package com.epicit.pos;

import com.epicit.pos.config.AppPaths;
import com.epicit.pos.startup.BrowserLauncher;
import com.epicit.pos.startup.SingleInstanceGuard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class EpicItPosApplication {

    /** A13: a wrong laptop timezone must never shift report day/week/month boundaries. */
    public static final ZoneId APP_ZONE = ZoneId.of("Africa/Kigali");

    public static void pinTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone(APP_ZONE));
    }

    public static void main(String[] args) {
        pinTimezone();
        AppPaths paths = AppPaths.resolve();

        // Startup order (section 9): 1) single-instance check -> 2) open DB -> 3) serve UI -> 4) snapshot
        if (!Boolean.getBoolean("epicit.allow-multiple-instances")) {
            Optional<Integer> running = SingleInstanceGuard.findRunningPort(paths.portFile());
            if (running.isPresent()) {
                BrowserLauncher.open("http://127.0.0.1:" + running.get() + "/");
                return;
            }
        }

        try {
            paths.ensureDirectories();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data folder " + paths.dataDir(), e);
        }
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, paths.dataDir().toString());
        if (System.getProperty("spring.datasource.url") == null) {
            System.setProperty("spring.datasource.url", paths.jdbcUrl());
        }

        // headless(false): Spring defaults to headless, which would disable opening the browser via AWT Desktop
        new SpringApplicationBuilder(EpicItPosApplication.class).headless(false).run(args);
    }
}
