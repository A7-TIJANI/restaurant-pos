package com.epicit.pos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @TempDir Path dir;

    @Test
    void firstRunCreatesConfigWithDefaultBackupFolderOnTheLaptop() {
        AppPaths paths = new AppPaths(dir);
        AppConfig config = new AppConfig(paths);

        assertThat(config.backupPath()).isEqualTo(paths.defaultBackupDir().toAbsolutePath());
        assertThat(Files.isRegularFile(paths.configFile())).isTrue();
    }

    @Test
    void changedBackupPathSurvivesARestart() {
        AppPaths paths = new AppPaths(dir);
        new AppConfig(paths).setBackupPath(dir.resolve("my-backups"));

        assertThat(new AppConfig(paths).backupPath()).isEqualTo(dir.resolve("my-backups").toAbsolutePath());
    }

    @Test
    void jdbcUrlPointsAtTheLaptopDataFolderWithForwardSlashes() {
        AppPaths paths = new AppPaths(dir);
        assertThat(paths.jdbcUrl()).startsWith("jdbc:h2:file:").endsWith("/epicit-pos").doesNotContain("\\");
    }

    @Test
    void timezoneIsPinnedToKigali() {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            com.epicit.pos.EpicItPosApplication.pinTimezone();
            assertThat(java.util.TimeZone.getDefault().getID()).isEqualTo("Africa/Kigali");
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }
}
