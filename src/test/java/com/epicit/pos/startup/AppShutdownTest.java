package com.epicit.pos.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AppShutdownTest {

    @Test
    void runsTheExitActionOnceEvenIfAskedTwice() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        AppShutdown shutdown = new AppShutdown(() -> { runs.incrementAndGet(); ran.countDown(); }, 10);

        assertThat(shutdown.requestExit()).isTrue();
        assertThat(shutdown.requestExit()).isFalse();

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);   // a second run, if there were one, would have happened by now
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void waitsBeforeExitingSoTheHttpResponseCanBeSent() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        AppShutdown shutdown = new AppShutdown(ran::countDown, 400);

        long start = System.nanoTime();
        shutdown.requestExit();
        assertThat(ran.getCount()).as("returns immediately, exit comes later").isEqualTo(1);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(350);
    }
}
