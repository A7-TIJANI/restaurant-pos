package com.epicit.pos.service;

import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.OrderLine;
import com.epicit.pos.domain.OrderLineAddon;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import java.util.List;

/** Read model sent to the browser. The surrogate order id is never exposed; orderNumber is the business number. */
public record OrderView(int orderNumber, OrderStatus status, PaymentMethod paymentMethod, long total,
                        List<LineView> lines) {

    public record LineView(long id, long menuItemId, String name, long unitPrice, int qty, long lineTotal,
                           List<AddonView> addons) {
    }

    public record AddonView(long addonId, String name) {
    }

    static OrderView of(CustomerOrder order) {
        List<LineView> lines = order.getLines().stream().map(OrderView::lineOf).toList();
        return new OrderView(order.getOrderNumber(), order.getStatus(), order.getPaymentMethod(),
                order.getTotal(), lines);
    }

    private static LineView lineOf(OrderLine line) {
        List<AddonView> addons = line.getAddons().stream()
                .map((OrderLineAddon a) -> new AddonView(a.getAddon().getId(), a.getAddonName()))
                .toList();
        return new LineView(line.getId(), line.getMenuItem().getId(), line.getItemName(), line.getUnitPrice(),
                line.getQty(), line.getLineTotal(), addons);
    }
}
