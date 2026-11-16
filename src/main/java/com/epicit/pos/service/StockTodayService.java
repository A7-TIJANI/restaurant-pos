package com.epicit.pos.service;

import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.repo.ItemTotals;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cashier-visible "Stock &amp; sales today" panel (FR-1.11, A18): for every active menu item, how many were sold
 * today (finalized orders only, so voids drop out) and how many are left. Quantities only, never revenue.
 */
@Service
@Transactional(readOnly = true)
public class StockTodayService {

    private final MenuItemRepository menuItems;
    private final OrderRepository orders;
    private final Clock clock;

    public StockTodayService(MenuItemRepository menuItems, OrderRepository orders, Clock clock) {
        this.menuItems = menuItems;
        this.orders = orders;
        this.clock = clock;
    }

    /** Out-of-stock first, then low stock, then A-Z. */
    public List<StockRow> today() {
        DayBounds day = DayBounds.today(clock);
        Map<String, Long> soldByName = new HashMap<>();
        for (ItemTotals t : orders.itemTotalsBetween(OrderStatus.FINALIZED, day.start(), day.end())) {
            soldByName.put(t.name(), t.qty());   // revenue is read by the query but dropped right here
        }
        return menuItems.findByActiveTrueOrderByIdAsc().stream()
                .map(item -> new StockRow(item.getId(), item.getName(), item.getStock(),
                        soldByName.getOrDefault(item.getName(), 0L), levelOf(item)))
                .sorted(Comparator.comparing((StockRow r) -> r.level().ordinal())
                        .thenComparing(StockRow::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static StockRow.Level levelOf(MenuItem item) {
        if (item.isOutOfStock()) { return StockRow.Level.OUT; }
        return item.isLowStock() ? StockRow.Level.LOW : StockRow.Level.OK;
    }
}
