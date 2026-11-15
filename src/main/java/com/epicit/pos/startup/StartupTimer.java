package com.epicit.pos.startup;

import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * NFR-11 says cold start must be <em>measured</em> on the owner's laptop before it is promised. This records, on every
 * start, how long the server side took: from the JVM's start (so including the database probe, Flyway and Tomcat) to
 * "ready to serve". It does not include the launcher before the JVM, nor the browser opening and drawing the page: for
 * the double-click-to-usable figure, time the whole thing with a stopwatch and use this log to see where it went.
 * The log is a few hundred bytes a day and trimmed to the last {@value #KEEP_LINES} starts.
 */
@Component
public class StartupTimer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupTimer.class);
    static final int KEEP_LINES = 100;

    private final Path logFile;
    private final boolean enabled;

    public StartupTimer(AppPaths paths, @Value("${epicit.startup-timing:true}") boolean enabled) {
        this.logFile = paths.dataDir().resolve("startup-times.log");
        this.enabled = enabled;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        long millis = System.currentTimeMillis() - jvmStart;
        log.info("Ready {} ms after JVM start", millis);
        if (enabled) {
            record(logFile, line(ZonedDateTime.now(), millis));
        }
    }

    static String line(ZonedDateTime now, long millis) {
        return now.toOffsetDateTime() + "  ready " + millis + " ms after JVM start  (Java "
                + System.getProperty("java.version") + ", " + System.getProperty("os.name") + ")";
    }

    /** Appends one line and keeps only the newest {@value #KEEP_LINES}. Never throws: timing must not stop the app. */
    static void record(Path file, String line) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            List<String> lines = Files.isRegularFile(file)
                    ? new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                    : new java.util.ArrayList<>();
            lines.add(line);
            if (lines.size() > KEEP_LINES) {
                lines = new java.util.ArrayList<>(lines.subList(lines.size() - KEEP_LINES, lines.size()));
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not write {}", file, e);
        }
    }
}
