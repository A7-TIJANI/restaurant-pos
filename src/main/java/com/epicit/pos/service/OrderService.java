package com.epicit.pos.service;

import com.epicit.pos.domain.Addon;
import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderLine;
import com.epicit.pos.domain.OrderLineAddon;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order flow: one in-progress order at a time (A12) moving DRAFT -> PRINTING_CUSTOMER -> PRINTING_KITCHEN
 * -> FINALIZED (FR-2.4). Stock changes only at finalization, inside the same transaction as the sale (FR-1.6).
 * Totals are always recomputed here from line snapshots; a client-sent total is never trusted.
 */
@Service
@Transactional
public class OrderService {

    private static final List<OrderStatus> IN_PROGRESS =
            List.of(OrderStatus.DRAFT, OrderStatus.PRINTING_CUSTOMER, OrderStatus.PRINTING_KITCHEN);

    private final OrderRepository orders;
    private final MenuItemRepository menuItems;
    private final AddonRepository addons;
    private final Clock clock;

    public OrderService(OrderRepository orders, MenuItemRepository menuItems, AddonRepository addons, Clock clock) {
        this.orders = orders;
        this.menuItems = menuItems;
        this.addons = addons;
        this.clock = clock;
    }

    // ---- the current order ------------------------------------------------------------------------------

    /** The in-progress order; a fresh empty draft is created when none exists (FR-1.7). */
    public OrderView current() {
        return OrderView.of(currentEntity());
    }

    // ---- editing (DRAFT only) ---------------------------------------------------------------------------

