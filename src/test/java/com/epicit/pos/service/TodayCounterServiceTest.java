package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A16: the cashier-visible counter strip counts only today's FINALIZED orders. */
@IntegrationTest
class TodayCounterServiceTest {

    @Autowired TodayCounterService counter;
    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired MenuItemRepository menuItems;
    @Autowired Clock clock;

    private void sell(MenuItem item, int qty, PaymentMethod method) {
        service.current();
        for (int i = 0; i < qty; i++) {
            service.addItem(item.getId());
        }
        service.setPayment(method);
        service.startPrint();
        OrderView printing = service.customerCopyPrinted();
        service.finalizeOrder(printing.orderNumber());
    }

    @Test
    void emptyDayIsAllZeros() {
        assertThat(counter.today()).isEqualTo(new TodayStats(0, 0, 0, 0));
    }

    @Test
    void countsRevenueAndSplitsCashFromMomo() {
        MenuItem meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);     // 2,500
        MenuItem chicken = menuItems.findByActiveTrueOrderByIdAsc().get(1);  // 2,000

        sell(meat, 2, PaymentMethod.CASH);      // 5,000 cash
        sell(chicken, 1, PaymentMethod.MOMO);   // 2,000 momo
        sell(meat, 1, PaymentMethod.CASH);      // 2,500 cash

        assertThat(counter.today()).isEqualTo(new TodayStats(3, 9500, 7500, 2000));
    }

    @Test
    void draftsPrintingAndVoidedOrdersAreNotCounted() {
        MenuItem meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        sell(meat, 1, PaymentMethod.CASH);

        service.addItem(meat.getId());                       // a draft in progress
        CustomerOrder voided = new CustomerOrder(orders.maxOrderNumber() + 1);
        voided.setStatus(OrderStatus.VOIDED);
        voided.setTotal(9999);
        voided.setFinalizedAt(clock.instant());
        orders.saveAndFlush(voided);

        assertThat(counter.today()).isEqualTo(new TodayStats(1, 2500, 2500, 0));
    }

    @Test
    void ordersFromEarlierDaysAreNotCounted() {
        CustomerOrder old = new CustomerOrder(1);
        old.setStatus(OrderStatus.FINALIZED);
        old.setTotal(4000);
        old.setFinalizedAt(clock.instant().minus(Duration.ofDays(2)));
        orders.saveAndFlush(old);

        assertThat(counter.today()).isEqualTo(new TodayStats(0, 0, 0, 0));
    }
}
