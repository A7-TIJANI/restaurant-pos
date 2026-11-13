package com.epicit.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epicit.pos.config.AppConfig;
import com.epicit.pos.config.AppPaths;
import com.epicit.pos.service.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real file-based H2 databases, like BackupServiceTest. The audit row is covered by RestoreAuditTest. */
class RestoreTest {

    @TempDir Path dir;
    AppPaths paths;
    AppConfig config;
    JdbcTemplate live;
    Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneId.of("Africa/Kigali"));

    static void schema(JdbcTemplate db, int migrations, String name, int finalized) {
        db.execute("CREATE TABLE \"flyway_schema_history\" (\"version\" VARCHAR(50), \"success\" BOOLEAN)");
        for (int v = 1; v <= migrations; v++) {
            db.update("INSERT INTO \"flyway_schema_history\" VALUES (?, TRUE)", Integer.toString(v));
        }
        db.execute("CREATE TABLE settings (id INT PRIMARY KEY, restaurant_name VARCHAR(100))");
        db.update("INSERT INTO settings VALUES (1, ?)", name);
        db.execute("CREATE TABLE orders (id INT PRIMARY KEY, status VARCHAR(20))");
        for (int i = 1; i <= finalized; i++) {
            db.update("INSERT INTO orders VALUES (?, 'FINALIZED')", i);
        }
    }

    @BeforeEach
    void setUp() {
        paths = new AppPaths(dir.resolve("data"));
        config = new AppConfig(paths);
        config.setBackupPath(dir.resolve("backups"));
        live = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("data/epicit-pos").toAbsolutePath(), "sa", ""));
        schema(live, 3, "Old Grill", 2);
    }

    private BackupService backups() {
        return new BackupService(live, config, clock, () -> "Old Grill", "2.0.0");
    }

    private RestoreService restore() {
        return new RestoreService(paths, backups(), live, null);
    }

    @Test
    void stagingValidatesTheBackupAndLeavesTheLiveDatabaseAlone() {
        backups().backupNow();
        live.update("INSERT INTO orders VALUES (3, 'FINALIZED')");   // sold after the backup

        RestoreService.Staged staged = restore().stage("epicit-pos-backup-2026-11-12.zip");

        assertThat(staged.restaurantName()).isEqualTo("Old Grill");
        assertThat(staged.orders()).isEqualTo(2);
        assertThat(restore().restorePending()).isTrue();
        assertThat(live.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(3);   // untouched
    }

    @Test
    void theSwapReplacesTheDatabaseKeepsTheOldOneAndLeavesAMarker() throws IOException {
        backups().backupNow();
        restore().stage("epicit-pos-backup-2026-11-12.zip");
        // the app is closed before the swap: H2 flushes the live file
        live.execute("SHUTDOWN");

        assertThat(RestoreApplier.applyIfPending(paths, clock)).isTrue();

        assertThat(paths.restorePendingDir()).doesNotExist();
        assertThat(paths.restoreMarker()).exists();
        assertThat(Files.list(paths.dataDir()).map(p -> p.getFileName().toString()).toList())
                .anyMatch(n -> n.startsWith("epicit-pos.mv.db.before-restore-"));
        JdbcTemplate restored = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("data/epicit-pos").toAbsolutePath(), "sa", ""));
        assertThat(restored.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(2);
    }

    @Test
    void nothingPendingMeansNothingHappens() throws IOException {
        assertThat(RestoreApplier.applyIfPending(paths, clock)).isFalse();
    }

    @Test
    void aBackupFromANewerVersionIsRefused() {
        JdbcTemplate newer = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("newer/epicit-pos").toAbsolutePath(), "sa", ""));
        schema(newer, 9, "Future Grill", 1);
        new BackupService(newer, config, clock, () -> "Future Grill", "9.0.0").backupNow();

        assertThatThrownBy(() -> restore().stage("epicit-pos-backup-2026-11-12.zip"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("BACKUP_TOO_NEW"));
        assertThat(restore().restorePending()).isFalse();
    }

    @Test
    void aFileThatIsNotABackupIsRefusedAndNothingIsStaged() throws IOException {
        Files.createDirectories(config.backupPath());
        Files.writeString(config.backupPath().resolve("epicit-pos-backup-2026-01-01.zip"), "this is not a zip");

        assertThatThrownBy(() -> restore().stage("epicit-pos-backup-2026-01-01.zip"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("BACKUP_UNREADABLE"));
        assertThat(restore().restorePending()).isFalse();
        assertThat(paths.dataDir().resolve("restore-staging")).doesNotExist();
    }

    @Test
    void onlyNamesFromTheBackupListAreAccepted() {
        for (String evil : new String[] {"../config.properties", "..\\x.zip", "/etc/passwd", "", "epicit-pos-backup-2099-01-01.zip"}) {
            assertThatThrownBy(() -> restore().stage(evil))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("BACKUP_NOT_FOUND"));
        }
    }

    @Test
    void cancelDropsThePendingRestore() {
        backups().backupNow();
        restore().stage("epicit-pos-backup-2026-11-12.zip");
        restore().cancel();
        assertThat(restore().restorePending()).isFalse();
    }
}
