package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.AuditLog;
import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.AuditLogRepository;
import com.epicit.pos.repo.SettingsRepository;
import com.epicit.pos.service.PinService.Result.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Owner PIN: hashing, persisted backoff, change, and the file-triggered reset (A14, NFR-7). */
@IntegrationTest
class PinServiceTest {

    @Autowired PinService pins;
    @Autowired SettingsRepository settings;
    @Autowired AuditLogRepository audit;

    private Settings row() {
        return settings.findById(Settings.SINGLETON_ID).orElseThrow();
    }

    private List<AuditLog> auditRows() {
        List<AuditLog> rows = new ArrayList<>();
        audit.findAll().forEach(rows::add);
        return rows;
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ---- setup + hashing ------------------------------------------------------------------------------------

    @Test
    void freshInstallHasNoPinSoSetupIsRequired() {
        assertThat(pins.state().pinSet()).isFalse();
        assertThat(pins.verify("1234").outcome()).isEqualTo(Outcome.NO_PIN);
    }

    @Test
    void pinIsStoredAsABcryptHashNeverInPlainText() {
        pins.setInitialPin("4821");

        String stored = row().getOwnerPinHash();
        assertThat(stored).startsWith("$2").doesNotContain("4821");
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void setupIsRefusedOnceAPinExists() {
        pins.setInitialPin("4821");
        assertRejected(() -> pins.setInitialPin("9999"), "PIN_ALREADY_SET");
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.OK);   // untouched
    }

    @Test
    void pinMustBeFourToSixDigits() {
        for (String bad : new String[] {"123", "1234567", "12a4", "", " 1234", null}) {
            assertRejected(() -> pins.setInitialPin(bad), "INVALID_PIN_FORMAT");
        }
        assertThat(pins.state().pinSet()).isFalse();
    }

    // ---- backoff (persisted, server-side) -----------------------------------------------------------------

    @Test
    void fourWrongAttemptsCountDownThenTheFifthLocks() {
        pins.setInitialPin("4821");

        for (int expectedLeft = 4; expectedLeft >= 1; expectedLeft--) {
            PinService.Result r = pins.verify("0000");
            assertThat(r.outcome()).isEqualTo(Outcome.WRONG);
            assertThat(r.attemptsLeft()).isEqualTo(expectedLeft);
        }
        PinService.Result locked = pins.verify("0000");

        assertThat(locked.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(locked.lockedUntil()).isAfter(Instant.now());
        assertThat(pins.state().lockedUntil()).isEqualTo(locked.lockedUntil());
        assertThat(row().getFailedPinAttempts()).isEqualTo(5);
        assertThat(row().getLastFailedPinAt()).isNotNull();
    }

    @Test
    void whileLockedEvenTheCorrectPinIsRejected() {
        pins.setInitialPin("4821");
        for (int i = 0; i < 5; i++) {
            pins.verify("0000");
        }
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.LOCKED);
    }

    @Test
    void afterTheLockExpiresTheCorrectPinWorksAndResetsTheCounter() {
        pins.setInitialPin("4821");
        for (int i = 0; i < 5; i++) {
            pins.verify("0000");
        }
        row().setPinLockedUntil(Instant.now().minusSeconds(1));   // the lock has run out
        settings.saveAndFlush(row());

        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.OK);
        assertThat(row().getFailedPinAttempts()).isZero();
        assertThat(row().getPinLockedUntil()).isNull();
        assertThat(pins.state().lockedUntil()).isNull();
    }

    @Test
    void aWrongPinAfterAnExpiredLockLocksAgainLonger() {
        pins.setInitialPin("4821");
        for (int i = 0; i < 5; i++) {
            pins.verify("0000");
        }
        row().setPinLockedUntil(Instant.now().minusSeconds(1));   // first 30 s lock has run out
        settings.saveAndFlush(row());

        PinService.Result again = pins.verify("0000");             // 6th failure -> 60 s lock

        assertThat(again.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(Duration.between(Instant.now(), again.lockedUntil())).isGreaterThan(Duration.ofSeconds(45));
    }

    @Test
    void aCorrectPinMidwayForgetsEarlierFailures() {
        pins.setInitialPin("4821");
        pins.verify("0000");
        pins.verify("0000");
        pins.verify("0000");
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.OK);

        PinService.Result next = pins.verify("0000");
        assertThat(next.attemptsLeft()).isEqualTo(4);
    }

