package com.epicit.pos.web;

import com.epicit.pos.service.PinService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-8.2: change the PIN from inside an owner session (the current PIN is required again). */
@RestController
@RequestMapping("/api/owner/pin")
public class OwnerPinController {

    public record ChangePinRequest(String currentPin, String newPin) {
    }

    private final PinService pins;

    public OwnerPinController(PinService pins) {
        this.pins = pins;
    }

    @PostMapping
    public Map<String, Object> change(@RequestBody ChangePinRequest request) {
        pins.changePin(request.currentPin(), request.newPin());
        return Map.of("changed", true);
    }
}
