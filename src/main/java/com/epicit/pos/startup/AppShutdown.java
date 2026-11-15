package com.epicit.pos.startup;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The packaged app has no window and no console, so closing the browser tab does not stop it, and a second
 * double-click only reopens the browser (A17). Without a way to stop it, a staged restore (FR-7.3, applied at the
 * next start) could never be finished. The owner's "Close POS" button ends up here.
 *
 * <p>The exit runs on its own thread after a short pause so the HTTP response that asked for it reaches the browser
 * first; then Spring closes the context (Tomcat, the connection pool and so H2, and port.txt are released cleanly)
 * and the JVM exits.
 */
@Component
public class AppShutdown {

    private static final Logger log = LoggerFactory.getLogger(AppShutdown.class);
    static final long DEFAULT_DELAY_MILLIS = 500;

    private final AtomicBoolean requested = new AtomicBoolean();
    private final Runnable exitAction;
    private final long delayMillis;

    public AppShutdown(ConfigurableApplicationContext context) {
        this(() -> System.exit(SpringApplication.exit(context)), DEFAULT_DELAY_MILLIS);
    }

    /** For tests: the exit action and the pause are injected so the test JVM is never stopped. */
    AppShutdown(Runnable exitAction, long delayMillis) {
        this.exitAction = exitAction;
        this.delayMillis = delayMillis;
    }

    /** @return true if this call started the shutdown, false if one was already under way. */
    public boolean requestExit() {
        if (!requested.compareAndSet(false, true)) {
            return false;
        }
        log.info("The owner asked to close Epic IT POS; shutting down.");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exitAction.run();
        }, "epicit-shutdown");
        t.start();
        return true;
    }
}
