package com.epicit.pos.service;

import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.ItemTotals;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.repo.PaymentTotals;
import com.epicit.pos.repo.SaleRow;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-4 financial reports. Revenue counts FINALIZED orders only (voided orders are excluded and reported
 * separately). Every day, week and month boundary is an Africa/Kigali calendar boundary (A13, NFR-10): a sale at
 * 00:30 Kigali time belongs to the new day even though its UTC timestamp is still the previous day.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    public record Totals(long orders, long revenue, long cash, long momo) {
    }

    public record PeriodRow(String label, LocalDate start, long orders, long revenue, long cash, long momo) {
    }

    /** from/to are the first and last calendar day covered (to = today, so a partial current period is included). */
    public record PeriodReport(String title, LocalDate from, LocalDate to, List<PeriodRow> rows, Totals totals) {
    }

    public record ItemRow(String name, long qty, long revenue) {
    }

    public record TodayReport(LocalDate date, Totals totals, int voidedOrders, long voidedTotal,
                              List<ItemRow> items, List<ItemRow> topItems) {
    }

    static final int TOP_ITEMS = 5;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final OrderRepository orders;
    private final Clock clock;

    public ReportService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    // ---- FR-4.1 Today ------------------------------------------------------------------------------------------

    public TodayReport today() {
        DayBounds day = DayBounds.today(clock);
        List<SaleRow> sales = orders.salesBetween(OrderStatus.FINALIZED, day.start(), day.end());
        List<SaleRow> voided = orders.salesBetween(OrderStatus.VOIDED, day.start(), day.end());
        List<ItemRow> items = itemRows(day.start(), day.end());
        return new TodayReport(LocalDate.now(clock), totalsOf(sales), voided.size(),
                voided.stream().mapToLong(SaleRow::total).sum(), items, top(items));
    }

    /** Cashier Top-5 card strip: best sellers today by quantity. Callers must not expose revenue to cashiers. */
    public List<ItemRow> topItemsToday() {
        DayBounds day = DayBounds.today(clock);
        return top(itemRows(day.start(), day.end()));
    }

    // ---- FR-4.2 .. FR-4.4 periods ------------------------------------------------------------------------------

    /** Today and the six days before it, one row per day (oldest first, empty days included). */
    public PeriodReport last7Days() {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> starts = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            starts.add(today.minusDays(i));
        }
        return period("Last 7 days", starts, day -> day, d -> DAY_LABEL.format(d));
    }

    /** Calendar weeks (Mon-Sun): the current partial week plus the three before it. */
    public PeriodReport last4Weeks() {
        LocalDate thisMonday = mondayOf(LocalDate.now(clock));
        List<LocalDate> starts = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            starts.add(thisMonday.minusWeeks(i));
        }
        return period("Last 4 weeks", starts, ReportService::mondayOf, d -> "Week of " + d);
    }

    /** Calendar months: the current partial month plus the eleven before it. */
    public PeriodReport last12Months() {
        LocalDate thisMonth = LocalDate.now(clock).withDayOfMonth(1);
        List<LocalDate> starts = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            starts.add(thisMonth.minusMonths(i));
        }
        return period("Last 12 months", starts, d -> d.withDayOfMonth(1), d -> MONTH_LABEL.format(d));
    }

    // ---- used by the Excel export ------------------------------------------------------------------------------

    /** Items sold between two Kigali calendar days, both inclusive, best sellers first. */
    public List<ItemRow> items(LocalDate from, LocalDate toInclusive) {
        ZoneId zone = clock.getZone();
        return itemRows(from.atStartOfDay(zone).toInstant(), toInclusive.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Everything ever sold (finalized orders only). */
    public Totals lifetime() {
        long count = 0;
        long cash = 0;
        long momo = 0;
        for (PaymentTotals row : orders.totalsByPayment(OrderStatus.FINALIZED, Instant.EPOCH,
                clock.instant().plus(Duration.ofDays(1)))) {
            count += row.count();
            if (row.method() == PaymentMethod.CASH) {
                cash += row.total();
            } else {
                momo += row.total();
            }
        }
        return new Totals(count, cash + momo, cash, momo);
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private PeriodReport period(String title, List<LocalDate> starts, Function<LocalDate, LocalDate> bucketOf,
                                Function<LocalDate, String> label) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Instant from = starts.get(0).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();   // start -> {orders, revenue, cash, momo}
        starts.forEach(s -> buckets.put(s, new long[4]));
        for (SaleRow sale : orders.salesBetween(OrderStatus.FINALIZED, from, to)) {
            LocalDate day = sale.at().atZone(zone).toLocalDate();
            long[] acc = buckets.get(bucketOf.apply(day));
            if (acc == null) {
                continue;   // outside the requested buckets; cannot happen for a range built from them
            }
            acc[0]++;
            acc[1] += sale.total();
            acc[2] += sale.method() == PaymentMethod.CASH ? sale.total() : 0;
            acc[3] += sale.method() == PaymentMethod.MOMO ? sale.total() : 0;
        }

        List<PeriodRow> rows = new ArrayList<>();
        long orderCount = 0;
        long revenue = 0;
        long cash = 0;
        long momo = 0;
        for (Map.Entry<LocalDate, long[]> e : buckets.entrySet()) {
            long[] a = e.getValue();
            rows.add(new PeriodRow(label.apply(e.getKey()), e.getKey(), a[0], a[1], a[2], a[3]));
            orderCount += a[0];
            revenue += a[1];
            cash += a[2];
            momo += a[3];
        }
        return new PeriodReport(title, starts.get(0), today, rows, new Totals(orderCount, revenue, cash, momo));
    }

    private static Totals totalsOf(List<SaleRow> sales) {
        long cash = 0;
        long momo = 0;
        for (SaleRow s : sales) {
            if (s.method() == PaymentMethod.CASH) {
                cash += s.total();
            } else {
                momo += s.total();
            }
        }
        return new Totals(sales.size(), cash + momo, cash, momo);
    }

    private List<ItemRow> itemRows(Instant from, Instant to) {
        List<ItemRow> rows = new ArrayList<>();
        for (ItemTotals t : orders.itemTotalsBetween(OrderStatus.FINALIZED, from, to)) {
            rows.add(new ItemRow(t.name(), t.qty(), t.revenue()));
        }
        return rows;
    }

    private static List<ItemRow> top(List<ItemRow> items) {
        return items.size() <= TOP_ITEMS ? items : new ArrayList<>(items.subList(0, TOP_ITEMS));
    }

    private static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
