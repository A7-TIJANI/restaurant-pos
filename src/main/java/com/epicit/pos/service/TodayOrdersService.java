package com.epicit.pos.service;

import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.OrderLine;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-3.1: today's orders, visible to the cashier. Read-only; the in-progress order is listed but never created here. */
@Service
@Transactional(readOnly = true)
public class TodayOrdersService {

    public record Row(int orderNumber, Instant at, long total, PaymentMethod paymentMethod, OrderStatus status,
                      int items) {
    }

    private static final List<OrderStatus> IN_PROGRESS =
            List.of(OrderStatus.DRAFT, OrderStatus.PRINTING_CUSTOMER, OrderStatus.PRINTING_KITCHEN);
    private static final List<OrderStatus> FINISHED = List.of(OrderStatus.FINALIZED, OrderStatus.VOIDED);

    private final OrderRepository orders;
    private final Clock clock;

    public TodayOrdersService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    public List<Row> list() {
        DayBounds today = DayBounds.today(clock);
        List<Row> rows = new ArrayList<>();
        orders.findFirstByStatusInOrderByIdDesc(IN_PROGRESS)
                .filter(o -> !o.getLines().isEmpty())
                .ifPresent(o -> rows.add(row(o, o.getOpenedAt())));
        for (CustomerOrder o : orders.findFinishedBetween(FINISHED, today.start(), today.end())) {
            rows.add(row(o, o.getFinalizedAt()));
        }
        return rows;
    }

    private static Row row(CustomerOrder o, Instant at) {
        int items = o.getLines().stream().mapToInt(OrderLine::getQty).sum();
        return new Row(o.getOrderNumber(), at, o.getTotal(), o.getPaymentMethod(), o.getStatus(), items);
    }
}
