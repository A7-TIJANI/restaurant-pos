package com.epicit.pos.backup;

import com.epicit.pos.config.AppConfig;
import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.SettingsRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FR-7.1 / FR-7.2 backups. Uses H2's online {@code BACKUP TO}, which is transactionally consistent while the app
 * is running, then repackages the result as {@code epicit-pos-backup-YYYY-MM-DD.zip} containing
 * {@code epicit-pos.mv.db} + {@code manifest.json}. The zip is written under a temporary name and renamed, so a
 * crash never leaves a half-written file that looks like a backup. Only the newest {@value #KEEP} are kept.
 */
@Service
public class BackupService {

    public static final int KEEP = 30;
    public static final String DB_ENTRY = "epicit-pos.mv.db";
    public static final String MANIFEST_ENTRY = "manifest.json";
    static final Pattern NAME = Pattern.compile("epicit-pos-backup-(\\d{4}-\\d{2}-\\d{2})\\.zip");

    public record BackupInfo(String name, long sizeBytes, Instant modified) {
    }

    private final JdbcTemplate jdbc;
    private final AppConfig config;
    private final Clock clock;
    private final Supplier<String> restaurantName;
    private final String version;

    @Autowired
    public BackupService(JdbcTemplate jdbc, AppConfig config, Clock clock, SettingsRepository settings,
                         @Value("${epicit.version:2.0.0}") String version) {
        this(jdbc, config, clock,
                () -> settings.findById(Settings.SINGLETON_ID).map(Settings::getRestaurantName).orElse(""), version);
    }

    BackupService(JdbcTemplate jdbc, AppConfig config, Clock clock, Supplier<String> restaurantName, String version) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.restaurantName = restaurantName;
        this.version = version;
    }

    public Path folder() {
        return config.backupPath();
    }

    /** Writes today's backup (replacing an earlier one from the same day) and rotates. Returns the file written. */
    public synchronized Path backupNow() {
        Path dir = folder();
        try {
            Files.createDirectories(dir);
            cleanLeftovers(dir);
            String stamp = Long.toString(clock.millis());
            Path h2Zip = dir.resolve(".h2-" + stamp + ".tmp");
            Path pending = dir.resolve("epicit-pos-backup-" + LocalDate.now(clock) + ".zip.tmp");
            Path target = dir.resolve("epicit-pos-backup-" + LocalDate.now(clock) + ".zip");
            try {
                jdbc.execute("BACKUP TO '" + h2Zip.toAbsolutePath().toString().replace("'", "''") + "'");
                repackage(h2Zip, pending);
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(h2Zip);
                Files.deleteIfExists(pending);
            }
            rotate(dir, KEEP);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Backup failed: " + e.getMessage(), e);
        }
    }

    /** Newest first. */
    public List<BackupInfo> list() {
        Path dir = folder();
        List<BackupInfo> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).forEach(p -> {
                try {
                    out.add(new BackupInfo(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toInstant()));
                } catch (IOException ignored) {
                    // file vanished between list and stat: skip it
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(BackupInfo::name).reversed());
        return out;
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private void repackage(Path h2Zip, Path out) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(h2Zip));
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            boolean found = false;
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (!e.isDirectory() && e.getName().endsWith(".mv.db")) {
                    zip.putNextEntry(new ZipEntry(DB_ENTRY));
                    in.transferTo(zip);
                    zip.closeEntry();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("H2 backup did not contain a database file");
            }
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifest().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private String manifest() {
        return "{\n  \"version\": \"" + json(version) + "\",\n  \"created_at\": \"" + clock.instant()
                + "\",\n  \"restaurant_name\": \"" + json(restaurantName.get()) + "\"\n}\n";
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return b.toString();
    }

    /** Deletes the oldest backups beyond {@code keep}; names sort chronologically because the date is ISO. */
    static void rotate(Path dir, int keep) throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(dir)) {
            backups = files.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        for (int i = keep; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private static void cleanLeftovers(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Test/restore helper: extracts the database entry of a backup into {@code destDir}. */
    static Path extractDb(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path out = destDir.resolve(DB_ENTRY);
        try (InputStream raw = Files.newInputStream(zipFile); ZipInputStream in = new ZipInputStream(raw)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (DB_ENTRY.equals(e.getName())) {
                    try (OutputStream os = Files.newOutputStream(out)) {
                        in.transferTo(os);
                    }
                    return out;
                }
            }
        }
        throw new IOException("No " + DB_ENTRY + " in " + zipFile);
    }
}
