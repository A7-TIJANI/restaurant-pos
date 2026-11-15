package com.epicit.pos.web;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.startup.AppShutdown;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The real AppShutdown is replaced by a mock: this test must never stop the test JVM. */
@IntegrationTest
class OwnerAppApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean AppShutdown shutdown;

    @Test
    void aCashierCannotCloseTheApp() throws Exception {
        mvc.perform(post("/api/owner/app/close")).andExpect(status().isUnauthorized());

        verify(shutdown, never()).requestExit();
    }

    @Test
    void theOwnerCanCloseTheApp() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\"}")).andExpect(status().isOk());

        mvc.perform(post("/api/owner/app/close").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closing").value(true));

        verify(shutdown).requestExit();
    }
}
