package com.epicit.pos.service;

import com.epicit.pos.config.AppConfig;
import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.SettingsRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-8: restaurant name, low-stock default, sound mute (database) and the backup folder (config.properties,
 * because it must be known before the database opens). Every change is audited as SETTINGS_CHANGE.
 */
@Service
@Transactional
public class SettingsService {

    public record View(String restaurantName, int lowStockDefault, boolean soundMuted, String backupPath) {
    }

    public record Update(String restaurantName, Integer lowStockDefault, Boolean soundMuted, String backupPath) {
    }

    static final int MAX_NAME = 100;
    static final int MAX_LOW_STOCK = 1000;

    private final SettingsRepository settings;
    private final AppConfig config;
    private final AuditService audit;

    public SettingsService(SettingsRepository settings, AppConfig config, AuditService audit) {
        this.settings = settings;
        this.config = config;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public View view() {
        return toView(row());
    }

    /** First-run wizard: the name is set together with the PIN, so the owner sees their own name on the first receipt. */
    public void setRestaurantName(String name) {
        row().setRestaurantName(cleanName(name));
    }

    public View update(Update in) {
        Settings s = row();
        View before = toView(s);
        if (in.restaurantName() != null) {
            s.setRestaurantName(cleanName(in.restaurantName()));
        }
        if (in.lowStockDefault() != null) {
            if (in.lowStockDefault() < 0 || in.lowStockDefault() > MAX_LOW_STOCK) {
                throw bad("BAD_LOW_STOCK", "The low-stock level must be between 0 and " + MAX_LOW_STOCK + ".");
            }
            s.setLowStockDefault(in.lowStockDefault());
        }
        if (in.soundMuted() != null) {
            s.setSoundMuted(in.soundMuted());
        }
        if (in.backupPath() != null && !in.backupPath().isBlank()) {
            setBackupFolder(in.backupPath().trim());
        }
        View after = toView(s);
        if (!after.equals(before)) {
            audit.record("SETTINGS_CHANGE", "settings", describe(before), describe(after), "Settings changed");
        }
        return after;
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private void setBackupFolder(String raw) {
        Path dir;
        try {
            dir = Path.of(raw).toAbsolutePath();
            Files.createDirectories(dir);
        } catch (InvalidPathException | IOException e) {
            throw bad("BAD_BACKUP_FOLDER", "That folder cannot be used. Check the path.");
        }
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw bad("BAD_BACKUP_FOLDER", "That folder is not writable.");
        }
        try {
            config.setBackupPath(dir);
        } catch (UncheckedIOException e) {
            throw bad("BAD_BACKUP_FOLDER", "The folder could not be saved.");
        }
    }

    /** Throws if the name is unusable; returns it trimmed. Lets callers check before changing anything else. */
    public static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw bad("BAD_NAME", "The restaurant name must be 1 to " + MAX_NAME + " characters.");
        }
        return n;
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private View toView(Settings s) {
        return new View(s.getRestaurantName(), s.getLowStockDefault(), s.isSoundMuted(), config.backupPath().toString());
    }

    private static String describe(View v) {
        return "name=" + v.restaurantName() + "; lowStockDefault=" + v.lowStockDefault() + "; soundMuted="
                + v.soundMuted() + "; backupPath=" + v.backupPath();
    }

    private Settings row() {
        return settings.findById(Settings.SINGLETON_ID).orElseThrow();
    }
}
