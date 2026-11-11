package com.epicit.pos.repo;

/** Quantity and revenue per item name (the sale-time snapshot, so renames never rewrite history). */
public record ItemTotals(String name, Long qty, Long revenue) {
}
