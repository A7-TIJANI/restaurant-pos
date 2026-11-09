package com.epicit.pos.web;

import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.service.FinalizeResult;
import com.epicit.pos.service.OrderService;
import com.epicit.pos.service.OrderView;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON API behind the POS screen. Every call acts on the single in-progress order (A12), except finalize. */
@RestController
@RequestMapping("/api/order")
public class OrderApiController {

    public record AddItemRequest(long menuItemId) {
    }

    public record QuantityRequest(int delta) {
    }

    public record AddonRequest(long addonId) {
    }

    public record PaymentRequest(PaymentMethod method) {
    }

    private final OrderService service;

    public OrderApiController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public OrderView current() {
        return service.current();
    }

    @PostMapping("/items")
    public OrderView addItem(@RequestBody AddItemRequest request) {
        return service.addItem(request.menuItemId());
    }

    @PostMapping("/lines/{lineId}/quantity")
    public OrderView changeQuantity(@PathVariable long lineId, @RequestBody QuantityRequest request) {
        return service.changeQuantity(lineId, request.delta());
    }

    @DeleteMapping("/lines/{lineId}")
    public OrderView removeLine(@PathVariable long lineId) {
        return service.removeLine(lineId);
    }

    @PostMapping("/lines/{lineId}/addons")
    public OrderView toggleAddon(@PathVariable long lineId, @RequestBody AddonRequest request) {
        return service.toggleAddon(lineId, request.addonId());
    }

    @PutMapping("/payment")
    public OrderView setPayment(@RequestBody PaymentRequest request) {
        return service.setPayment(request.method());
    }

    /** Cancel/discard the unfinalized order (FR-1.9). */
    @DeleteMapping
    public OrderView discard() {
        return service.discard();
    }

    @PostMapping("/print/start")
    public OrderView startPrint() {
        return service.startPrint();
    }

    @PostMapping("/print/customer-done")
    public OrderView customerCopyPrinted() {
        return service.customerCopyPrinted();
    }

    @PostMapping("/print/reopen")
    public OrderView reopen() {
        return service.reopen();
    }

    @PostMapping("/finalize/{orderNumber}")
    public ResponseEntity<Map<String, Object>> finalizeOrder(@PathVariable int orderNumber) {
        FinalizeResult result = service.finalizeOrder(orderNumber);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", result.order());
        if (result.outcome() == FinalizeResult.Outcome.STOCK_SHORT) {
            body.put("error", "STOCK_SHORT");
            body.put("message", result.message());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        body.put("outcome", result.outcome());
        return ResponseEntity.ok(body);
    }
}
