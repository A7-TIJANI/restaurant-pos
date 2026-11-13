package com.epicit.pos.backup;

import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * FR-7.3, the swap half. Runs in main() BEFORE the database is opened: a restore chosen while the app was running
 * waits in {@code restore-pending/}, and here it replaces the live file. The old database is never deleted: it is
 * renamed to {@code epicit-pos.mv.db.before-restore-<timestamp>} so a wrong restore can be undone by hand.
 */
public final class RestoreApplier {

    static final String PENDING_DB = BackupService.DB_ENTRY;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private RestoreApplier() {
    }

    /** Returns true if a pending restore was applied. */
    public static boolean applyIfPending(AppPaths paths, Clock clock) throws IOException {
        Path pendingDir = paths.restorePendingDir();
        Path pendingDb = pendingDir.resolve(PENDING_DB);
        if (!Files.isRegularFile(pendingDb)) {
            return false;
        }
        Path live = paths.dbFile();
        String backupName = Files.exists(pendingDir.resolve("source.txt"))
                ? Files.readString(pendingDir.resolve("source.txt")).trim() : "unknown";
        String sideName = "";
        if (Files.exists(live)) {
            sideName = live.getFileName() + ".before-restore-" + STAMP.format(clock.instant());
            Files.move(live, paths.dataDir().resolve(sideName));
        }
        try {
            Files.move(pendingDb, live, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!sideName.isEmpty()) {   // put the original back so the next start still works
                Files.move(paths.dataDir().resolve(sideName), live, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
        // stale H2 side files belong to the old database
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(paths.dataDir(), "epicit-pos.*.db")) {
            for (Path p : stale) {
                if (!p.equals(live)) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Properties marker = new Properties();
        marker.setProperty("backup", backupName);
        marker.setProperty("previous_database", sideName);
        try (OutputStream out = Files.newOutputStream(paths.restoreMarker())) {
            marker.store(out, "Read once by the running app to write the RESTORE audit row");
        }
        deleteTree(pendingDir);
        return true;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
