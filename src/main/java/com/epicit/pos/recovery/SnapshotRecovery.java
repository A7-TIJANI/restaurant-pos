package com.epicit.pos.recovery;

import com.epicit.pos.backup.BackupService;
import com.epicit.pos.backup.RestoreApplier;
import com.epicit.pos.backup.RestoreService;
import com.epicit.pos.config.AppConfig;
import com.epicit.pos.config.AppPaths;
import com.epicit.pos.service.BusinessException;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * The real {@link RecoveryActions}: the same validation and swap code as the normal Owner &gt; Data &gt; Restore
 * (RestoreService stages and validates, RestoreApplier swaps), driven without a database. The broken file is kept
 * as {@code epicit-pos.mv.db.before-restore-<timestamp>}, never deleted, and the RESTORE audit row is written by
 * the next normal start from the marker file the swap leaves behind.
 */
public final class SnapshotRecovery implements RecoveryActions {

    private final AppPaths paths;
    private final Clock clock;
    private final BackupService backups;
    private final RestoreService restoreService;

    public SnapshotRecovery(AppPaths paths, Clock clock) {
        this.paths = paths;
        this.clock = clock;
        // config.properties lives outside the database on purpose (see AppConfig): the backup folder is known here
        this.backups = BackupService.forRecovery(new AppConfig(paths), clock);
        this.restoreService = RestoreService.forRecovery(paths, backups);
    }

    @Override
    public String backupFolder() {
        return backups.folder().toString();
    }

    @Override
    public List<Entry> backups() {
        return backups.list().stream().map(b -> new Entry(b.name(), b.sizeBytes(), b.modified())).toList();
    }

    @Override
    public synchronized Restored restore(String backupName) {
        RestoreService.Staged staged;
        try {
            staged = restoreService.stage(backupName);
        } catch (BusinessException e) {
            throw new RecoveryException(e.getStatus().value(), e.getCode(), e.getMessage());
        }
        try {
            if (!RestoreApplier.applyIfPending(paths, clock)) {
                throw new RecoveryException(500, "RESTORE_FAILED", "The backup could not be put in place. Nothing was changed.");
            }
        } catch (IOException e) {
            restoreService.cancel();
            throw new RecoveryException(500, "RESTORE_FAILED",
                    "The backup could not be put in place (" + e.getMessage() + "). Your data folder was not changed.");
        }
        return new Restored(staged.restaurantName(), staged.orders());
    }
}
