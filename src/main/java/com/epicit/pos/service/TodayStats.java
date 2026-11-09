package com.epicit.pos.service;

/** Cashier-visible counter strip (A16): today's finalized orders, revenue and Cash/MoMo split, in RWF. */
public record TodayStats(long orders, long revenue, long cash, long momo) {
}
