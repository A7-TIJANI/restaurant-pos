package com.epicit.pos.web;

import com.epicit.pos.service.TodayOrdersService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-3.1: today's orders, open to cashiers (no lines, no owner-only detail). */
@RestController
public class TodayOrdersController {

    private final TodayOrdersService service;

    public TodayOrdersController(TodayOrdersService service) {
        this.service = service;
    }

    @GetMapping("/api/orders/today")
    public List<TodayOrdersService.Row> today() {
        return service.list();
    }
}
