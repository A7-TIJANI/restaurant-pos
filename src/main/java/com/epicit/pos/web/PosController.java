package com.epicit.pos.web;

import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.repo.SettingsRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PosController {

    private final SettingsRepository settings;
    private final MenuItemRepository menuItems;
    private final AddonRepository addons;
    private final OrderRepository orders;

    public PosController(SettingsRepository settings, MenuItemRepository menuItems,
                         AddonRepository addons, OrderRepository orders) {
        this.settings = settings;
        this.menuItems = menuItems;
        this.addons = addons;
        this.orders = orders;
    }

    /** The default screen: cashier mode, no login (spec section 5). */
    @GetMapping("/")
    public String pos(Model model) {
        Settings s = settings.findById(Settings.SINGLETON_ID).orElseThrow();
        model.addAttribute("restaurantName", s.getRestaurantName());
        model.addAttribute("menu", menuItems.findByActiveTrueOrderByIdAsc());
        model.addAttribute("addons", addons.findByActiveTrueOrderByIdAsc());
        model.addAttribute("nextOrderNumber", orders.maxOrderNumber() + 1);
        return "pos";
    }
}
