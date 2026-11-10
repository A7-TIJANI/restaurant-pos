package com.epicit.pos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Single-row table (id = 1). Holds PIN hash and the persisted failed-PIN backoff state. */
@Entity
@Table(name = "settings")
public class Settings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;

    @Column(name = "restaurant_name", nullable = false, length = 100)
    private String restaurantName;

    /** BCrypt hash; null until the first-run wizard sets the PIN. */
    @Column(name = "owner_pin_hash", length = 100)
    private String ownerPinHash;

    @Column(name = "low_stock_default", nullable = false)
    private int lowStockDefault = 5;

    @Column(name = "sound_muted", nullable = false)
    private boolean soundMuted;

    @Column(name = "failed_pin_attempts", nullable = false)
    private int failedPinAttempts;

    @Column(name = "last_failed_pin_at")
    private Instant lastFailedPinAt;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    /** Set when the PIN was reset via the RESET-PIN file; cleared when a new PIN is set (A14). Drives the banner. */
    @Column(name = "pin_reset_at")
    private Instant pinResetAt;

    protected Settings() {
    }

    public Integer getId() { return id; }
    public String getRestaurantName() { return restaurantName; }
    public void setRestaurantName(String restaurantName) { this.restaurantName = restaurantName; }
    public String getOwnerPinHash() { return ownerPinHash; }
    public void setOwnerPinHash(String ownerPinHash) { this.ownerPinHash = ownerPinHash; }
    public int getLowStockDefault() { return lowStockDefault; }
    public void setLowStockDefault(int lowStockDefault) { this.lowStockDefault = lowStockDefault; }
    public boolean isSoundMuted() { return soundMuted; }
    public void setSoundMuted(boolean soundMuted) { this.soundMuted = soundMuted; }
    public int getFailedPinAttempts() { return failedPinAttempts; }
    public void setFailedPinAttempts(int failedPinAttempts) { this.failedPinAttempts = failedPinAttempts; }
    public Instant getLastFailedPinAt() { return lastFailedPinAt; }
    public void setLastFailedPinAt(Instant lastFailedPinAt) { this.lastFailedPinAt = lastFailedPinAt; }
    public Instant getPinLockedUntil() { return pinLockedUntil; }
    public void setPinLockedUntil(Instant pinLockedUntil) { this.pinLockedUntil = pinLockedUntil; }
    public Instant getPinResetAt() { return pinResetAt; }
    public void setPinResetAt(Instant pinResetAt) { this.pinResetAt = pinResetAt; }
}
