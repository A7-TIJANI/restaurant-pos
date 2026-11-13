package com.epicit.pos.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class OwnerSettingsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppConfig config;
    MockHttpSession owner;
    Path originalBackupPath;

    @BeforeEach
    void setUp() throws Exception {
        originalBackupPath = config.backupPath();
        owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\",\"restaurantName\":\"Nile Grill\"}")).andExpect(status().isOk());
    }


    @Test
    void settingsAreOwnerOnly() throws Exception {
        mvc.perform(get("/api/owner/settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/owner/settings").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void firstRunSetupStoresTheRestaurantNameAndItShowsOnThePage() throws Exception {
        mvc.perform(get("/api/owner/settings").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Nile Grill"));
        mvc.perform(get("/")).andExpect(content().string(containsString("Nile Grill")));
    }

    @Test
    void aStrangerCannotRenameTheRestaurantThroughSetupOnceAPinExists() throws Exception {
        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"9999\",\"restaurantName\":\"Hijacked\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/owner/settings").session(owner)).andExpect(jsonPath("$.restaurantName").value("Nile Grill"));
    }

    @Test
    void updatingSettingsPersistsAndIsAudited() throws Exception {
        mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantName\":\"Khartoum Kofta\",\"lowStockDefault\":8,\"soundMuted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Khartoum Kofta"))
                .andExpect(jsonPath("$.lowStockDefault").value(8))
                .andExpect(jsonPath("$.soundMuted").value(true));

        mvc.perform(get("/")).andExpect(content().string(containsString("data-muted=\"true\"")));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SETTINGS_CHANGE'", Long.class)).isEqualTo(1);
    }

    @Test
    void unchangedSettingsWriteNoAuditRow() throws Exception {
        mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"restaurantName\":\"Nile Grill\"}")).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SETTINGS_CHANGE'", Long.class)).isZero();
    }

    @Test
    void invalidValuesAreRefusedWithAMessage() throws Exception {
        mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"restaurantName\":\"   \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_NAME"));
        mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON).content("{\"lowStockDefault\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_LOW_STOCK"));
    }

    @Test
    void backupFolderIsCreatedAndSavedToConfigPropertiesNotTheDatabase() throws Exception {
        Path target = Files.createTempDirectory("epicit-settings").resolve("my-backups");
        try {
            mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"backupPath\":\"" + target.toString().replace("\\", "\\\\") + "\"}"))
                    .andExpect(status().isOk());
            org.assertj.core.api.Assertions.assertThat(Files.isDirectory(target)).isTrue();
            org.assertj.core.api.Assertions.assertThat(config.backupPath()).isEqualTo(target.toAbsolutePath());
        } finally {
            config.setBackupPath(originalBackupPath);
        }
    }

    @Test
    void anUnusableBackupFolderIsRefused() throws Exception {
        Path file = Files.createTempFile("epicit-not-a-folder", ".txt");
        mvc.perform(put("/api/owner/settings").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"backupPath\":\"" + file.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_BACKUP_FOLDER"));
    }
}
