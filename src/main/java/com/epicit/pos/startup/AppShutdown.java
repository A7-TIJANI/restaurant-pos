package com.epicit.pos.startup;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The packaged app has no window and no console, so closing the browser tab does not stop it, and a second
 * double-click only reopens the browser (A17). Without a way to stop it, a staged restore (FR-7.3, applied at the
 * next start) could never be finished. The owner's "Close POS" button ends up here.
 *
 * <p>Order matters, because the owner may double-click the exe again a moment after closing:
 * <ol>
 *   <li>{@link State#CLOSING}: the HTTP response that asked for the exit reaches the browser first.</li>
 *   <li>The database is released <em>explicitly</em> (H2 {@code SHUTDOWN}, then the connection pool is closed), so
 *       the {@code .mv.db} file lock is gone before anything else is torn down.</li>
 *   <li>{@link State#RELEASED}: the screen polls {@code /api/shutdown-status} and shows "closed" only now. The
 *       pause that follows lets the browser see it.</li>
 *   <li>Spring closes the rest (Tomcat, port.txt) and the JVM exits.</li>
 * </ol>
 */
@Component
public class AppShutdown {

    /** What the browser can learn from {@code /api/shutdown-status}. */
    public enum State { RUNNING, CLOSING, RELEASED }

    private static final Logger log = LoggerFactory.getLogger(AppShutdown.class);
    static final long DEFAULT_DELAY_MILLIS = 500;
    static final long DEFAULT_GRACE_MILLIS = 1000;

    private final AtomicBoolean requested = new AtomicBoolean();
    private volatile State state = State.RUNNING;
    private final Runnable releaseAction;
    private final Runnable exitAction;
    private final long delayMillis;
    private final long graceMillis;

    @Autowired
    public AppShutdown(ConfigurableApplicationContext context, DataSource dataSource) {
        this(() -> releaseDatabase(dataSource), () -> System.exit(SpringApplication.exit(context)),
                DEFAULT_DELAY_MILLIS, DEFAULT_GRACE_MILLIS);
    }

    /** For tests: nothing to release, no grace pause; the exit action is injected so the test JVM is never stopped. */
    AppShutdown(Runnable exitAction, long delayMillis) {
        this(() -> { }, exitAction, delayMillis, 0);
    }

    AppShutdown(Runnable releaseAction, Runnable exitAction, long delayMillis, long graceMillis) {
        this.releaseAction = releaseAction;
        this.exitAction = exitAction;
        this.delayMillis = delayMillis;
        this.graceMillis = graceMillis;
    }

    public State state() {
        return state;
    }

    /** @return true if this call started the shutdown, false if one was already under way. */
    public boolean requestExit() {
        if (!requested.compareAndSet(false, true)) {
            return false;
        }
        state = State.CLOSING;
        log.info("The owner asked to close Epic IT POS; shutting down.");
        Thread t = new Thread(() -> {
            pause(delayMillis);
            try {
                releaseAction.run();
            } catch (RuntimeException e) {
                // Never let a failed release stop the exit; Spring's own close still runs afterwards.
                log.warn("Releasing the database before exit failed; continuing with the normal shutdown.", e);
            }
            state = State.RELEASED;
            pause(graceMillis);
            exitAction.run();
        }, "epicit-shutdown");
        t.start();
        return true;
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * H2 keeps the file open until its last connection closes, and the pool keeps connections warm, so closing the
     * Spring context alone takes a couple of seconds on a slow laptop. Ask H2 to shut the database down now, then close
     * the pool. Closing the pool again later, when Spring closes the context, is harmless.
     */
    static void releaseDatabase(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
                if (pool.getJdbcUrl() != null && pool.getJdbcUrl().startsWith("jdbc:h2:")) {
                    try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                        s.execute("SHUTDOWN");
                    } catch (SQLException e) {
                        log.warn("H2 SHUTDOWN failed; closing the pool anyway.", e);
                    }
                }
                pool.close();
            }
        } catch (SQLException e) {
            log.warn("Could not release the database cleanly.", e);
        }
    }
}
