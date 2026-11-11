package com.epicit.pos.repo;

import com.epicit.pos.domain.PaymentMethod;
import java.time.Instant;

/** One order's money facts for report bucketing. Wrapper type because JPQL returns the total as Long. */
public record SaleRow(Instant at, PaymentMethod method, Long total) {
}
