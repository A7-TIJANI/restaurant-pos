package com.epicit.pos.service;

import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderLine;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only changes to finalized orders (A4, FR-3.2, FR-3.4). The caller must already hold an owner session
 * (enforced by OwnerAuthInterceptor). Only TODAY's finalized orders can be touched. Stock follows every change
 * (more units sold takes stock, fewer or a void gives it back) and every change is written to the audit log
 * in the same transaction, with a mandatory reason for edits and voids.
 */
@Service
@Transactional
public class OwnerOrderService {

    public record OwnerOrderView(OrderView order, Instant finalizedAt, String voidReason) {
    }

    public record Addition(long menuItemId, int qty) {
    }

    /** All fields optional except reason: payment change, new quantities by line id (0 removes), extra items. */
    public record EditRequest(PaymentMethod paymentMethod, Map<Long, Integer> quantities, List<Addition> additions,
                              String reason) {
    }

    private final OrderRepository orders;
    private final MenuItemRepository menuItems;
    private final AuditService audit;
    private final Clock clock;

    public OwnerOrderService(OrderRepository orders, MenuItemRepository menuItems, AuditService audit, Clock clock) {
        this.orders = orders;
        this.menuItems = menuItems;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OwnerOrderView get(int orderNumber) {
        CustomerOrder order = load(orderNumber);
        if (order.getStatus() != OrderStatus.FINALIZED && order.getStatus() != OrderStatus.VOIDED) {
            throw notFinalized(order);
        }
        return view(order);
    }

    public OwnerOrderView edit(int orderNumber, EditRequest request) {
        String reason = requireReason(request.reason());
        CustomerOrder order = editable(orderNumber);
        String before = describe(order);

        Map<Long, Integer> unitsMoreSold = new LinkedHashMap<>();   // menu item id -> extra units now sold
        Map<Long, MenuItem> itemsById = new LinkedHashMap<>();
        boolean changed = false;

        if (request.paymentMethod() != null && request.paymentMethod() != order.getPaymentMethod()) {
            order.setPaymentMethod(request.paymentMethod());
            changed = true;
        }

        Map<Long, Integer> quantities = request.quantities() == null ? Map.of() : request.quantities();
        for (Map.Entry<Long, Integer> change : quantities.entrySet()) {
            OrderLine line = order.getLines().stream()
                    .filter(l -> Objects.equals(l.getId(), change.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                            "That line is not part of order #" + orderNumber + "."));
            int newQty = change.getValue() == null ? line.getQty() : change.getValue();
            if (newQty < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Quantity cannot be negative.");
            }
            if (newQty == line.getQty()) {
                continue;
            }
            MenuItem item = line.getMenuItem();
            itemsById.put(item.getId(), item);
            unitsMoreSold.merge(item.getId(), newQty - line.getQty(), Integer::sum);
            if (newQty == 0) {
                order.getLines().remove(line);
            } else {
                line.setQty(newQty);
            }
            changed = true;
        }

        List<Addition> additions = request.additions() == null ? List.of() : request.additions();
        for (Addition addition : additions) {
            if (addition.qty() <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Quantity must be at least 1.");
            }
            MenuItem item = menuItems.findById(addition.menuItemId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                            "That menu item no longer exists."));
            if (!item.isActive()) {
                throw new BusinessException(HttpStatus.CONFLICT, "ITEM_INACTIVE",
                        item.getName() + " is no longer on the menu.");
            }
            itemsById.put(item.getId(), item);
            unitsMoreSold.merge(item.getId(), addition.qty(), Integer::sum);
            OrderLine existing = order.getLines().stream()
                    .filter(l -> Objects.equals(l.getMenuItem().getId(), item.getId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setQty(existing.getQty() + addition.qty());
            } else {
                order.addLine(item, addition.qty());
            }
            changed = true;
        }

        if (!changed) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NO_CHANGES", "Nothing was changed.");
        }
        if (order.getLines().isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMPTY_ORDER",
                    "An order needs at least one item. Void it instead.");
        }

        // Check every stock effect before applying any of them.
        unitsMoreSold.forEach((id, delta) -> {
            MenuItem item = itemsById.get(id);
            if (delta > 0 && item.getStock() < delta) {
                throw new BusinessException(HttpStatus.CONFLICT, "OUT_OF_STOCK",
                        "Only " + item.getStock() + " " + item.getName() + " left in stock.");
            }
        });
        unitsMoreSold.forEach((id, delta) -> {
            MenuItem item = itemsById.get(id);
            item.setStock(item.getStock() - delta);
        });

        order.setTotal(order.computeTotal());
        orders.saveAndFlush(order);
        audit.record("ORDER_EDIT", "order #" + orderNumber, before, describe(order), reason);
        return view(order);
    }

    /** Void: stock goes back, the order stays in the books marked VOIDED (excluded from revenue later). */
    public OwnerOrderView voidOrder(int orderNumber, String rawReason) {
        String reason = requireReason(rawReason);
        CustomerOrder order = editable(orderNumber);
        String before = describe(order);

        for (OrderLine line : order.getLines()) {
            MenuItem item = line.getMenuItem();
            item.setStock(item.getStock() + line.getQty());
        }
        order.setStatus(OrderStatus.VOIDED);
        order.setVoidedBy("OWNER");
        order.setVoidReason(reason);
        orders.saveAndFlush(order);
        audit.record("ORDER_VOID", "order #" + orderNumber, before, "VOIDED", reason);
        return view(order);
    }

    /** FR-3.4: the browser prints both copies marked ** REPRINT **; this records that it happened. */
    public OwnerOrderView reprint(int orderNumber) {
        CustomerOrder order = editable(orderNumber);
        audit.record("ORDER_REPRINT", "order #" + orderNumber, null, null, "Reprinted both copies");
        return view(order);
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private CustomerOrder load(int orderNumber) {
        return orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Order #" + orderNumber + " does not exist."));
    }

    /** Finalized, not voided, and finalized today. */
    private CustomerOrder editable(int orderNumber) {
        CustomerOrder order = load(orderNumber);
        if (order.getStatus() == OrderStatus.VOIDED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_VOIDED", "Order #" + orderNumber + " was voided.");
        }
        if (order.getStatus() != OrderStatus.FINALIZED) {
            throw notFinalized(order);
        }
        if (!DayBounds.today(clock).contains(order.getFinalizedAt())) {
            throw new BusinessException(HttpStatus.CONFLICT, "NOT_TODAY",
                    "Only today's orders can be changed. Order #" + orderNumber + " is from an earlier day.");
        }
        return order;
    }

    private static BusinessException notFinalized(CustomerOrder order) {
        return new BusinessException(HttpStatus.CONFLICT, "NOT_FINALIZED",
                "Order #" + order.getOrderNumber() + " is not finalized yet.");
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Please enter a reason.");
        }
        if (trimmed.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The reason is too long (255 characters max).");
        }
        return trimmed;
    }

    private static OwnerOrderView view(CustomerOrder order) {
        return new OwnerOrderView(OrderView.of(order), order.getFinalizedAt(), order.getVoidReason());
    }

    /** Compact, human-readable snapshot for the audit trail (old value / new value). */
    static String describe(CustomerOrder order) {
        String lines = order.getLines().stream()
                .map(l -> l.getQty() + " x " + l.getItemName() + " @" + l.getUnitPrice())
                .collect(Collectors.joining("; "));
        return "payment=" + order.getPaymentMethod() + ", total=" + order.getTotal() + ", lines=[" + lines + "]";
    }
}
