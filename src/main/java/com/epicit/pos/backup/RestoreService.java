package com.epicit.pos.backup;

import com.epicit.pos.config.AppPaths;
import com.epicit.pos.service.AuditService;
import com.epicit.pos.service.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

/**
 * FR-7.3, the validate-and-stage half. The chosen backup is unpacked into a staging folder and opened read-only
 * to prove it is a real, readable, not-too-new Epic IT POS database. Only then does it move to
 * {@code restore-pending/}; the live database is not touched until the next start (see RestoreApplier).
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    public record Staged(String backup, String restaurantName, long orders) {
    }

    private final AppPaths paths;
    private final BackupService backups;
    private final JdbcTemplate live;
    private final AuditService audit;

    @Autowired
    public RestoreService(AppPaths paths, BackupService backups, JdbcTemplate live, AuditService audit) {
        this.paths = paths;
        this.backups = backups;
        this.live = live;
        this.audit = audit;
    }

    public synchronized Staged stage(String backupName) {
        // only a name the backup list itself produced: no paths, no "..", nothing outside the backup folder
        if (backups.list().stream().noneMatch(b -> b.name().equals(backupName))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", "That backup is not in the backup folder.");
        }
        Path zipFile = backups.folder().resolve(backupName);
        Path staging = paths.dataDir().resolve("restore-staging");
        try {
            RestoreApplier.deleteTree(staging);
            Files.createDirectories(staging);
            extract(zipFile, staging);
            Staged info = validate(staging, backupName);
            Path pending = paths.restorePendingDir();
            RestoreApplier.deleteTree(pending);
            Files.createDirectories(pending);
            Files.move(staging.resolve(BackupService.DB_ENTRY), pending.resolve(RestoreApplier.PENDING_DB));
            Files.writeString(pending.resolve("source.txt"), backupName, StandardCharsets.UTF_8);
            return info;
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_UNREADABLE",
                    "This backup file could not be read. Pick another one.");
        } finally {
            try {
                RestoreApplier.deleteTree(staging);
            } catch (IOException ignored) {
                // leftover staging is wiped at the next attempt
            }
        }
    }

    public synchronized void cancel() {
        try {
            RestoreApplier.deleteTree(paths.restorePendingDir());
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "CANCEL_FAILED", "Could not cancel the restore.");
        }
    }

    public boolean restorePending() {
        return Files.isRegularFile(paths.restorePendingDir().resolve(RestoreApplier.PENDING_DB));
    }

    /** After a restart that swapped the database in, the running app records it once. */
    @EventListener(ApplicationReadyEvent.class)
    public void auditCompletedRestore() {
        Path marker = paths.restoreMarker();
        if (!Files.isRegularFile(marker)) {
            return;
        }
        try (InputStream in = Files.newInputStream(marker)) {
            Properties p = new Properties();
            p.load(in);
            audit.record("RESTORE", "database", p.getProperty("previous_database", ""), p.getProperty("backup", ""),
                    "Database restored from backup");
            Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not record the restore in the audit log: {}", e.getMessage());
        }
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private static void extract(Path zipFile, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry db = zip.getEntry(BackupService.DB_ENTRY);
            if (db == null || zip.getEntry(BackupService.MANIFEST_ENTRY) == null) {
                throw new IOException("not an Epic IT POS backup");
            }
            try (InputStream in = zip.getInputStream(db); OutputStream out = Files.newOutputStream(dest.resolve(BackupService.DB_ENTRY))) {
                in.transferTo(out);
            }
        }
    }

    private Staged validate(Path staging, String backupName) throws IOException {
        String base = staging.resolve(BackupService.DB_ENTRY).toAbsolutePath().toString().replaceAll("\\.mv\\.db$", "").replace('\\', '/');
        try {
            JdbcTemplate copy = new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:file:" + base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", ""));
            int theirs = maxMigration(copy);
            int ours = maxMigration(live);
            if (theirs > ours) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_TOO_NEW",
                        "This backup was made by a newer version of the app. Update the app first.");
            }
            String name = copy.queryForObject("SELECT restaurant_name FROM settings WHERE id = 1", String.class);
            Long orders = copy.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'FINALIZED'", Long.class);
            return new Staged(backupName, name, orders == null ? 0 : orders);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IOException("backup database is not readable: " + e.getMessage(), e);
        }
    }

    private static int maxMigration(JdbcTemplate db) {
        return db.queryForList("SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = TRUE AND \"version\" IS NOT NULL",
                String.class).stream().mapToInt(v -> Integer.parseInt(v.split("\\.")[0])).max().orElse(0);
    }
}
