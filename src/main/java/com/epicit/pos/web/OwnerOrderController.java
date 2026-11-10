package com.epicit.pos.web;

import com.epicit.pos.service.OwnerOrderService;
import com.epicit.pos.service.OwnerOrderService.EditRequest;
import com.epicit.pos.service.OwnerOrderService.OwnerOrderView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only order changes. The whole /api/owner/** tree is guarded by OwnerAuthInterceptor. */
@RestController
@RequestMapping("/api/owner/orders")
public class OwnerOrderController {

    public record VoidRequest(String reason) {
    }

    private final OwnerOrderService service;

    public OwnerOrderController(OwnerOrderService service) {
        this.service = service;
    }

    @GetMapping("/{orderNumber}")
    public OwnerOrderView get(@PathVariable int orderNumber) {
        return service.get(orderNumber);
    }

    @PutMapping("/{orderNumber}")
    public OwnerOrderView edit(@PathVariable int orderNumber, @RequestBody EditRequest request) {
        return service.edit(orderNumber, request);
    }

    @PostMapping("/{orderNumber}/void")
    public OwnerOrderView voidOrder(@PathVariable int orderNumber, @RequestBody VoidRequest request) {
        return service.voidOrder(orderNumber, request.reason());
    }

    @PostMapping("/{orderNumber}/reprint")
    public OwnerOrderView reprint(@PathVariable int orderNumber) {
        return service.reprint(orderNumber);
    }
}
