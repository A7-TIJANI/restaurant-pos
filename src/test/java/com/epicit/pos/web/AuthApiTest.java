package com.epicit.pos.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The PIN gate over HTTP: who gets into /api/owner/**, lockout, lock, and that cashiers never do. */
@IntegrationTest
class AuthApiTest {

    @Autowired MockMvc mvc;

    private ResultActions json(String url, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(url).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void setPin(MockHttpSession session, String pin) throws Exception {
        json("/api/auth/setup", session, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk());
    }

    @Test
    void freshInstallReportsNoPinAndLocked() throws Exception {
        mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinSet").value(false))
                .andExpect(jsonPath("$.unlocked").value(false))
                .andExpect(jsonPath("$.resetAt").doesNotExist());
    }

    @Test
    void ownerEndpointsRefuseAnonymousRequestsOnTheServer() throws Exception {
        mvc.perform(get("/api/owner/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("OWNER_REQUIRED"));
        mvc.perform(post("/api/owner/orders/1/void").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/orders/1/reprint"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/pin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPin\":\"1\",\"newPin\":\"2222\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cashierEndpointsNeedNoPin() throws Exception {
        mvc.perform(get("/api/order")).andExpect(status().isOk());
        mvc.perform(get("/api/orders/today")).andExpect(status().isOk());
        mvc.perform(get("/api/today")).andExpect(status().isOk());
    }

    @Test
    void setupSetsThePinAndOpensTheSession() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        setPin(owner, "4821");

        mvc.perform(get("/api/auth/status").session(owner))
                .andExpect(jsonPath("$.pinSet").value(true))
                .andExpect(jsonPath("$.unlocked").value(true));
        // gate passed: a missing order is a 404, not a 401
        mvc.perform(get("/api/owner/orders/999").session(owner)).andExpect(status().isNotFound());
    }

    @Test
    void setupIsRefusedOnceAPinExists() throws Exception {
        setPin(new MockHttpSession(), "4821");
        json("/api/auth/setup", new MockHttpSession(), "{\"pin\":\"9999\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PIN_ALREADY_SET"));
    }

    @Test
    void malformedPinIsRejectedAtSetup() throws Exception {
        json("/api/auth/setup", new MockHttpSession(), "{\"pin\":\"12\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PIN_FORMAT"));
    }

    @Test
    void loginBeforeAnyPinIsSetAsksForSetup() throws Exception {
        json("/api/auth/login", new MockHttpSession(), "{\"pin\":\"1234\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NO_PIN"));
    }

    @Test
    void wrongPinTellsHowManyAttemptsRemain() throws Exception {
        setPin(new MockHttpSession(), "4821");
        MockHttpSession cashier = new MockHttpSession();

        json("/api/auth/login", cashier, "{\"pin\":\"0000\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_PIN"))
                .andExpect(jsonPath("$.attemptsLeft").value(4))
                .andExpect(jsonPath("$.message").value(containsString("4 attempts left")));
        mvc.perform(get("/api/owner/orders/999").session(cashier)).andExpect(status().isUnauthorized());
    }

    @Test
    void fiveWrongPinsLockOutEvenTheCorrectPinWithAClockTimeMessage() throws Exception {
        setPin(new MockHttpSession(), "4821");
        MockHttpSession attacker = new MockHttpSession();
        for (int i = 0; i < 4; i++) {
            json("/api/auth/login", attacker, "{\"pin\":\"0000\"}").andExpect(status().isUnauthorized());
        }

        json("/api/auth/login", attacker, "{\"pin\":\"0000\"}")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("PIN_LOCKED"))
                .andExpect(jsonPath("$.message").value(containsString("Too many failed attempts — try again after ")))
                .andExpect(jsonPath("$.lockedUntil").exists());

        json("/api/auth/login", attacker, "{\"pin\":\"4821\"}").andExpect(status().isLocked());
        mvc.perform(get("/api/auth/status"))
                .andExpect(jsonPath("$.lockedUntil").exists());
    }

    @Test
    void correctLoginOpensASessionThatOnlyThatBrowserHolds() throws Exception {
        setPin(new MockHttpSession(), "4821");
        MockHttpSession owner = new MockHttpSession();
        MockHttpSession otherBrowser = new MockHttpSession();

        json("/api/auth/login", owner, "{\"pin\":\"4821\"}").andExpect(status().isOk());

        mvc.perform(get("/api/owner/orders/999").session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/owner/orders/999").session(otherBrowser)).andExpect(status().isUnauthorized());
    }

    @Test
    void lockEndsTheOwnerSession() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        setPin(owner, "4821");

        json("/api/auth/lock", owner, "{}").andExpect(status().isOk());

        mvc.perform(get("/api/owner/orders/999").session(owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/status").session(owner)).andExpect(jsonPath("$.unlocked").value(false));
    }

    @Test
    void changePinNeedsTheSessionAndTheCurrentPin() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        setPin(owner, "4821");

        json("/api/owner/pin", owner, "{\"currentPin\":\"0000\",\"newPin\":\"7777\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_PIN"));
        json("/api/owner/pin", owner, "{\"currentPin\":\"4821\",\"newPin\":\"7777\"}")
                .andExpect(status().isOk());

        json("/api/auth/login", new MockHttpSession(), "{\"pin\":\"7777\"}").andExpect(status().isOk());
        json("/api/auth/login", new MockHttpSession(), "{\"pin\":\"4821\"}").andExpect(status().isUnauthorized());
    }
}
