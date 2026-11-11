package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.service.ReportService.ItemRow;
import com.epicit.pos.service.ReportService.PeriodReport;
import com.epicit.pos.service.ReportService.PeriodRow;
import com.epicit.pos.service.ReportService.TodayReport;
import com.epicit.pos.service.ReportService.Totals;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-4 aggregations. Orders are inserted with exact finalization times in Africa/Kigali so the day, week and month
 * boundaries (A13) are tested deliberately, not by luck of when the test happens to run.
 */
@IntegrationTest
class ReportServiceTest {

    @Autowired ReportService reports;
    @Autowired OrderRepository orders;
    @Autowired MenuItemRepository menuItems;
    @Autowired Clock clock;

    MenuItem meat;      // 2,500 RWF
    MenuItem chicken;   // 2,000 RWF
    LocalDate today;
    ZoneId zone;

    @BeforeEach
    void setUp() {
        List<MenuItem> menu = menuItems.findByActiveTrueOrderByIdAsc();
        meat = menu.get(0);
        chicken = menu.get(1);
        today = LocalDate.now(clock);
        zone = clock.getZone();
    }

    /** A finalized order whose finalization instant is the given local Kigali date and time. */
    private CustomerOrder sale(LocalDate day, int hour, int minute, PaymentMethod method, MenuItem item, int qty) {
        CustomerOrder o = new CustomerOrder(orders.maxOrderNumber() + 1);
        o.addLine(item, qty);
        o.setTotal(o.computeTotal());
        o.setPaymentMethod(method);
        o.setStatus(OrderStatus.FINALIZED);
        o.setFinalizedAt(day.atTime(hour, minute).atZone(zone).toInstant());
        return orders.saveAndFlush(o);
    }

    private CustomerOrder voided(LocalDate day, MenuItem item, int qty) {
        CustomerOrder o = sale(day, 12, 0, PaymentMethod.CASH, item, qty);
        o.setStatus(OrderStatus.VOIDED);
        return orders.saveAndFlush(o);
    }

    private static PeriodRow row(PeriodReport report, int index) {
        return report.rows().get(index);
    }

    // ---- empty system -----------------------------------------------------------------------------------------

    @Test
    void anEmptySystemGivesZeroRowsInEveryPeriodNotMissingRows() {
        TodayReport t = reports.today();
        assertThat(t.totals()).isEqualTo(new Totals(0, 0, 0, 0));
        assertThat(t.items()).isEmpty();
        assertThat(t.date()).isEqualTo(today);

        assertThat(reports.last7Days().rows()).hasSize(7);
        assertThat(reports.last4Weeks().rows()).hasSize(4);
        assertThat(reports.last12Months().rows()).hasSize(12);
        assertThat(reports.last7Days().totals()).isEqualTo(new Totals(0, 0, 0, 0));
    }

    // ---- today ------------------------------------------------------------------------------------------------

