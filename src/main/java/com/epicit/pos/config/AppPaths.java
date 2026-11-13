package com.epicit.pos.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where everything lives on the laptop (A11). The USB stick is never touched at runtime.
 *
 * <pre>
 * %LOCALAPPDATA%\EpicIT POS\
 *   epicit-pos.mv.db     H2 database
 *   config.properties    settings that must be known before the DB opens (backup_path)
 *   port.txt             port of the running instance (single-instance guard, A17)
 *   backups\             default snapshot folder
 * </pre>
 */
public record AppPaths(Path dataDir) {

    public static final String DATA_DIR_PROPERTY = "epicit.data-dir";

    /** Resolves the data folder: explicit override, then %LOCALAPPDATA%, then ~/.epicit-pos (dev on non-Windows). */
    public static AppPaths resolve() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new AppPaths(Path.of(override));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData, "EpicIT POS")
                : Path.of(System.getProperty("user.home"), ".epicit-pos");
        return new AppPaths(base);
    }

    /** H2 appends .mv.db itself, so this is the path without extension. */
    public Path dbBase() {
        return dataDir.resolve("epicit-pos");
    }

    public Path portFile() {
        return dataDir.resolve("port.txt");
    }

    public Path configFile() {
        return dataDir.resolve("config.properties");
    }

    public Path defaultBackupDir() {
        return dataDir.resolve("backups");
    }

    /** File whose presence at startup triggers the forced PIN reset (A14). Wired up in the security work. */
    public Path resetPinFile() {
        return dataDir.resolve("RESET-PIN");
    }

    public String jdbcUrl() {
        return "jdbc:h2:file:" + dbBase().toAbsolutePath().toString().replace('\\', '/');
    }

    /** A validated backup waiting to be swapped in at the next start (FR-7.3). */
    public Path restorePendingDir() {
        return dataDir.resolve("restore-pending");
    }

    /** Written by the swap, read once by the next running instance to write the RESTORE audit row. */
    public Path restoreMarker() {
        return dataDir.resolve("restore-done.properties");
    }

    public Path dbFile() {
        return dataDir.resolve("epicit-pos.mv.db");
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(dataDir);
    }
}
