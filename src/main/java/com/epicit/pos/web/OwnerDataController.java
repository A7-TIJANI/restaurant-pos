package com.epicit.pos.web;

import com.epicit.pos.backup.BackupService;
import com.epicit.pos.backup.BackupService.BackupInfo;
import com.epicit.pos.backup.RestoreService;
import com.epicit.pos.service.ExcelExportService;
import com.epicit.pos.service.ExcelExportService.Export;
import com.epicit.pos.service.BusinessException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-5 / FR-7: Excel export and backups (owner-only via OwnerAuthInterceptor on /api/owner/**). */
@RestController
@RequestMapping("/api/owner")
public class OwnerDataController {

    public record RestoreRequest(String name) {
    }

    public record BackupStatus(String folder, List<BackupInfo> backups, boolean restorePending) {
    }

    private final ExcelExportService excel;
    private final BackupService backups;
    private final RestoreService restore;

    public OwnerDataController(ExcelExportService excel, BackupService backups, RestoreService restore) {
        this.excel = excel;
        this.backups = backups;
        this.restore = restore;
    }

    /** The download is the deliverable; the copy in the backup folder (FR-5.3) is best effort. */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> export() {
        Export e = excel.export();
        try {
            Path dir = backups.folder();
            Files.createDirectories(dir);
            Files.write(dir.resolve(e.fileName()), e.bytes());
        } catch (IOException | RuntimeException ignored) {
            // folder missing or read-only: the download still works
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ExcelExportService.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(e.fileName()).build().toString())
                .body(e.bytes());
    }

    @GetMapping("/backups")
    public BackupStatus status() {
        return new BackupStatus(backups.folder().toString(), backups.list(), restore.restorePending());
    }

    @PostMapping("/backup")
    public Map<String, Object> backupNow() {
        Path file;
        try {
            file = backups.backupNow();
        } catch (UncheckedIOException | org.springframework.dao.DataAccessException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_FAILED",
                    "Backup failed. Check that the backup folder exists and has free space.");
        }
        return Map.of("file", file.getFileName().toString(), "folder", backups.folder().toString());
    }

    /** Validates the chosen backup and stages it; the swap happens at the next start (FR-7.3). */
    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody RestoreRequest request) {
        RestoreService.Staged staged = restore.stage(request == null ? "" : String.valueOf(request.name()));
        return Map.of("backup", staged.backup(), "restaurantName", staged.restaurantName(),
                "orders", staged.orders(), "restartRequired", true);
    }

    @PostMapping("/restore/cancel")
    public Map<String, Object> cancelRestore() {
        restore.cancel();
        return Map.of("cancelled", true);
    }
}
