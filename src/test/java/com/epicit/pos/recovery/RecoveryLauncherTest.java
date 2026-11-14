package com.epicit.pos.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.backup.BackupService;
import com.epicit.pos.config.AppConfig;
import com.epicit.pos.config.AppPaths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NFR-6 end to end on real files: a live database that cannot be opened, a real backup, the wizard driven over
 * HTTP exactly as the page does it, and the app cleared to start only on a database that opens.
 */
class RecoveryLauncherTest {

    private static final String BACKUP = "epicit-pos-backup-2026-11-12.zip";

    @TempDir Path dir;
    AppPaths paths;
    AppConfig config;
    Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneId.of("Africa/Kigali"));
    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        paths = new AppPaths(dir.resolve("data"));
        paths.ensureDirectories();
        config = new AppConfig(paths);
        config.setBackupPath(dir.resolve("backups"));

        JdbcTemplate live = new JdbcTemplate(new DriverManagerDataSource(paths.jdbcUrl(), "sa", ""));
        live.execute("CREATE TABLE \"flyway_schema_history\" (\"version\" VARCHAR(50), \"success\" BOOLEAN)");
        for (int v = 1; v <= 3; v++) {
            live.update("INSERT INTO \"flyway_schema_history\" VALUES (?, TRUE)", Integer.toString(v));
        }
        live.execute("CREATE TABLE settings (id INT PRIMARY KEY, restaurant_name VARCHAR(100))");
        live.update("INSERT INTO settings VALUES (1, 'Nile Grill')");
        live.execute("CREATE TABLE orders (id INT PRIMARY KEY, status VARCHAR(20))");
        live.update("INSERT INTO orders VALUES (1, 'FINALIZED')");
        live.update("INSERT INTO orders VALUES (2, 'FINALIZED')");
        new BackupService(live, config, clock, () -> "Nile Grill", "2.0.0").backupNow();
        live.execute("SHUTDOWN");
    }

    private byte[] corruptTheLiveDatabase() throws Exception {
        byte[] junk = new byte[16 * 1024];
        Arrays.fill(junk, (byte) 'x');
        Files.write(paths.dbFile(), junk);
        return junk;
    }

    private static String tokenOf(String page) {
        Matcher m = Pattern.compile("data-token=\"([^\"]+)\"").matcher(page);
        assertThat(m.find()).as("the page carries its token").isTrue();
        return m.group(1);
    }

    private HttpResponse<String> postAsThePageDoes(String wizardUrl, String path, String body) throws Exception {
        String page = http.send(HttpRequest.newBuilder(URI.create(wizardUrl)).build(), HttpResponse.BodyHandlers.ofString()).body();
        return http.send(HttpRequest.newBuilder(URI.create(wizardUrl + path))
                .header("Content-Type", "application/json").header(RecoveryServer.TOKEN_HEADER, tokenOf(page))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aHealthyDatabaseStartsTheAppWithoutEverShowingTheWizard() {
        List<String> shown = new ArrayList<>();

        boolean start = RecoveryLauncher.ensureDatabaseUsable(paths, clock, false, shown::add);

        assertThat(start).isTrue();
        assertThat(shown).isEmpty();
    }

    @Test
    void aBrokenDatabaseShowsTheWizardAndRestoringABackupLetsTheAppStart() throws Exception {
        byte[] junk = corruptTheLiveDatabase();
        List<String> restoreResponses = new ArrayList<>();

        boolean start = RecoveryLauncher.ensureDatabaseUsable(paths, clock, false, url -> {
            try {
                String status = http.send(HttpRequest.newBuilder(URI.create(url + "api/recovery/status")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                assertThat(status).contains(BACKUP);
                restoreResponses.add(postAsThePageDoes(url, "api/recovery/restore", "{\"name\":\"" + BACKUP + "\"}").body());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(start).isTrue();
        assertThat(restoreResponses.get(0)).contains("\"restored\":true").contains("Nile Grill").contains("\"orders\":2");
        // the app may now open the database, and it holds the backup's data
        assertThat(DatabaseHealth.check(paths).state()).isEqualTo(DatabaseHealth.State.OK);
        JdbcTemplate restored = new JdbcTemplate(new DriverManagerDataSource(paths.jdbcUrl(), "sa", ""));
        assertThat(restored.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isEqualTo(2);
        // nothing was deleted: the damaged file is still there for examination, byte for byte
        Path kept = Files.list(paths.dataDir()).filter(p -> p.getFileName().toString().startsWith("epicit-pos.mv.db.before-restore-"))
                .findFirst().orElseThrow();
        assertThat(Files.readAllBytes(kept)).isEqualTo(junk);
        // and the next normal start will write the RESTORE audit row
        assertThat(paths.restoreMarker()).exists();
        // the wizard cleaned up after itself
        assertThat(paths.portFile()).doesNotExist();
        assertThat(paths.restorePendingDir()).doesNotExist();
    }

    @Test
    void closingTheWizardLeavesTheBrokenFileUntouchedAndTheAppDoesNotStart() throws Exception {
        byte[] junk = corruptTheLiveDatabase();

        boolean start = RecoveryLauncher.ensureDatabaseUsable(paths, clock, false, url -> {
            try {
                assertThat(postAsThePageDoes(url, "api/recovery/quit", "").statusCode()).isEqualTo(200);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(start).isFalse();
        assertThat(Files.readAllBytes(paths.dbFile())).isEqualTo(junk);
        assertThat(paths.restoreMarker()).doesNotExist();
    }

    @Test
    void aFailedRestoreKeepsTheWizardOpenSoAnotherBackupCanBeTried() throws Exception {
        corruptTheLiveDatabase();
        Files.writeString(config.backupPath().resolve("epicit-pos-backup-2026-11-13.zip"), "this is not a zip");
        List<Integer> statuses = new ArrayList<>();

        boolean start = RecoveryLauncher.ensureDatabaseUsable(paths, clock, false, url -> {
            try {
                statuses.add(postAsThePageDoes(url, "api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-13.zip\"}").statusCode());
                statuses.add(postAsThePageDoes(url, "api/recovery/restore", "{\"name\":\"" + BACKUP + "\"}").statusCode());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(statuses).containsExactly(422, 200);
        assertThat(start).isTrue();
    }

    @Test
    void theWizardOnlyRestoresNamesFromTheBackupList() throws Exception {
        corruptTheLiveDatabase();
        List<Integer> statuses = new ArrayList<>();

        RecoveryLauncher.ensureDatabaseUsable(paths, clock, false, url -> {
            try {
                for (String evil : new String[] {"../config.properties", "..\\x.zip", "/etc/passwd", ""}) {
                    statuses.add(postAsThePageDoes(url, "api/recovery/restore", "{\"name\":\"" + evil.replace("\\", "") + "\"}").statusCode());
                }
                postAsThePageDoes(url, "api/recovery/quit", "");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(statuses).containsOnly(404);
    }
}