    @Test
    void lockDurationDoublesFromThirtySecondsAndCapsAtFifteenMinutes() {
        assertThat(PinService.lockFor(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PinService.lockFor(6)).isEqualTo(Duration.ofSeconds(60));
        assertThat(PinService.lockFor(7)).isEqualTo(Duration.ofSeconds(120));
        assertThat(PinService.lockFor(9)).isEqualTo(Duration.ofMinutes(8));
        assertThat(PinService.lockFor(10)).isEqualTo(Duration.ofMinutes(15));
        assertThat(PinService.lockFor(500)).isEqualTo(Duration.ofMinutes(15));
    }

    // ---- change (FR-8.2) --------------------------------------------------------------------------------------

    @Test
    void changeNeedsTheCurrentPinAndThenOnlyTheNewOneWorks() {
        pins.setInitialPin("4821");

        assertRejected(() -> pins.changePin("0000", "7777"), "INVALID_PIN");
        pins.changePin("4821", "7777");

        assertThat(pins.verify("7777").outcome()).isEqualTo(Outcome.OK);
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.WRONG);
    }

    @Test
    void changingThePinClearsTheLockout() {
        pins.setInitialPin("4821");
        pins.verify("0000");
        pins.verify("0000");

        pins.changePin("4821", "7777");

        assertThat(row().getFailedPinAttempts()).isZero();
        assertThat(row().getPinLockedUntil()).isNull();
    }

    // ---- reset via file (A14) --------------------------------------------------------------------------------

    @Test
    void resetRemovesThePinClearsLockoutMarksWhenAndIsAudited() {
        pins.setInitialPin("4821");
        for (int i = 0; i < 5; i++) {
            pins.verify("0000");
        }
        long auditBefore = audit.count();

        pins.resetViaFile();

        PinService.State state = pins.state();
        assertThat(state.pinSet()).isFalse();
        assertThat(state.resetAt()).isNotNull();
        assertThat(state.lockedUntil()).isNull();
        assertThat(row().getFailedPinAttempts()).isZero();
        assertThat(audit.count()).isEqualTo(auditBefore + 1);
        assertThat(auditRows()).extracting(AuditLog::getAction).contains("PIN_RESET");
    }

    @Test
    void settingANewPinAfterAResetClearsTheBannerMarker() {
        pins.setInitialPin("4821");
        pins.resetViaFile();
        assertThat(pins.state().resetAt()).isNotNull();

        pins.setInitialPin("5555");   // old PIN not required

        assertThat(pins.state().resetAt()).isNull();
        assertThat(pins.verify("5555").outcome()).isEqualTo(Outcome.OK);
        assertThat(pins.verify("4821").outcome()).isEqualTo(Outcome.WRONG);
    }

    // ---- audit never holds secrets ---------------------------------------------------------------------------

    @Test
    void pinAuditEntriesNeverContainThePinOrItsHash() {
        pins.setInitialPin("4821");
        pins.changePin("4821", "7777");
        pins.resetViaFile();

        List<AuditLog> rows = auditRows();
        assertThat(rows).extracting(AuditLog::getAction).contains("PIN_CHANGE", "PIN_RESET");
        for (AuditLog entry : rows) {
            String all = String.join("|", String.valueOf(entry.getOldValue()), String.valueOf(entry.getNewValue()),
                    String.valueOf(entry.getReason()), String.valueOf(entry.getEntity()));
            assertThat(all).doesNotContain("4821").doesNotContain("7777").doesNotContain("$2");
            assertThat(entry.getActor()).isEqualTo("OWNER");
        }
    }
}
