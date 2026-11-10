package com.epicit.pos.web;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * A15: entering the PIN once opens an owner session that ends after 10 idle minutes or on an explicit Lock.
 * State lives in the server-side HttpSession; the browser only holds an opaque session cookie.
 */
@Component
public class OwnerSession {

    static final String ATTR = "ownerLastActivity";
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final Clock clock;

    public OwnerSession(Clock clock) {
        this.clock = clock;
    }

    public void open(HttpSession session) {
        session.setAttribute(ATTR, clock.instant());
    }

    public void lock(HttpSession session) {
        session.removeAttribute(ATTR);
    }

    /** True while the session is within the idle window; each successful check extends it. */
    public boolean touchIfActive(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object last = session.getAttribute(ATTR);
        if (!(last instanceof Instant lastActivity)) {
            return false;
        }
        Instant now = clock.instant();
        if (Duration.between(lastActivity, now).compareTo(IDLE_TIMEOUT) > 0) {
            session.removeAttribute(ATTR);
            return false;
        }
        session.setAttribute(ATTR, now);
        return true;
    }

    /** Read-only variant for the status endpoint (does not extend the session). */
    public boolean isActive(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object last = session.getAttribute(ATTR);
        return last instanceof Instant lastActivity
                && Duration.between(lastActivity, clock.instant()).compareTo(IDLE_TIMEOUT) <= 0;
    }
}
