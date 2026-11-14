package com.epicit.pos.recovery;

import java.time.Instant;
import java.util.List;

/**
 * What the restore wizard can do. The HTTP layer ({@link RecoveryServer}) only knows this interface, so it can be
 * tested without a database, Spring or real backup files; {@link SnapshotRecovery} is the real implementation.
 */
public interface RecoveryActions {

    record Entry(String name, long sizeBytes, Instant modified) {
    }

    record Restored(String restaurantName, long orders) {
    }

    /** Raised for anything the person should be told in plain words; mapped to {"error","message"} JSON. */
    class RecoveryException extends RuntimeException {
        private final int status;
        private final String code;

        public RecoveryException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    String backupFolder();

    /** Newest first. */
    List<Entry> backups();

    /** Validates the named backup and swaps it in for the broken database. Throws {@link RecoveryException}. */
    Restored restore(String backupName);
}
