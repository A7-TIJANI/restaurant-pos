package com.epicit.pos.recovery;

import com.epicit.pos.config.AppPaths;
import com.epicit.pos.recovery.DatabaseHealth.Result;
import com.epicit.pos.recovery.DatabaseHealth.State;
import com.epicit.pos.startup.BrowserLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.function.Consumer;

/**
 * NFR-6, the decision point called from main() after a pending restore was applied and before Spring starts:
 * is the database usable? If yes, carry on. If it is broken, run the restore wizard and carry on only after a
 * backup was restored (the database is checked again, so a wizard that "succeeded" onto another bad file cannot
 * let Spring start on it).
 */
public final class RecoveryLauncher {

    private RecoveryLauncher() {
    }

    /**
     * @return true if the app should start normally, false if it should exit (the person chose to quit).
     * @throws IllegalStateException if another process holds the database (restoring would be the wrong advice).
     */
    public static boolean ensureDatabaseUsable(AppPaths paths, Clock clock, boolean openBrowser) {
        return ensureDatabaseUsable(paths, clock, openBrowser, url -> {
            if (openBrowser) {
                BrowserLauncher.open(url);
            }
        });
    }

    /** Test seam: {@code onWizardUrl} receives the wizard address instead of opening a browser. */
    public static boolean ensureDatabaseUsable(AppPaths paths, Clock clock, boolean openBrowser, Consumer<String> onWizardUrl) {
        while (true) {
            Result health = DatabaseHealth.checkWithRetry(paths);
            if (health.state() == State.OK) {
                return true;
            }
            if (health.state() == State.LOCKED) {
                throw new IllegalStateException(health.detail() + " Close it (or restart the laptop) and open Epic IT POS again. "
                        + "Your data is not damaged.");
            }
            System.err.println("The database cannot be opened (" + health.detail() + "). Starting the restore wizard.");
            try (RecoveryServer wizard = RecoveryServer.start(new SnapshotRecovery(paths, clock), health.detail(), paths.portFile())) {
                onWizardUrl.accept(wizard.url());
                if (!wizard.awaitOutcome()) {
                    return false;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the restore wizard", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            // loop: probe the restored database before letting Spring near it
        }
    }
}
