package com.epicit.pos.web;

import com.epicit.pos.service.PinService;
import com.epicit.pos.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Opening and closing the owner session (A15). Open to everyone by design: this is where the PIN is checked. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record PinRequest(String pin, String restaurantName) {
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final PinService pins;
    private final SettingsService settings;
    private final OwnerSession ownerSession;
    private final Clock clock;

    public AuthController(PinService pins, SettingsService settings, OwnerSession ownerSession, Clock clock) {
        this.pins = pins;
        this.settings = settings;
        this.ownerSession = ownerSession;
        this.clock = clock;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        PinService.State state = pins.state();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pinSet", state.pinSet());
        body.put("unlocked", ownerSession.isActive(request.getSession(false)));
        body.put("resetAt", state.resetAt());
        body.put("lockedUntil", state.lockedUntil());
        return body;
    }

    /** Forced PIN setup: first run, or after a RESET-PIN reset. Rejected once a PIN exists. */
    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestBody PinRequest request, HttpServletRequest http) {
        if (request.restaurantName() != null) {
            SettingsService.cleanName(request.restaurantName());   // reject a bad name before the PIN is stored
        }
        pins.setInitialPin(request.pin());   // refuses once a PIN exists, so a stranger cannot rename the restaurant
        if (request.restaurantName() != null) {
            settings.setRestaurantName(request.restaurantName());   // first-run wizard
        }
        ownerSession.open(http.getSession(true));
        return Map.of("unlocked", true);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody PinRequest request, HttpServletRequest http) {
        PinService.Result result = pins.verify(request.pin());
        Map<String, Object> body = new LinkedHashMap<>();
        switch (result.outcome()) {
            case OK -> {
                ownerSession.open(http.getSession(true));
                body.put("unlocked", true);
                return ResponseEntity.ok(body);
            }
            case NO_PIN -> {
                body.put("error", "NO_PIN");
                body.put("message", "No PIN is set yet. Set one first.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case LOCKED -> {
                body.put("error", "PIN_LOCKED");
                body.put("message", "Too many failed attempts — try again after " + hhmm(result.lockedUntil()));
                body.put("lockedUntil", result.lockedUntil());
                return ResponseEntity.status(HttpStatus.LOCKED).body(body);
            }
            default -> {
                body.put("error", "INVALID_PIN");
                body.put("message", "Wrong PIN. " + result.attemptsLeft() + " attempt"
                        + (result.attemptsLeft() == 1 ? "" : "s") + " left before a lockout.");
                body.put("attemptsLeft", result.attemptsLeft());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
            }
        }
    }

    @PostMapping("/lock")
    public Map<String, Object> lock(HttpServletRequest http) {
        if (http.getSession(false) != null) {
            ownerSession.lock(http.getSession(false));
        }
        return Map.of("unlocked", false);
    }

    private String hhmm(Instant instant) {
        return HH_MM.format(instant.atZone(clock.getZone()));
    }
}
