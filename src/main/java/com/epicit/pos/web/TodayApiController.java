package com.epicit.pos.web;

import com.epicit.pos.service.TodayCounterService;
import com.epicit.pos.service.TodayStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Today's counter strip data (A16), visible to cashiers. No item breakdown: that stays owner-only. */
@RestController
public class TodayApiController {

    private final TodayCounterService counter;

    public TodayApiController(TodayCounterService counter) {
        this.counter = counter;
    }

    @GetMapping("/api/today")
    public TodayStats today() {
        return counter.today();
    }
}