    /** Tap a menu card: adds a line, or increments the quantity of the existing one (FR-1.1). */
    public OrderView addItem(long menuItemId) {
        MenuItem item = menuItems.findById(menuItemId)
                .orElseThrow(() -> notFound("That menu item no longer exists."));
        if (!item.isActive()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ITEM_INACTIVE",
                    item.getName() + " is no longer on the menu.");
        }
        CustomerOrder order = editableDraft();
        OrderLine line = order.getLines().stream()
                .filter(l -> Objects.equals(l.getMenuItem().getId(), item.getId()))
                .findFirst().orElse(null);
        int newQty = line == null ? 1 : line.getQty() + 1;
        if (newQty > item.getStock()) {
            throw outOfStock(item);
        }
        if (line == null) {
            order.addLine(item, 1);
        } else {
            line.setQty(newQty);
        }
        return save(order);
    }

    /** Plus/minus on a cart line. Dropping to zero removes the line. */
    public OrderView changeQuantity(long lineId, int delta) {
        if (delta == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Quantity change cannot be zero.");
        }
        CustomerOrder order = editableDraft();
        OrderLine line = lineOf(order, lineId);
        int newQty = line.getQty() + delta;
        if (newQty <= 0) {
            order.getLines().remove(line);
        } else {
            MenuItem item = line.getMenuItem();
            if (newQty > item.getStock()) {
                throw outOfStock(item);
            }
            line.setQty(newQty);
        }
        return save(order);
    }

    public OrderView removeLine(long lineId) {
        CustomerOrder order = editableDraft();
        order.getLines().remove(lineOf(order, lineId));
        return save(order);
    }

    /** Attach an add-on to a line, or remove it if already attached (FR-1.2, FR-1.3). */
    public OrderView toggleAddon(long lineId, long addonId) {
        CustomerOrder order = editableDraft();
        OrderLine line = lineOf(order, lineId);
        Optional<OrderLineAddon> existing = line.getAddons().stream()
                .filter(a -> Objects.equals(a.getAddon().getId(), addonId))
                .findFirst();
        if (existing.isPresent()) {
            line.getAddons().remove(existing.get());
        } else {
            Addon addon = addons.findById(addonId).filter(Addon::isActive)
                    .orElseThrow(() -> notFound("That add-on is no longer available."));
            line.addAddon(addon);
        }
        return save(order);
    }

    public OrderView setPayment(PaymentMethod method) {
        CustomerOrder order = editableDraft();
        order.setPaymentMethod(method);
        return save(order);
    }

    /**
     * FR-1.9: cashier discards the unfinalized order. The draft rows are deleted (not voided, not audited:
     * nothing of financial record existed) and a fresh empty draft takes its place.
     */
    public OrderView discard() {
        CustomerOrder order = editableDraft();
        orders.delete(order);
        orders.flush();
        return OrderView.of(currentEntity());
    }

    // ---- the two-step print sequence (FR-2) -------------------------------------------------------------

    /** PRINT INVOICE pressed: DRAFT -> PRINTING_CUSTOMER. Calling it again mid-print is a harmless no-op. */
    public OrderView startPrint() {
        CustomerOrder order = currentEntity();
        if (order.getStatus() == OrderStatus.PRINTING_CUSTOMER) {
            return OrderView.of(order);
        }
        if (order.getStatus() != OrderStatus.DRAFT) {
            throw locked(order);
        }
        if (order.getLines().isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMPTY_CART", "Add at least one item before printing.");
        }
        order.setStatus(OrderStatus.PRINTING_CUSTOMER);
        return save(order);
    }

    /** First print dialog closed: PRINTING_CUSTOMER -> PRINTING_KITCHEN. */
    public OrderView customerCopyPrinted() {
        CustomerOrder order = currentEntity();
        if (order.getStatus() == OrderStatus.PRINTING_KITCHEN) {
            return OrderView.of(order);
        }
        if (order.getStatus() != OrderStatus.PRINTING_CUSTOMER) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOT_PRINTING",
                    "Order #" + order.getOrderNumber() + " is not being printed.");
        }
        order.setStatus(OrderStatus.PRINTING_KITCHEN);
        return save(order);
    }

    /** Resume flow, answer "no, it was not printed": back to DRAFT so the cashier can print again (FR-2.4). */
    public OrderView reopen() {
        CustomerOrder order = currentEntity();
        if (order.getStatus() == OrderStatus.PRINTING_CUSTOMER || order.getStatus() == OrderStatus.PRINTING_KITCHEN) {
            order.setStatus(OrderStatus.DRAFT);
        }
        return save(order);
    }

    /**
     * Finalize (FR-2.3): stock decrement + sale record, atomically. Idempotent: an already FINALIZED order
     * returns success without touching stock again. If stock ran out between adding and finalizing, nothing is
     * decremented and the order is reopened as a DRAFT so the cashier can fix it.
     */
    public FinalizeResult finalizeOrder(int orderNumber) {
        CustomerOrder order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> notFound("Order #" + orderNumber + " does not exist."));

        if (order.getStatus() == OrderStatus.FINALIZED) {
            return new FinalizeResult(FinalizeResult.Outcome.ALREADY_FINALIZED, OrderView.of(order), null);
        }
        if (order.getStatus() == OrderStatus.VOIDED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_VOIDED",
                    "Order #" + orderNumber + " was voided.");
        }
        if (order.getStatus() == OrderStatus.DRAFT) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOT_PRINTED",
                    "Order #" + orderNumber + " has not been printed yet.");
        }

        Map<Long, Integer> needed = new LinkedHashMap<>();
        Map<Long, MenuItem> itemsById = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            MenuItem item = line.getMenuItem();
            itemsById.put(item.getId(), item);
            needed.merge(item.getId(), line.getQty(), Integer::sum);
        }

        List<String> short_ = new ArrayList<>();
        needed.forEach((id, qty) -> {
            MenuItem item = itemsById.get(id);
            if (item.getStock() < qty) {
                short_.add(item.getName() + " (" + qty + " needed, " + item.getStock() + " left)");
            }
        });
        if (!short_.isEmpty()) {
            order.setStatus(OrderStatus.DRAFT);
            orders.saveAndFlush(order);
            return new FinalizeResult(FinalizeResult.Outcome.STOCK_SHORT, OrderView.of(order),
                    "Not enough stock for: " + String.join(", ", short_)
                            + ". The order was reopened so you can adjust it.");
        }

        needed.forEach((id, qty) -> {
            MenuItem item = itemsById.get(id);
            item.setStock(item.getStock() - qty);
        });
        order.setTotal(order.computeTotal());
        order.setStatus(OrderStatus.FINALIZED);
        order.setFinalizedAt(clock.instant());
        orders.saveAndFlush(order);
        return new FinalizeResult(FinalizeResult.Outcome.FINALIZED, OrderView.of(order), null);
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private CustomerOrder currentEntity() {
        return orders.findFirstByStatusInOrderByIdDesc(IN_PROGRESS)
                .orElseGet(() -> orders.saveAndFlush(new CustomerOrder(orders.maxOrderNumber() + 1)));
    }

    private CustomerOrder editableDraft() {
        CustomerOrder order = currentEntity();
        if (order.getStatus() != OrderStatus.DRAFT) {
            throw locked(order);
        }
        return order;
    }

    private OrderView save(CustomerOrder order) {
        order.setTotal(order.computeTotal());
        orders.saveAndFlush(order);
        return OrderView.of(order);
    }

    private static OrderLine lineOf(CustomerOrder order, long lineId) {
        return order.getLines().stream()
                .filter(l -> Objects.equals(l.getId(), lineId))
                .findFirst()
                .orElseThrow(() -> notFound("That item is no longer in the order."));
    }

    private static BusinessException locked(CustomerOrder order) {
        return new BusinessException(HttpStatus.CONFLICT, "ORDER_LOCKED",
                "Order #" + order.getOrderNumber() + " is being printed and cannot be changed.");
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private static BusinessException outOfStock(MenuItem item) {
        String message = item.getStock() <= 0
                ? item.getName() + " is out of stock."
                : "Only " + item.getStock() + " " + item.getName() + " left in stock.";
        return new BusinessException(HttpStatus.CONFLICT, "OUT_OF_STOCK", message);
    }
}
