package com.epicit.pos.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.config.AppPaths;
import com.epicit.pos.recovery.DatabaseHealth.Result;
import com.epicit.pos.recovery.DatabaseHealth.State;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NFR-6: "opening the database is the integrity check", against real H2 files. */
class DatabaseHealthTest {

    @TempDir Path dir;
    AppPaths paths;

    @BeforeEach
    void setUp() throws IOException {
        paths = new AppPaths(dir.resolve("data"));
        paths.ensureDirectories();
    }

    private void createRealDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection(paths.jdbcUrl(), "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            s.execute("INSERT INTO t VALUES (1)");
            s.execute("SHUTDOWN");
        }
    }

    @Test
    void aFirstRunWithNoDatabaseFileIsFineAndCreatesNothing() {
        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.OK);
        assertThat(paths.dbFile()).doesNotExist();
    }

    @Test
    void aHealthyDatabaseIsOkAndTheProbeLeavesItClosedForSpring() throws Exception {
        createRealDatabase();

        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.OK);
        // the probe must have released the file: opening it again (as Spring will) works
        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.OK);
        try (Connection c = DriverManager.getConnection(paths.jdbcUrl(), "sa", "")) {
            assertThat(c.isValid(1)).isTrue();
        }
    }

    @Test
    void aGarbageFileIsBrokenAndIsNotModified() throws Exception {
        byte[] junk = new byte[16 * 1024];
        Arrays.fill(junk, (byte) 'x');
        Files.write(paths.dbFile(), junk);

        Result r = DatabaseHealth.check(paths);

        assertThat(r.state()).isEqualTo(State.BROKEN);
        assertThat(r.detail()).isNotBlank();
        assertThat(Files.readAllBytes(paths.dbFile())).isEqualTo(junk);   // the probe never "repairs" or rewrites
    }

    @Test
    void aZeroByteFileIsBrokenNotSilentlyTreatedAsANewEmptyDatabase() throws Exception {
        Files.write(paths.dbFile(), new byte[0]);

        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.BROKEN);
        assertThat(Files.size(paths.dbFile())).isZero();
    }

    @Test
    void aTruncatedDatabaseIsBroken() throws Exception {
        createRealDatabase();
        byte[] all = Files.readAllBytes(paths.dbFile());
        Files.write(paths.dbFile(), Arrays.copyOf(all, Math.min(all.length, 100)));

        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.BROKEN);
    }

    /**
     * H2 lets connections inside one JVM share a database, so "locked" only happens across processes: exactly the
     * "second copy of the app" case. A child JVM plays the other copy.
     */
    @Test
    void aDatabaseHeldOpenByAnotherProcessIsLockedNotBroken() throws Exception {
        createRealDatabase();
        Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Holder.class.getName(), paths.jdbcUrl())
                .redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(other.getInputStream()));
            String line;
            while ((line = out.readLine()) != null && !line.equals("ready")) {
                // skip JVM notices printed before the child has opened the database
            }
            assertThat(line).as("child JVM opened the database").isEqualTo("ready");

            Result r = DatabaseHealth.check(paths);

            assertThat(r.state()).isEqualTo(State.LOCKED);
            assertThat(r.detail()).contains("Another copy");
        } finally {
            other.getOutputStream().write(1);   // child exits and releases the file
            other.getOutputStream().flush();
            other.waitFor(30, TimeUnit.SECONDS);
            other.destroyForcibly();
        }
        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(State.OK);
    }

    /** The "other copy of the app": opens the database, says so, and holds it until stdin gets a byte. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            try (Connection c = DriverManager.getConnection(args[0], "sa", "")) {
                System.out.println("ready");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
