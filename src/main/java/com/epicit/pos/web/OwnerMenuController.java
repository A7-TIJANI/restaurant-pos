package com.epicit.pos.web;

import com.epicit.pos.service.MenuManagementService;
import com.epicit.pos.service.MenuManagementService.AddonView;
import com.epicit.pos.service.MenuManagementService.ItemInput;
import com.epicit.pos.service.MenuManagementService.ItemView;
import com.epicit.pos.service.MenuManagementService.MenuView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-6 (owner-only via the /api/owner/** interceptor). Nothing here deletes: items and add-ons are deactivated. */
@RestController
@RequestMapping("/api/owner/menu")
public class OwnerMenuController {

    public record RestockRequest(Integer delta, Integer setTo) {
    }

    public record ActiveRequest(Boolean active) {
    }

    public record AddonRequest(String name) {
    }

    private final MenuManagementService menu;

    public OwnerMenuController(MenuManagementService menu) {
        this.menu = menu;
    }

    @GetMapping
    public MenuView get() {
        return menu.view();
    }

    @PostMapping("/items")
    public ItemView addItem(@RequestBody ItemInput in) {
        return menu.addItem(in);
    }

    @PutMapping("/items/{id}")
    public ItemView updateItem(@PathVariable long id, @RequestBody ItemInput in) {
        return menu.updateItem(id, in);
    }

    @PostMapping("/items/{id}/restock")
    public ItemView restock(@PathVariable long id, @RequestBody RestockRequest in) {
        return menu.restock(id, in.delta(), in.setTo());
    }

    @PostMapping("/items/{id}/active")
    public ItemView itemActive(@PathVariable long id, @RequestBody ActiveRequest in) {
        return menu.setItemActive(id, in.active());
    }

    @PostMapping("/addons")
    public AddonView addAddon(@RequestBody AddonRequest in) {
        return menu.addAddon(in.name());
    }

    @PostMapping("/addons/{id}/active")
    public AddonView addonActive(@PathVariable long id, @RequestBody ActiveRequest in) {
        return menu.setAddonActive(id, in.active());
    }
}
