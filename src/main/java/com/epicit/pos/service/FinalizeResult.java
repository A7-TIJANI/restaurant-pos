package com.epicit.pos.service;

/**
 * Outcome of finalizing an order. STOCK_SHORT is a normal business outcome, not an exception: it must
 * commit the order's reopening to DRAFT, which a thrown exception would roll back.
 */
public record FinalizeResult(Outcome outcome, OrderView order, String message) {

    public enum Outcome {
        /** Stock decremented, sale recorded. */
        FINALIZED,
        /** Order was already final: nothing was changed (idempotent, FR-2.3). */
        ALREADY_FINALIZED,
        /** Not enough stock at finalize time; the order was reopened as a DRAFT. */
        STOCK_SHORT
    }
}
