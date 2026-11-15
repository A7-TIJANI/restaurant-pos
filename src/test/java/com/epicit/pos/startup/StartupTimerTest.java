package com.epicit.pos.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupTimerTest {

    @TempDir Path dir;

    @Test
    void theLineSaysWhenAndHowLong() {
        String line = StartupTimer.line(ZonedDateTime.of(2026, 11, 15, 8, 30, 0, 0, ZoneId.of("Africa/Kigali")), 3412);

        assertThat(line).startsWith("2026-11-15T08:30+02:00").contains("ready 3412 ms after JVM start").contains("Java ");
    }

    @Test
    void appendsAcrossStartsAndCreatesTheFolderAndFile() throws Exception {
        Path file = dir.resolve("data").resolve("startup-times.log");

        StartupTimer.record(file, "first");
        StartupTimer.record(file, "second");

        assertThat(Files.readAllLines(file)).containsExactly("first", "second");
    }

    @Test
    void keepsOnlyTheNewestHundredStarts() throws Exception {
        Path file = dir.resolve("startup-times.log");

        for (int i = 1; i <= StartupTimer.KEEP_LINES + 25; i++) {
            StartupTimer.record(file, "start " + i);
        }

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(StartupTimer.KEEP_LINES);
        assertThat(lines.get(0)).isEqualTo("start 26");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("start " + (StartupTimer.KEEP_LINES + 25));
    }

    @Test
    void anUnwritableLocationNeverThrows() throws Exception {
        Path blocker = Files.writeString(dir.resolve("a-file"), "x");   // a file where a folder is needed

        StartupTimer.record(blocker.resolve("startup-times.log"), "ignored");   // must simply not throw
    }
}
