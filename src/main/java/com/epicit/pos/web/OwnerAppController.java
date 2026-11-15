package com.epicit.pos.web;

import com.epicit.pos.startup.AppShutdown;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only (the /api/owner/** interceptor): stop the app, e.g. to finish a restore or at the end of the day. */
@RestController
@RequestMapping("/api/owner/app")
public class OwnerAppController {

    private final AppShutdown shutdown;

    public OwnerAppController(AppShutdown shutdown) {
        this.shutdown = shutdown;
    }

    /** Idempotent: asking twice still answers "closing". */
    @PostMapping("/close")
    public Map<String, Object> close() {
        shutdown.requestExit();
        return Map.of("closing", true);
    }
}
