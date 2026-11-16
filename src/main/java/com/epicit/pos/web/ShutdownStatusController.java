package com.epicit.pos.web;

import com.epicit.pos.startup.AppShutdown;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * After "Close POS" the screen polls this until the database file lock is released ({@code RELEASED}) or the server
 * is gone. No PIN: it only says whether the app is running, closing, or has let go of its data file.
 */
@RestController
public class ShutdownStatusController {

    private final AppShutdown shutdown;

    public ShutdownStatusController(AppShutdown shutdown) {
        this.shutdown = shutdown;
    }

    @GetMapping("/api/shutdown-status")
    public Map<String, String> status() {
        return Map.of("state", shutdown.state().name());
    }
}
