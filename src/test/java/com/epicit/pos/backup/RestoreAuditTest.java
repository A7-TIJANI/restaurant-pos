package com.epicit.pos.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RestoreAuditTest {

    @Autowired RestoreService restore;
    @Autowired AppPaths paths;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aRestoreMarkerBecomesOneRestoreAuditRowAndIsConsumed() throws IOException {
        Files.createDirectories(paths.dataDir());
        Files.writeString(paths.restoreMarker(), "backup=epicit-pos-backup-2026-11-10.zip\nprevious_database=epicit-pos.mv.db.before-restore-x\n");

        restore.auditCompletedRestore();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'RESTORE' AND new_value = 'epicit-pos-backup-2026-11-10.zip'", Long.class)).isEqualTo(1);
        assertThat(paths.restoreMarker()).doesNotExist();
        restore.auditCompletedRestore();   // second call: nothing left to record
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'RESTORE'", Long.class)).isEqualTo(1);
    }
}
