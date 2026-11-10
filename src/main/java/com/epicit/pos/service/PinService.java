package com.epicit.pos.service;

import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.SettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner PIN: BCrypt-hashed, backoff enforced here on the server with the state persisted in the database
 * (never client-side), and file-triggered recovery (A14). The PIN and its hash never reach logs or the audit log.
 *
 * <p>Backoff: the first {@value #FREE_ATTEMPTS} wrong attempts are free; every further wrong attempt locks the
 * PIN for 30 s, doubling each time (30 s, 1 min, 2 min ... capped at 15 min). While locked the PIN is not even
 * checked, so a brute-force loop gets nothing. A correct PIN resets the counter.
 */
@Service
@Transactional
public class PinService {

    static final int FREE_ATTEMPTS = 5;
    static final Duration BASE_LOCK = Duration.ofSeconds(30);
    static final Duration MAX_LOCK = Duration.ofMinutes(15);
    private static final Pattern PIN_FORMAT = Pattern.compile("\\d{4,6}");

    /** Outcome of a PIN check. A wrong PIN is a normal outcome (not an exception) so the failure count commits. */
    public record Result(Outcome outcome, int attemptsLeft, Instant lockedUntil) {
        public enum Outcome { OK, WRONG, LOCKED, NO_PIN }
    }

    public record State(boolean pinSet, Instant resetAt, Instant lockedUntil) {
    }

    private final SettingsRepository settings;
    private final AuditService audit;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public PinService(SettingsRepository settings, AuditService audit, Clock clock) {
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public State state() {
        Settings s = row();
        Instant lock = s.getPinLockedUntil();
        boolean locked = lock != null && clock.instant().isBefore(lock);
        return new State(s.getOwnerPinHash() != null, s.getPinResetAt(), locked ? lock : null);
    }

    /** First-run / post-reset PIN setup. Only allowed while no PIN is set (the old PIN is never required here). */
    public void setInitialPin(String pin) {
        requireFormat(pin);
        Settings s = row();
        if (s.getOwnerPinHash() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "PIN_ALREADY_SET",
                    "A PIN is already set. Unlock with it, or change it from the owner area.");
        }
        boolean afterReset = s.getPinResetAt() != null;
        store(s, pin);
        s.setPinResetAt(null);
        audit.record("PIN_CHANGE", "owner_pin", null, null, afterReset ? "New PIN set after reset" : "PIN set on first run");
    }

    /** FR-8.2: change needs the current PIN, and clears the lockout state. */
    public void changePin(String currentPin, String newPin) {
        requireFormat(newPin);
        Settings s = row();
        if (s.getOwnerPinHash() == null || !encoder.matches(String.valueOf(currentPin), s.getOwnerPinHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_PIN", "The current PIN is not correct.");
        }
        store(s, newPin);
        audit.record("PIN_CHANGE", "owner_pin", null, null, "PIN changed by owner");
    }

    public Result verify(String pin) {
        Settings s = row();
        if (s.getOwnerPinHash() == null) {
            return new Result(Result.Outcome.NO_PIN, 0, null);
        }
        Instant now = clock.instant();
        if (s.getPinLockedUntil() != null && now.isBefore(s.getPinLockedUntil())) {
            return new Result(Result.Outcome.LOCKED, 0, s.getPinLockedUntil());
        }
        if (pin != null && encoder.matches(pin, s.getOwnerPinHash())) {
            s.setFailedPinAttempts(0);
            s.setLastFailedPinAt(null);
            s.setPinLockedUntil(null);
            settings.save(s);
            return new Result(Result.Outcome.OK, FREE_ATTEMPTS, null);
        }
        int failures = s.getFailedPinAttempts() + 1;
        s.setFailedPinAttempts(failures);
        s.setLastFailedPinAt(now);
        Result result;
        if (failures >= FREE_ATTEMPTS) {
            Instant until = now.plus(lockFor(failures));
            s.setPinLockedUntil(until);
            result = new Result(Result.Outcome.LOCKED, 0, until);
        } else {
            result = new Result(Result.Outcome.WRONG, FREE_ATTEMPTS - failures, null);
        }
        settings.save(s);
        return result;
    }

    /**
     * A14: the RESET-PIN file was found at startup. Removes the PIN (old PIN not required), clears the lockout,
     * records when it happened for the banner, and audits it. Physical access to the laptop is the control.
     */
    public void resetViaFile() {
        Settings s = row();
        s.setOwnerPinHash(null);
        s.setFailedPinAttempts(0);
        s.setLastFailedPinAt(null);
        s.setPinLockedUntil(null);
        s.setPinResetAt(clock.instant());
        settings.save(s);
        audit.record("PIN_RESET", "owner_pin", null, null, "RESET-PIN file found at startup");
    }

    static Duration lockFor(int failures) {
        int doublings = Math.min(failures - FREE_ATTEMPTS, 10);
        Duration lock = BASE_LOCK.multipliedBy(1L << doublings);
        return lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock;
    }

    private void store(Settings s, String pin) {
        s.setOwnerPinHash(encoder.encode(pin));
        s.setFailedPinAttempts(0);
        s.setLastFailedPinAt(null);
        s.setPinLockedUntil(null);
        settings.save(s);
    }

    private Settings row() {
        return settings.findById(Settings.SINGLETON_ID).orElseThrow();
    }

    private static void requireFormat(String pin) {
        if (pin == null || !PIN_FORMAT.matcher(pin).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PIN_FORMAT", "The PIN must be 4 to 6 digits.");
        }
    }
}
