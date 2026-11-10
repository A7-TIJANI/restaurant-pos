package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.AuditLog;
import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.AuditLogRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.service.OwnerOrderService.Addition;
import com.epicit.pos.service.OwnerOrderService.EditRequest;
import com.epicit.pos.service.OwnerOrderService.OwnerOrderView;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Owner changes to finalized orders: stock follows every change, everything is audited, history is protected. */
@IntegrationTest
class OwnerOrderServiceTest {

    @Autowired OwnerOrderService owner;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepo;
    @Autowired MenuItemRepository menuItems;
    @Autowired AuditLogRepository audit;
    @Autowired Clock clock;

    MenuItem meat;      // 2,500 RWF, stock 100
    MenuItem chicken;   // 2,000 RWF, stock 100

    @BeforeEach
    void loadMenu() {
        List<MenuItem> menu = menuItems.findByActiveTrueOrderByIdAsc();
        meat = menu.get(0);
        chicken = menu.get(1);
    }

    /** Rings up and finalizes one order of a single item; returns its order number. */
    private int sell(MenuItem item, int qty, PaymentMethod method) {
        orders.current();
        for (int i = 0; i < qty; i++) {
            orders.addItem(item.getId());
        }
        orders.setPayment(method);
        orders.startPrint();
        int number = orders.customerCopyPrinted().orderNumber();
        orders.finalizeOrder(number);
        return number;
    }

    private long firstLineId(int orderNumber) {
        return owner.get(orderNumber).order().lines().get(0).id();
    }

    private List<AuditLog> auditRows() {
        List<AuditLog> rows = new ArrayList<>();
        audit.findAll().forEach(rows::add);
        return rows;
    }

    private AuditLog lastAudit(String action) {
        return auditRows().stream().filter(a -> a.getAction().equals(action)).reduce((a, b) -> b).orElseThrow();
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private static EditRequest quantities(long lineId, int qty, String reason) {
        return new EditRequest(null, Map.of(lineId, qty), null, reason);
    }

    // ---- edit: quantities -----------------------------------------------------------------------------------

    @Test
    void loweringAQuantityReturnsStockRecomputesTotalAndWritesTheAuditTrail() {
        int n = sell(meat, 3, PaymentMethod.CASH);          // stock 97, total 7,500
        assertThat(meat.getStock()).isEqualTo(97);

        OwnerOrderView after = owner.edit(n, quantities(firstLineId(n), 1, "Customer returned two"));

        assertThat(after.order().total()).isEqualTo(2500L);
        assertThat(after.order().lines().get(0).qty()).isEqualTo(1);
        assertThat(meat.getStock()).isEqualTo(99);
        AuditLog entry = lastAudit("ORDER_EDIT");
        assertThat(entry.getActor()).isEqualTo("OWNER");
        assertThat(entry.getEntity()).isEqualTo("order #" + n);
        assertThat(entry.getReason()).isEqualTo("Customer returned two");
        assertThat(entry.getOldValue()).contains("3 x Meat Kofta @2500").contains("total=7500");
        assertThat(entry.getNewValue()).contains("1 x Meat Kofta @2500").contains("total=2500");
    }

    @Test
    void raisingAQuantityTakesMoreStock() {
        int n = sell(meat, 1, PaymentMethod.CASH);

        OwnerOrderView after = owner.edit(n, quantities(firstLineId(n), 4, "Forgot three"));

        assertThat(after.order().total()).isEqualTo(10_000L);
        assertThat(meat.getStock()).isEqualTo(96);
    }

    @Test
    void raisingAQuantityBeyondStockIsRefusedAndStockIsUntouched() {
        int n = sell(meat, 1, PaymentMethod.CASH);          // stock 99
        long lineId = firstLineId(n);

        assertRejected(() -> owner.edit(n, quantities(lineId, 500, "Too many")), "OUT_OF_STOCK");

        assertThat(meat.getStock()).isEqualTo(99);
    }

    @Test
    void settingAQuantityToZeroRemovesTheLineAndRestoresItsStock() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        // add a second item through an edit so the order has two lines
        owner.edit(n, new EditRequest(null, null, List.of(new Addition(chicken.getId(), 2)), "Added chicken"));
        assertThat(chicken.getStock()).isEqualTo(98);
        long chickenLine = owner.get(n).order().lines().stream()
                .filter(l -> l.name().equals("Chicken Kofta")).findFirst().orElseThrow().id();

        OwnerOrderView after = owner.edit(n, quantities(chickenLine, 0, "Chicken was cancelled"));

        assertThat(after.order().lines()).hasSize(1);
        assertThat(chicken.getStock()).isEqualTo(100);
        assertThat(after.order().total()).isEqualTo(2500L);
    }

