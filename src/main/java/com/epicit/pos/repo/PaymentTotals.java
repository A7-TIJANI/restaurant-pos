package com.epicit.pos.repo;

import com.epicit.pos.domain.PaymentMethod;

/** Projection for the per-payment-method aggregation. Wrapper types because JPQL count/sum return Long. */
public record PaymentTotals(PaymentMethod method, Long count, Long total) {
}
