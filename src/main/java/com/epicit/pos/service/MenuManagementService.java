package com.epicit.pos.service;

import com.epicit.pos.domain.Addon;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.SettingsRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-6: owner-only menu and inventory management (the owner session is enforced by the interceptor on
 * {@code /api/owner/**}). Items and add-ons are only ever deactivated, never deleted, because historical order lines
 * keep a foreign key to them. Every change writes its audit row in the same transaction (NFR-8): a price change as
 * {@code PRICE_CHANGE}, everything else as {@code MENU_CHANGE}. The audit "entity" is {@code menu_item #<id>} and not
 * the name, because audit_log.entity is only 60 characters and a name may be 100.
 */
@Service
@Transactional
public class MenuManagementService {

    public record ItemView(long id, String name, long price, int stock, int lowStockThreshold, String color,
                           boolean active, boolean lowStock, boolean outOfStock) {
    }

    public record AddonView(long id, String name, boolean active) {
    }

    public record MenuView(List<ItemView> items, List<AddonView> addons, List<String> palette, int lowStockDefault) {
    }

    /** Any field may be null on update = "leave as is". {@code stock} and {@code color} are used by add only / both. */
    public record ItemInput(String name, Long price, Integer stock, Integer lowStockThreshold, String color) {
    }

    static final int MAX_NAME = 100;
    static final long MAX_PRICE = 100_000_000L;
    static final int MAX_STOCK = 1_000_000;
    static final int MAX_THRESHOLD = 1000;

    private final MenuItemRepository items;
    private final AddonRepository addons;
    private final SettingsRepository settings;
    private final AuditService audit;

    public MenuManagementService(MenuItemRepository items, AddonRepository addons, SettingsRepository settings,
                                 AuditService audit) {
        this.items = items;
        this.addons = addons;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MenuView view() {
        int lowDefault = settings.findById(Settings.SINGLETON_ID).map(Settings::getLowStockDefault).orElse(5);
        return new MenuView(items.findAllByOrderByIdAsc().stream().map(MenuManagementService::toView).toList(),
                addons.findAllByOrderByIdAsc().stream().map(MenuManagementService::toView).toList(),
                MenuPalette.COLORS, lowDefault);
    }

    // ---- menu items ----------------------------------------------------------------------------------------------

    public ItemView addItem(ItemInput in) {
        String name = cleanName(in.name());
        long price = requirePrice(in.price());
        int stock = in.stock() == null ? 0 : requireStock(in.stock());
        int threshold = in.lowStockThreshold() == null
                ? settings.findById(Settings.SINGLETON_ID).map(Settings::getLowStockDefault).orElse(5)
                : requireThreshold(in.lowStockThreshold());
        requireFreeName(name, -1L);
        String color = in.color() == null || in.color().isBlank() ? nextColor() : requirePaletteColor(in.color());

        MenuItem item = new MenuItem(name, price, stock, color);
        item.setLowStockThreshold(threshold);
        item = items.saveAndFlush(item);
        audit.record("MENU_CHANGE", entity(item), null, describe(item), "Menu item added");
        return toView(item);
    }

    public ItemView updateItem(long id, ItemInput in) {
        MenuItem item = item(id);
        String before = describe(item);
        String beforeWithoutPrice = describeWithoutPrice(item);
        long oldPrice = item.getPrice();

        if (in.name() != null) {
            String name = cleanName(in.name());
            if (item.isActive()) {
                requireFreeName(name, id);
            }
            item.setName(name);
        }
        if (in.price() != null) {
            item.setPrice(requirePrice(in.price()));
        }
        if (in.lowStockThreshold() != null) {
            item.setLowStockThreshold(requireThreshold(in.lowStockThreshold()));
        }
        if (in.color() != null) {
            item.setColor(requirePaletteColor(in.color()));
        }
        // Stock is deliberately not editable here: use restock(), which is atomic against a sale in progress.

        items.saveAndFlush(item);
        if (item.getPrice() != oldPrice) {
            audit.record("PRICE_CHANGE", entity(item), oldPrice + " RWF", item.getPrice() + " RWF", "Price changed: " + item.getName());
        }
        // price has its own audit row above; MENU_CHANGE only when something else changed
        String after = describe(item);
        if (!describeWithoutPrice(item).equals(beforeWithoutPrice)) {
            audit.record("MENU_CHANGE", entity(item), before, after, "Menu item edited");
        }
        return toView(item);
    }

    /** FR-6.3: add stock (positive) or remove it (negative: spoilage, miscount). Never below zero. */
    public ItemView restock(long id, Integer delta) {
        if (delta == null || delta == 0 || Math.abs((long) delta) > MAX_STOCK) {
            throw bad("BAD_QUANTITY", "Enter a whole number other than 0, up to " + MAX_STOCK + " (use a minus sign to remove stock).");
        }
        MenuItem current = item(id);
        int before = current.getStock();
        if (items.adjustStock(id, delta) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_BELOW_ZERO",
                    "Only " + before + " " + current.getName() + " in stock, so you cannot remove " + Math.abs(delta) + ".");
        }
        MenuItem after = item(id);   // the bulk update bypassed the persistence context: read the stored value
        audit.record("MENU_CHANGE", entity(after), "stock=" + (after.getStock() - delta), "stock=" + after.getStock(),
                (delta > 0 ? "Restock +" : "Stock adjusted ") + delta + ": " + after.getName());
        return toView(after);
    }

    /** FR-6.1 soft delete: the item leaves the POS grid but stays referenced by past orders. Reversible. */
    public ItemView setItemActive(long id, Boolean active) {
        if (active == null) {
            throw bad("BAD_REQUEST", "Say whether the item should be active.");
        }
        MenuItem item = item(id);
        if (item.isActive() == active) {
            return toView(item);
        }
        if (active) {
            requireFreeName(item.getName(), id);   // an active item with the same name was added meanwhile
        }
        item.setActive(active);
        items.saveAndFlush(item);
        audit.record("MENU_CHANGE", entity(item), "active=" + !active, "active=" + active,
                (active ? "Menu item reactivated: " : "Menu item deactivated: ") + item.getName());
        return toView(item);
    }

    // ---- add-ons -------------------------------------------------------------------------------------------------

    public AddonView addAddon(String rawName) {
        String name = cleanName(rawName);
        if (addons.existsByActiveTrueAndNameIgnoreCaseAndIdNot(name, -1L)) {
            throw duplicate(name);
        }
        Addon addon = addons.saveAndFlush(new Addon(name));
        audit.record("MENU_CHANGE", "addon #" + addon.getId(), null, "name=" + name + "; active=true", "Add-on added");
        return toView(addon);
    }

    public AddonView setAddonActive(long id, Boolean active) {
        if (active == null) {
            throw bad("BAD_REQUEST", "Say whether the add-on should be active.");
        }
        Addon addon = addons.findById(id).orElseThrow(() -> notFound("add-on"));
        if (addon.isActive() == active) {
            return toView(addon);
        }
        if (active && addons.existsByActiveTrueAndNameIgnoreCaseAndIdNot(addon.getName(), id)) {
            throw duplicate(addon.getName());
        }
        addon.setActive(active);
        addons.saveAndFlush(addon);
        audit.record("MENU_CHANGE", "addon #" + addon.getId(), "active=" + !active, "active=" + active,
                (active ? "Add-on reactivated: " : "Add-on deactivated: ") + addon.getName());
        return toView(addon);
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private String nextColor() {
        return MenuPalette.next(items.findByActiveTrueOrderByIdAsc().stream().map(MenuItem::getColor).toList());
    }

    private MenuItem item(long id) {
        return items.findById(id).orElseThrow(() -> notFound("menu item"));
    }

    private void requireFreeName(String name, long exceptId) {
        if (items.existsByActiveTrueAndNameIgnoreCaseAndIdNot(name, exceptId)) {
            throw duplicate(name);
        }
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw bad("BAD_NAME", "The name must be 1 to " + MAX_NAME + " characters.");
        }
        return n;
    }

    private static long requirePrice(Long price) {
        if (price == null || price <= 0 || price > MAX_PRICE) {
            throw bad("BAD_PRICE", "The price must be a whole number of RWF, above 0 and at most " + MAX_PRICE + ".");
        }
        return price;
    }

    private static int requireStock(Integer stock) {
        if (stock < 0 || stock > MAX_STOCK) {
            throw bad("BAD_STOCK", "The stock must be between 0 and " + MAX_STOCK + ".");
        }
        return stock;
    }

    private static int requireThreshold(Integer threshold) {
        if (threshold < 0 || threshold > MAX_THRESHOLD) {
            throw bad("BAD_THRESHOLD", "The low-stock level must be between 0 and " + MAX_THRESHOLD + ".");
        }
        return threshold;
    }

    private static String requirePaletteColor(String color) {
        String canonical = MenuPalette.canonical(color);
        if (canonical == null) {
            throw bad("BAD_COLOR", "Pick one of the colours offered.");
        }
        return canonical;
    }

    private static String entity(MenuItem item) {
        return "menu_item #" + item.getId();
    }

    private static String describe(MenuItem i) {
        return "name=" + i.getName() + "; price=" + i.getPrice() + "; " + rest(i);
    }

    private static String describeWithoutPrice(MenuItem i) {
        return "name=" + i.getName() + "; " + rest(i);
    }

    private static String rest(MenuItem i) {
        return "lowStockThreshold=" + i.getLowStockThreshold() + "; color=" + i.getColor() + "; active=" + i.isActive();
    }

    private static ItemView toView(MenuItem i) {
        return new ItemView(i.getId(), i.getName(), i.getPrice(), i.getStock(), i.getLowStockThreshold(), i.getColor(),
                i.isActive(), i.isLowStock(), i.isOutOfStock());
    }

    private static AddonView toView(Addon a) {
        return new AddonView(a.getId(), a.getName(), a.isActive());
    }

    private static BusinessException bad(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static BusinessException notFound(String what) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "That " + what + " no longer exists.");
    }

    private static BusinessException duplicate(String name) {
        return new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_NAME", "\"" + name + "\" is already on the active list.");
    }
}
