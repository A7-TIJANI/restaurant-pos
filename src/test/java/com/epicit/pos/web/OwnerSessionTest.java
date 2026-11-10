package com.epicit.pos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/** A15: 10-minute idle window, extended by activity, ended by Lock. No Spring context needed. */
class OwnerSessionTest {

    /** A clock the test can move. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-10T08:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneId.of("Africa/Kigali"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    TestClock clock;
    OwnerSession owner;
    MockHttpSession session;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        owner = new OwnerSession(clock);
        session = new MockHttpSession();
    }

    @Test
    void aNewSessionIsNotUnlocked() {
        assertThat(owner.touchIfActive(session)).isFalse();
        assertThat(owner.isActive(session)).isFalse();
    }

    @Test
    void noSessionAtAllIsNotUnlocked() {
        assertThat(owner.touchIfActive(null)).isFalse();
        assertThat(owner.isActive(null)).isFalse();
    }

    @Test
    void openingUnlocksUntilTheIdleWindowEnds() {
        owner.open(session);

        clock.advance(Duration.ofMinutes(9));
        assertThat(owner.touchIfActive(session)).isTrue();

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(owner.touchIfActive(session)).isFalse();
    }

    @Test
    void activityKeepsTheSessionAlive() {
        owner.open(session);
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(9));          // 45 minutes in total, never idle for 10
            assertThat(owner.touchIfActive(session)).isTrue();
        }
    }

    @Test
    void anExpiredSessionStaysLockedEvenIfItIsUsedAgain() {
        owner.open(session);
        clock.advance(Duration.ofMinutes(11));
        assertThat(owner.touchIfActive(session)).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(owner.touchIfActive(session)).isFalse();   // needs the PIN again
    }

    @Test
    void checkingStatusDoesNotExtendTheSession() {
        owner.open(session);
        clock.advance(Duration.ofMinutes(8));
        assertThat(owner.isActive(session)).isTrue();

        clock.advance(Duration.ofMinutes(3));                  // 11 minutes since open: isActive did not refresh it
        assertThat(owner.isActive(session)).isFalse();
    }

    @Test
    void lockEndsTheSessionImmediately() {
        owner.open(session);
        owner.lock(session);
        assertThat(owner.touchIfActive(session)).isFalse();
    }

    @Test
    void sessionsAreIndependent() {
        MockHttpSession cashier = new MockHttpSession();
        owner.open(session);
        assertThat(owner.touchIfActive(cashier)).isFalse();
    }
}