    @Test
    void todayCountsRevenueSplitsPaymentAndBreaksDownItems() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 2);        // 5,000 cash
        sale(today, 10, 0, PaymentMethod.MOMO, chicken, 1);    // 2,000 momo
        sale(today, 11, 0, PaymentMethod.CASH, meat, 1);       // 2,500 cash

        TodayReport r = reports.today();

        assertThat(r.totals()).isEqualTo(new Totals(3, 9500, 7500, 2000));
        assertThat(r.items()).containsExactly(new ItemRow("Meat Kofta", 3, 7500), new ItemRow("Chicken Kofta", 1, 2000));
        assertThat(r.topItems()).containsExactlyElementsOf(r.items());
    }

    @Test
    void voidedOrdersAreExcludedFromRevenueAndItemsButReportedSeparately() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 1);
        voided(today, chicken, 2);                              // 4,000 voided

        TodayReport r = reports.today();

        assertThat(r.totals()).isEqualTo(new Totals(1, 2500, 2500, 0));
        assertThat(r.items()).extracting(ItemRow::name).containsExactly("Meat Kofta");
        assertThat(r.voidedOrders()).isEqualTo(1);
        assertThat(r.voidedTotal()).isEqualTo(4000);
    }

    @Test
    void draftsAndOrdersFromOtherDaysAreNotInToday() {
        sale(today.minusDays(1), 12, 0, PaymentMethod.CASH, meat, 1);
        CustomerOrder draft = new CustomerOrder(orders.maxOrderNumber() + 1);
        draft.addLine(meat, 1);
        draft.setTotal(2500);
        orders.saveAndFlush(draft);

        assertThat(reports.today().totals()).isEqualTo(new Totals(0, 0, 0, 0));
    }

    @Test
    void anItemRenamedAfterASaleKeepsItsOldNameInHistory() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 1);
        meat.setName("Beef Kofta");
        menuItems.saveAndFlush(meat);
        sale(today, 10, 0, PaymentMethod.CASH, meat, 2);

        assertThat(reports.today().items()).extracting(ItemRow::name, ItemRow::qty)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Beef Kofta", 2L),
                        org.assertj.core.groups.Tuple.tuple("Meat Kofta", 1L));
    }

    // ---- Africa/Kigali boundaries (A13) -----------------------------------------------------------------------

    @Test
    void aSaleJustAfterKigaliMidnightBelongsToTheNewDayEvenThoughUtcIsStillYesterday() {
        sale(today, 0, 30, PaymentMethod.CASH, meat, 1);        // 00:30 Kigali = 22:30 UTC the day before
        sale(today.minusDays(1), 23, 30, PaymentMethod.CASH, chicken, 1);   // 23:30 Kigali = 21:30 UTC, same UTC day

        assertThat(reports.today().totals()).isEqualTo(new Totals(1, 2500, 2500, 0));
        PeriodReport days = reports.last7Days();
        assertThat(row(days, 6).revenue()).isEqualTo(2500);     // today
        assertThat(row(days, 5).revenue()).isEqualTo(2000);     // yesterday
    }

    // ---- last 7 days ------------------------------------------------------------------------------------------

    @Test
    void sevenDaysAreOldestFirstEndAtTodayAndIncludeEmptyDays() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 1);
        sale(today.minusDays(3), 9, 0, PaymentMethod.MOMO, chicken, 1);
        sale(today.minusDays(6), 9, 0, PaymentMethod.CASH, meat, 2);      // oldest day in the window
        sale(today.minusDays(7), 9, 0, PaymentMethod.CASH, meat, 9);      // just outside

        PeriodReport r = reports.last7Days();

        assertThat(r.rows()).hasSize(7);
        assertThat(row(r, 0).start()).isEqualTo(today.minusDays(6));
        assertThat(row(r, 6).start()).isEqualTo(today);
        assertThat(r.from()).isEqualTo(today.minusDays(6));
        assertThat(r.to()).isEqualTo(today);
        assertThat(row(r, 0).revenue()).isEqualTo(5000);
        assertThat(row(r, 1).orders()).isZero();
        assertThat(row(r, 3).momo()).isEqualTo(2000);
        assertThat(row(r, 6).cash()).isEqualTo(2500);
        assertThat(r.totals()).isEqualTo(new Totals(3, 9500, 7500, 2000));
    }

    @Test
    void dayLabelsReadLikeAWeekdayAndDate() {
        String expected = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH).format(today);
        assertThat(row(reports.last7Days(), 6).label()).isEqualTo(expected);
    }

    @Test
    void voidedOrdersNeverReachPeriodTotals() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 1);
        voided(today.minusDays(1), meat, 5);

        assertThat(reports.last7Days().totals()).isEqualTo(new Totals(1, 2500, 2500, 0));
        assertThat(reports.last4Weeks().totals().orders()).isEqualTo(1);
        assertThat(reports.last12Months().totals().orders()).isEqualTo(1);
    }

    // ---- last 4 weeks (calendar weeks, Mon-Sun) ---------------------------------------------------------------

    @Test
    void weeksStartOnMondayAndTheCurrentPartialWeekIsIncluded() {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        PeriodReport r = reports.last4Weeks();

        assertThat(r.rows()).hasSize(4);
        assertThat(row(r, 3).start()).isEqualTo(monday);
        assertThat(row(r, 0).start()).isEqualTo(monday.minusWeeks(3));
        assertThat(row(r, 3).label()).isEqualTo("Week of " + monday);
        for (PeriodRow w : r.rows()) {
            assertThat(w.start().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        }
    }

    @Test
    void sundayBelongsToTheWeekThatEndsAndMondayStartsTheNext() {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        sale(monday.minusDays(1), 12, 0, PaymentMethod.CASH, meat, 1);    // Sunday of last week
        sale(monday, 0, 30, PaymentMethod.CASH, chicken, 1);              // Monday 00:30 this week

        PeriodReport r = reports.last4Weeks();

        assertThat(row(r, 2).revenue()).isEqualTo(2500);
        assertThat(row(r, 3).revenue()).isEqualTo(2000);
    }

    @Test
    void theWindowIncludesItsFirstMondayAndExcludesTheSundayBefore() {
        LocalDate firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(3);
        sale(firstMonday, 0, 5, PaymentMethod.CASH, meat, 1);
        sale(firstMonday.minusDays(1), 23, 55, PaymentMethod.CASH, meat, 4);

        PeriodReport r = reports.last4Weeks();

        assertThat(row(r, 0).revenue()).isEqualTo(2500);
        assertThat(r.totals().orders()).isEqualTo(1);
    }

    // ---- last 12 months -----------------------------------------------------------------------------------------

    @Test
    void monthsRunFromElevenMonthsAgoToTheCurrentPartialMonth() {
        LocalDate thisMonth = today.withDayOfMonth(1);
        DateTimeFormatter label = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

        PeriodReport r = reports.last12Months();

        assertThat(r.rows()).hasSize(12);
        assertThat(row(r, 11).start()).isEqualTo(thisMonth);
        assertThat(row(r, 0).start()).isEqualTo(thisMonth.minusMonths(11));
        assertThat(row(r, 11).label()).isEqualTo(label.format(thisMonth));
    }

    @Test
    void monthBoundariesFollowKigaliTime() {
        LocalDate thisMonth = today.withDayOfMonth(1);
        sale(thisMonth, 0, 30, PaymentMethod.CASH, meat, 1);                        // first minutes of this month
        sale(thisMonth.minusDays(1), 23, 30, PaymentMethod.MOMO, chicken, 1);       // last minutes of last month
        sale(thisMonth.minusMonths(11), 0, 30, PaymentMethod.CASH, meat, 2);        // first day of the window
        sale(thisMonth.minusMonths(12), 12, 0, PaymentMethod.CASH, meat, 9);        // a month too old

        PeriodReport r = reports.last12Months();

        assertThat(row(r, 11).revenue()).isEqualTo(2500);
        assertThat(row(r, 10).revenue()).isEqualTo(2000);
        assertThat(row(r, 10).momo()).isEqualTo(2000);
        assertThat(row(r, 0).revenue()).isEqualTo(5000);
        assertThat(r.totals().orders()).isEqualTo(3);
        assertThat(r.totals().revenue()).isEqualTo(9500);
    }

    // ---- top items (cashier Top-5 strip) --------------------------------------------------------------------------

    @Test
    void topItemsAreTheFiveBestSellersByQuantity() {
        MenuItem[] extra = new MenuItem[4];
        for (int i = 0; i < extra.length; i++) {
            extra[i] = menuItems.saveAndFlush(new MenuItem("Extra " + (i + 1), 1000, 100, "#000000"));
        }
        sale(today, 9, 0, PaymentMethod.CASH, meat, 1);          // 1
        sale(today, 9, 5, PaymentMethod.CASH, chicken, 7);       // 7
        sale(today, 9, 10, PaymentMethod.CASH, extra[0], 6);     // 6
        sale(today, 9, 15, PaymentMethod.CASH, extra[1], 5);     // 5
        sale(today, 9, 20, PaymentMethod.CASH, extra[2], 4);     // 4
        sale(today, 9, 25, PaymentMethod.CASH, extra[3], 3);     // 3  (meat with qty 1 is the sixth and drops out)

        List<ItemRow> top = reports.topItemsToday();

        assertThat(top).extracting(ItemRow::name)
                .containsExactly("Chicken Kofta", "Extra 1", "Extra 2", "Extra 3", "Extra 4");
        assertThat(reports.today().items()).hasSize(6);
    }

    @Test
    void equalQuantitiesAreOrderedByNameSoTheStripIsStable() {
        sale(today, 9, 0, PaymentMethod.CASH, meat, 2);
        sale(today, 9, 5, PaymentMethod.CASH, chicken, 2);

        assertThat(reports.topItemsToday()).extracting(ItemRow::name).containsExactly("Chicken Kofta", "Meat Kofta");
    }
}
