package com.epicit.pos.web;

import com.epicit.pos.service.SettingsService;
import com.epicit.pos.service.SettingsService.Update;
import com.epicit.pos.service.SettingsService.View;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-8 (owner-only via the /api/owner/** interceptor). The PIN has its own endpoint (/api/owner/pin). */
@RestController
@RequestMapping("/api/owner/settings")
public class OwnerSettingsController {

    private final SettingsService settings;

    public OwnerSettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public View get() {
        return settings.view();
    }

    @PutMapping
    public View put(@RequestBody Update update) {
        return settings.update(update);
    }
}
