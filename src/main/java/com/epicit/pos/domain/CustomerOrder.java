package com.epicit.pos.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order. Named CustomerOrder in code because ORDER is reserved in SQL (table: orders).
 * {@code id} is a surrogate key that is never shown; {@code orderNumber} is the business number.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true)
    private int orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 10)
    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    /** RWF. Always recomputed from lines server-side; never trust a client-sent total. */
    @Column(nullable = false)
    private long total;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "voided_by", length = 20)
    private String voidedBy;

    @Column(name = "void_reason")
    private String voidReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderLine> lines = new ArrayList<>();

    protected CustomerOrder() {
    }

    public CustomerOrder(int orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderLine addLine(MenuItem item, int qty) {
        OrderLine line = new OrderLine(this, item, qty);
        lines.add(line);
        return line;
    }

    public long computeTotal() {
        return lines.stream().mapToLong(OrderLine::getLineTotal).sum();
    }

    public Long getId() { return id; }
    public int getOrderNumber() { return orderNumber; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }
    public String getVoidedBy() { return voidedBy; }
    public void setVoidedBy(String voidedBy) { this.voidedBy = voidedBy; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public List<OrderLine> getLines() { return lines; }
}
