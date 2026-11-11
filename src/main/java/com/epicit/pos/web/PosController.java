package com.epicit.pos.web;

import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.SettingsRepository;
import com.epicit.pos.service.ReportService;
import com.epicit.pos.service.TodayCounterService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PosController {

    private final SettingsRepository settings;
    private final MenuItemRepository menuItems;
    private final AddonRepository addons;
    private final TodayCounterService counter;
    private final ReportService reports;

    public PosController(SettingsRepository settings, MenuItemRepository menuItems,
                         AddonRepository addons, TodayCounterService counter, ReportService reports) {
        this.settings = settings;
        this.menuItems = menuItems;
        this.addons = addons;
        this.counter = counter;
        this.reports = reports;
    }

    /**
     * The default screen: cashier mode, no login (spec section 5). The current order itself is loaded by the
     * page via GET /api/order, so this page never creates an order as a side effect of being rendered.
     */
    @GetMapping("/")
    public String pos(Model model) {
        Settings s = settings.findById(Settings.SINGLETON_ID).orElseThrow();
        model.addAttribute("restaurantName", s.getRestaurantName());
        model.addAttribute("menu", menuItems.findByActiveTrueOrderByIdAsc());
        model.addAttribute("addons", addons.findByActiveTrueOrderByIdAsc());
        model.addAttribute("today", counter.today());
        // Top 5 by quantity only; revenue per item stays owner-only (A16)
        model.addAttribute("top5", reports.topItemsToday());
        return "pos";
    }
}
