package com.epicit.pos.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FR-7.2: a silent snapshot shortly after every start, on a daemon thread so it never delays the POS screen and
 * a failure (full disk, unplugged drive) is only logged, never shown to a cashier.
 */
@Component
public class StartupSnapshot {

    private static final Logger log = LoggerFactory.getLogger(StartupSnapshot.class);

    private final BackupService backups;
    private final boolean enabled;

    public StartupSnapshot(BackupService backups, @Value("${epicit.startup-snapshot:true}") boolean enabled) {
        this.backups = backups;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5_000);
                log.info("Startup snapshot written to {}", backups.backupNow());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Startup snapshot failed: {}", e.getMessage());
            }
        }, "startup-snapshot");
        t.setDaemon(true);
        t.start();
    }
}
