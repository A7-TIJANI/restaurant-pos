package com.epicit.pos.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** The current Africa/Kigali calendar day as a half-open instant range (A13), whatever the laptop's own zone. */
record DayBounds(Instant start, Instant end) {

    static DayBounds today(Clock clock) {
        LocalDate day = LocalDate.now(clock);
        return new DayBounds(day.atStartOfDay(clock.getZone()).toInstant(),
                day.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
    }

    boolean contains(Instant instant) {
        return instant != null && !instant.isBefore(start) && instant.isBefore(end);
    }
}