    @Test
    void removingTheLastLineIsRefusedUseVoidInstead() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        assertRejected(() -> owner.edit(n, quantities(firstLineId(n), 0, "Remove it")), "EMPTY_ORDER");
    }

    @Test
    void negativeQuantityIsRefused() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        assertRejected(() -> owner.edit(n, quantities(firstLineId(n), -1, "Oops")), "BAD_REQUEST");
    }

    @Test
    void unknownLineIsNotFound() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        assertRejected(() -> owner.edit(n, quantities(999_999L, 1, "Ghost")), "NOT_FOUND");
    }

    // ---- edit: additions + payment --------------------------------------------------------------------------

    @Test
    void addingANewItemCreatesALineWithTheCurrentPriceAndTakesStock() {
        int n = sell(meat, 1, PaymentMethod.CASH);

        OwnerOrderView after = owner.edit(n,
                new EditRequest(null, null, List.of(new Addition(chicken.getId(), 2)), "Customer added chicken"));

        assertThat(after.order().lines()).hasSize(2);
        assertThat(after.order().total()).isEqualTo(2500L + 2 * 2000L);
        assertThat(chicken.getStock()).isEqualTo(98);
    }

    @Test
    void addingAnItemAlreadyOnTheOrderMergesIntoItsLine() {
        int n = sell(meat, 1, PaymentMethod.CASH);

        OwnerOrderView after = owner.edit(n,
                new EditRequest(null, null, List.of(new Addition(meat.getId(), 2)), "Two more"));

        assertThat(after.order().lines()).hasSize(1);
        assertThat(after.order().lines().get(0).qty()).isEqualTo(3);
        assertThat(meat.getStock()).isEqualTo(97);
    }

    @Test
    void addingADeactivatedItemOrBeyondStockIsRefused() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        chicken.setActive(false);
        menuItems.saveAndFlush(chicken);
        assertRejected(() -> owner.edit(n,
                new EditRequest(null, null, List.of(new Addition(chicken.getId(), 1)), "x")), "ITEM_INACTIVE");

        assertRejected(() -> owner.edit(n,
                new EditRequest(null, null, List.of(new Addition(meat.getId(), 5000)), "x")), "OUT_OF_STOCK");
    }

    @Test
    void paymentMethodCanBeCorrectedAndShowsInTheAuditTrail() {
        int n = sell(meat, 1, PaymentMethod.CASH);

        OwnerOrderView after = owner.edit(n, new EditRequest(PaymentMethod.MOMO, null, null, "Paid by MoMo"));

        assertThat(after.order().paymentMethod()).isEqualTo(PaymentMethod.MOMO);
        assertThat(meat.getStock()).isEqualTo(99);          // payment fix never touches stock
        assertThat(lastAudit("ORDER_EDIT").getOldValue()).contains("payment=CASH");
        assertThat(lastAudit("ORDER_EDIT").getNewValue()).contains("payment=MOMO");
    }

    @Test
    void editsKeepTheSnapshotPriceOfLinesThatAreNotNewlyAdded() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        meat.setPrice(9999);                                // menu price changed after the sale
        menuItems.saveAndFlush(meat);

        OwnerOrderView after = owner.edit(n, quantities(firstLineId(n), 2, "One more"));

        assertThat(after.order().total()).isEqualTo(5000L);
        assertThat(after.order().lines().get(0).unitPrice()).isEqualTo(2500L);
    }

    @Test
    void anEditThatChangesNothingIsRefused() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        assertRejected(() -> owner.edit(n, new EditRequest(PaymentMethod.CASH, Map.of(firstLineId(n), 1), null, "Nothing")),
                "NO_CHANGES");
    }

    @Test
    void aReasonIsMandatoryAndNothingIsAuditedWithoutOne() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        long before = audit.count();

        assertRejected(() -> owner.edit(n, quantities(firstLineId(n), 2, null)), "REASON_REQUIRED");
        assertRejected(() -> owner.edit(n, quantities(firstLineId(n), 2, "   ")), "REASON_REQUIRED");
        assertRejected(() -> owner.voidOrder(n, ""), "REASON_REQUIRED");

        assertThat(audit.count()).isEqualTo(before);
    }

    // ---- void -----------------------------------------------------------------------------------------------

    @Test
    void voidRestoresAllStockMarksTheOrderAndIsAudited() {
        int n = sell(meat, 2, PaymentMethod.CASH);
        owner.edit(n, new EditRequest(null, null, List.of(new Addition(chicken.getId(), 1)), "Added chicken"));
        assertThat(meat.getStock()).isEqualTo(98);
        assertThat(chicken.getStock()).isEqualTo(99);

        OwnerOrderView voided = owner.voidOrder(n, "Customer left without paying");

        assertThat(voided.order().status()).isEqualTo(OrderStatus.VOIDED);
        assertThat(voided.voidReason()).isEqualTo("Customer left without paying");
        assertThat(meat.getStock()).isEqualTo(100);
        assertThat(chicken.getStock()).isEqualTo(100);
        CustomerOrder stored = orderRepo.findByOrderNumber(n).orElseThrow();
        assertThat(stored.getVoidedBy()).isEqualTo("OWNER");
        assertThat(stored.getFinalizedAt()).isNotNull();    // stays in today's list, marked voided
        AuditLog entry = lastAudit("ORDER_VOID");
        assertThat(entry.getReason()).isEqualTo("Customer left without paying");
        assertThat(entry.getNewValue()).isEqualTo("VOIDED");
        assertThat(entry.getOldValue()).contains("2 x Meat Kofta");
    }

    @Test
    void aVoidedOrderCannotBeVoidedEditedOrReprintedAndStockIsNotRestoredTwice() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        owner.voidOrder(n, "Mistake");

        assertRejected(() -> owner.voidOrder(n, "Again"), "ORDER_VOIDED");
        assertRejected(() -> owner.edit(n, new EditRequest(PaymentMethod.MOMO, null, null, "x")), "ORDER_VOIDED");
        assertRejected(() -> owner.reprint(n), "ORDER_VOIDED");
        assertThat(meat.getStock()).isEqualTo(100);
    }

    // ---- guards ---------------------------------------------------------------------------------------------

    @Test
    void anUnfinalizedOrderCannotBeChangedByTheOwnerApi() {
        orders.addItem(meat.getId());                       // order #1 is still a draft
        assertRejected(() -> owner.voidOrder(1, "x"), "NOT_FINALIZED");
        assertRejected(() -> owner.get(1), "NOT_FINALIZED");
    }

    @Test
    void ordersFromAnEarlierDayAreLockedForEveryOwnerAction() {
        CustomerOrder old = new CustomerOrder(1);
        old.addLine(meat, 1);
        old.setTotal(2500);
        old.setStatus(OrderStatus.FINALIZED);
        old.setFinalizedAt(clock.instant().minus(Duration.ofDays(2)));
        orderRepo.saveAndFlush(old);

        assertRejected(() -> owner.voidOrder(1, "Too late"), "NOT_TODAY");
        assertRejected(() -> owner.edit(1, new EditRequest(PaymentMethod.MOMO, null, null, "x")), "NOT_TODAY");
        assertRejected(() -> owner.reprint(1), "NOT_TODAY");
    }

    @Test
    void unknownOrderIsNotFound() {
        assertRejected(() -> owner.get(404), "NOT_FOUND");
        assertRejected(() -> owner.voidOrder(404, "x"), "NOT_FOUND");
    }

    // ---- reprint (FR-3.4) -----------------------------------------------------------------------------------

    @Test
    void reprintIsAuditedLeavesStockAndTheOrderAlone() {
        int n = sell(meat, 2, PaymentMethod.MOMO);
        long auditBefore = audit.count();

        OwnerOrderView view = owner.reprint(n);

        assertThat(view.order().status()).isEqualTo(OrderStatus.FINALIZED);
        assertThat(view.order().total()).isEqualTo(5000L);
        assertThat(meat.getStock()).isEqualTo(98);
        assertThat(audit.count()).isEqualTo(auditBefore + 1);
        assertThat(lastAudit("ORDER_REPRINT").getEntity()).isEqualTo("order #" + n);
    }

    @Test
    void everyReprintIsItsOwnAuditEntry() {
        int n = sell(meat, 1, PaymentMethod.CASH);
        long before = audit.count();

        owner.reprint(n);
        owner.reprint(n);
        owner.reprint(n);

        assertThat(audit.count()).isEqualTo(before + 3);
    }
}
