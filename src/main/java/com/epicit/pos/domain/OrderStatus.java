package com.epicit.pos.domain;

/** Order state machine (FR-2.4): DRAFT -> PRINTING_CUSTOMER -> PRINTING_KITCHEN -> FINALIZED; owner may VOID later. */
public enum OrderStatus {
    DRAFT,
    PRINTING_CUSTOMER,
    PRINTING_KITCHEN,
    FINALIZED,
    VOIDED
}
