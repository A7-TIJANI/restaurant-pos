package com.epicit.pos.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads/writes {@code config.properties}, which sits outside the database on purpose: the backup
 * location must be knowable before the DB opens (first run, restore wizard). Holds only {@code backup_path}.
 */
public class AppConfig {

    static final String BACKUP_PATH_KEY = "backup_path";

    private final Path file;
    private final Properties props = new Properties();

    public AppConfig(AppPaths paths) {
        this.file = paths.configFile();
        try {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            if (props.getProperty(BACKUP_PATH_KEY, "").isBlank()) {
                props.setProperty(BACKUP_PATH_KEY, paths.defaultBackupDir().toAbsolutePath().toString());
                store();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create " + file, e);
        }
    }

    public synchronized Path backupPath() {
        return Path.of(props.getProperty(BACKUP_PATH_KEY));
    }

    public synchronized void setBackupPath(Path path) {
        props.setProperty(BACKUP_PATH_KEY, path.toAbsolutePath().toString());
        try {
            store();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private void store() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Epic IT POS - values needed before the database opens");
        }
    }
}
