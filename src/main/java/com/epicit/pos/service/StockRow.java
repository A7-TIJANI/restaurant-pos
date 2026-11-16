package com.epicit.pos.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One line of the cashier's "Stock & sales today" panel (A18): quantities only. There is deliberately no price or
 * revenue field, so item revenue can never reach an unauthenticated endpoint (NFR-7).
 */
public record StockRow(Long id, String name, int stock, long soldToday, Level level) {

    public enum Level { OUT, LOW, OK }

    @JsonIgnore
    public boolean isOut() { return level == Level.OUT; }

    @JsonIgnore
    public boolean isLow() { return level == Level.LOW; }
}
