package com.epicit.pos.service;

import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.repo.PaymentTotals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Today's counter strip (A16). "Today" is a Africa/Kigali calendar day (A13), whatever the laptop's own zone. */
@Service
@Transactional(readOnly = true)
public class TodayCounterService {

    private final OrderRepository orders;
    private final Clock clock;

    public TodayCounterService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public TodayStats today() {
        ZoneId zone = clock.getZone();
        LocalDate day = LocalDate.now(clock);
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();

        long count = 0;
        long cash = 0;
        long momo = 0;
        for (PaymentTotals row : orders.totalsByPayment(OrderStatus.FINALIZED, dayStart, dayEnd)) {
            count += row.count();
            if (row.method() == PaymentMethod.CASH) {
                cash += row.total();
            } else {
                momo += row.total();
            }
        }
        return new TodayStats(count, cash + momo, cash, momo);
    }
}
