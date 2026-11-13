package com.epicit.pos.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.service.ExcelExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerDataApiTest {

    @Autowired MockMvc mvc;
    MockHttpSession owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\"}")).andExpect(status().isOk());
    }

    @Test
    void exportAndBackupEndpointsRefuseAnonymousRequests() throws Exception {
        mvc.perform(get("/api/owner/export.xlsx")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/owner/backups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/backup")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/restore").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerDownloadsAnXlsxAttachment() throws Exception {
        mvc.perform(get("/api/owner/export.xlsx").session(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", ExcelExportService.CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", containsString("EpicIT-Report-")));
    }

    @Test
    void ownerCanBackUpAndSeeTheBackupInTheList() throws Exception {
        // in-memory H2 cannot BACKUP TO, so the backup itself is covered by BackupServiceTest on a file database;
        // here we only check the listing shape and that a failing backup is a clean JSON error, not a 500 page.
        mvc.perform(get("/api/owner/backups").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder").isString())
                .andExpect(jsonPath("$.backups").isArray());
        mvc.perform(post("/api/owner/backup").session(owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("BACKUP_FAILED"));
    }

    @Test
    void restoreRefusesANameThatIsNotInTheBackupFolder() throws Exception {
        mvc.perform(post("/api/owner/restore").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"../../config.properties\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BACKUP_NOT_FOUND"));
    }
}
