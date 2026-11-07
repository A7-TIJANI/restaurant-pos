package com.epicit.pos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line_addon")
public class OrderLineAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine orderLine;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "addon_id", nullable = false)
    private Addon addon;

    /** Snapshot of the add-on name at sale time. */
    @Column(name = "addon_name", nullable = false, length = 100)
    private String addonName;

    @Column(length = 255)
    private String note;

    protected OrderLineAddon() {
    }

    OrderLineAddon(OrderLine orderLine, Addon addon) {
        this.orderLine = orderLine;
        this.addon = addon;
        this.addonName = addon.getName();
    }

    public Long getId() { return id; }
    public OrderLine getOrderLine() { return orderLine; }
    public Addon getAddon() { return addon; }
    public String getAddonName() { return addonName; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
