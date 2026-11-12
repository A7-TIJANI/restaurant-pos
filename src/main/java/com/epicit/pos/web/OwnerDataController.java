package com.epicit.pos.web;

import com.epicit.pos.backup.BackupService;
import com.epicit.pos.backup.BackupService.BackupInfo;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-5 / FR-7: Excel export and backups (owner-only via OwnerAuthInterceptor on /api/owner/**). */
@RestController
@RequestMapping("/api/owner")
public class OwnerDataController {

    public record BackupStatus(String folder, List<BackupInfo> backups) {
    }

    private final ExcelExportService excel;
    private final BackupService backups;

    public OwnerDataController(ExcelExportService excel, BackupService backups) {
        this.excel = excel;
        this.backups = backups;
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
        return new BackupStatus(backups.folder().toString(), backups.list());
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
}
