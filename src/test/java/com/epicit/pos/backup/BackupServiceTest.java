package com.epicit.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.config.AppConfig;
import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses a real file-based H2 database: BACKUP TO does not work on in-memory databases. */
class BackupServiceTest {

    static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @TempDir Path dir;
    JdbcTemplate jdbc;
    AppConfig config;
    Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), KIGALI);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("live/epicit-pos").toAbsolutePath(), "sa", ""));
        jdbc.execute("CREATE TABLE sale (id INT PRIMARY KEY, label VARCHAR(50))");
        jdbc.update("INSERT INTO sale VALUES (1, 'Meat Kofta')");
        config = new AppConfig(new AppPaths(dir.resolve("data")));
        config.setBackupPath(dir.resolve("backups"));
    }

    private BackupService service() {
        return new BackupService(jdbc, config, clock, () -> "Epic \"Grill\"", "2.0.0");
    }

    @Test
    void backupIsADatedZipWithTheDatabaseAndAManifest() throws IOException {
        Path file = service().backupNow();

        assertThat(file.getFileName().toString()).isEqualTo("epicit-pos-backup-2026-11-12.zip");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).toList())
                    .containsExactlyInAnyOrder("epicit-pos.mv.db", "manifest.json");
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"version\": \"2.0.0\"").contains("\"restaurant_name\": \"Epic \\\"Grill\\\"\"")
                    .contains("2026-11-12T10:00:00Z");
        }
    }

    @Test
    void theBackedUpDatabaseOpensAndHoldsTheData() throws IOException {
        Path file = service().backupNow();
        Path restored = BackupService.extractDb(file, dir.resolve("restored"));
        String base = restored.toString().replaceAll("\\.mv\\.db$", "");

        JdbcTemplate copy = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:" + base, "sa", ""));
        assertThat(copy.queryForObject("SELECT label FROM sale WHERE id = 1", String.class)).isEqualTo("Meat Kofta");
    }

    @Test
    void leavesNoTemporaryFilesBehindAndRemovesStaleOnes() throws IOException {
        Files.createDirectories(config.backupPath());
        Files.writeString(config.backupPath().resolve("epicit-pos-backup-2026-11-01.zip.tmp"), "half written");

        service().backupNow();

        try (var files = Files.list(config.backupPath())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactly("epicit-pos-backup-2026-11-12.zip");
        }
    }

    @Test
    void backingUpTwiceInADayReplacesTheEarlierFile() {
        BackupService s = service();
        s.backupNow();
        jdbc.update("INSERT INTO sale VALUES (2, 'Chicken Kofta')");
        s.backupNow();

        assertThat(s.list()).hasSize(1);
    }

    @Test
    void keepsOnlyTheNewestThirty() throws IOException {
        Files.createDirectories(config.backupPath());
        for (int i = 0; i < 40; i++) {
            Files.writeString(config.backupPath().resolve(
                    "epicit-pos-backup-" + java.time.LocalDate.of(2026, 10, 31).minusDays(i) + ".zip"), "x");
        }
        Files.writeString(config.backupPath().resolve("notes.txt"), "not a backup");

        service().backupNow();

        List<BackupService.BackupInfo> list = service().list();
        assertThat(list).hasSize(30);
        assertThat(list.get(0).name()).isEqualTo("epicit-pos-backup-2026-11-12.zip");   // newest first
        assertThat(Files.exists(config.backupPath().resolve("notes.txt"))).isTrue();    // foreign files untouched
        assertThat(list).noneMatch(b -> b.name().contains("2026-09"));                  // the oldest went first
    }

    @Test
    void listIsEmptyWhenTheFolderDoesNotExistYet() {
        assertThat(service().list()).isEmpty();
    }
}
