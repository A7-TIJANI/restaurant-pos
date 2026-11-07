package com.epicit.pos.web;

import com.epicit.pos.startup.SingleInstanceGuard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe used by the single-instance guard (A17). */
@RestController
public class PingController {

    @GetMapping(value = "/ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ping() {
        return SingleInstanceGuard.PING_RESPONSE;
    }
}
