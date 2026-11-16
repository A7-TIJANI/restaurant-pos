package com.epicit.pos.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** The close screen polls this endpoint; it must answer without a PIN and say RUNNING while the app is up. */
@IntegrationTest
class ShutdownStatusApiTest {

    @Autowired MockMvc mvc;

    @Test
    void answersWithoutAPinAndReportsRunning() throws Exception {
        mvc.perform(get("/api/shutdown-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }
}
