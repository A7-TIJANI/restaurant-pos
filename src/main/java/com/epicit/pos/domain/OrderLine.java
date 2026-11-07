package com.epicit.pos.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** itemName and unitPrice are snapshots taken when the line is created, so later menu edits never rewrite history. */
@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(nullable = false)
    private int qty;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @OneToMany(mappedBy = "orderLine", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderLineAddon> addons = new ArrayList<>();

    protected OrderLine() {
    }

    OrderLine(CustomerOrder order, MenuItem menuItem, int qty) {
        this.order = order;
        this.menuItem = menuItem;
        this.qty = qty;
        this.itemName = menuItem.getName();
        this.unitPrice = menuItem.getPrice();
    }

    public OrderLineAddon addAddon(Addon addon) {
        OrderLineAddon line = new OrderLineAddon(this, addon);
        addons.add(line);
        return line;
    }

    public long getLineTotal() {
        return unitPrice * qty;
    }

    public Long getId() { return id; }
    public CustomerOrder getOrder() { return order; }
    public MenuItem getMenuItem() { return menuItem; }
    public int getQty() { return qty; }
    public void setQty(int qty) { this.qty = qty; }
    public String getItemName() { return itemName; }
    public long getUnitPrice() { return unitPrice; }
    public List<OrderLineAddon> getAddons() { return addons; }
}
