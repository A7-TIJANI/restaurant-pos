package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.AddonRepository;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The money-critical flow: draft editing, the print state machine, atomic + idempotent finalize (FR-1, FR-2). */
@IntegrationTest
class OrderServiceTest {

    @Autowired OrderService service;
    @Autowired OrderRepository orders;
    @Autowired MenuItemRepository menuItems;
    @Autowired AddonRepository addons;

    MenuItem meat;      // 2,500 RWF
    MenuItem chicken;   // 2,000 RWF

    @BeforeEach
    void loadMenu() {
        List<MenuItem> menu = menuItems.findByActiveTrueOrderByIdAsc();
        meat = menu.get(0);
        chicken = menu.get(1);
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ---- the single draft (A12, FR-1.7) -------------------------------------------------------------------

    @Test
    void firstCallCreatesOneEmptyDraftAndLaterCallsReuseIt() {
        OrderView first = service.current();
        OrderView second = service.current();

        assertThat(first.orderNumber()).isEqualTo(1);
        assertThat(first.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(first.lines()).isEmpty();
        assertThat(second.orderNumber()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(1);
    }

    // ---- editing --------------------------------------------------------------------------------------------

    @Test
    void tappingTheSameCardIncrementsQuantityAndTotalIsComputedFromSnapshots() {
        service.addItem(meat.getId());
        service.addItem(meat.getId());
        OrderView view = service.addItem(chicken.getId());

        assertThat(view.lines()).hasSize(2);
        assertThat(view.lines().get(0).qty()).isEqualTo(2);
        assertThat(view.lines().get(0).lineTotal()).isEqualTo(5000L);
        assertThat(view.total()).isEqualTo(7000L);
    }

    @Test
    void cannotAddMoreThanIsInStock() {
        meat.setStock(1);
        menuItems.saveAndFlush(meat);

        service.addItem(meat.getId());
        assertRejected(() -> service.addItem(meat.getId()), "OUT_OF_STOCK");
        assertThat(service.current().lines().get(0).qty()).isEqualTo(1);
    }

    @Test
    void outOfStockItemCannotBeAddedAtAll() {
        meat.setStock(0);
        menuItems.saveAndFlush(meat);
        assertRejected(() -> service.addItem(meat.getId()), "OUT_OF_STOCK");
    }

    @Test
    void deactivatedItemCannotBeAdded() {
        meat.setActive(false);
        menuItems.saveAndFlush(meat);
        assertRejected(() -> service.addItem(meat.getId()), "ITEM_INACTIVE");
    }

    @Test
    void unknownMenuItemIsNotFound() {
        assertRejected(() -> service.addItem(999_999L), "NOT_FOUND");
    }

    @Test
    void quantityCanGoUpAndDownAndZeroRemovesTheLine() {
        long lineId = service.addItem(meat.getId()).lines().get(0).id();

        assertThat(service.changeQuantity(lineId, +2).lines().get(0).qty()).isEqualTo(3);
        assertThat(service.changeQuantity(lineId, -1).lines().get(0).qty()).isEqualTo(2);
        assertThat(service.changeQuantity(lineId, -2).lines()).isEmpty();
        assertThat(service.current().total()).isZero();
    }

    @Test
    void quantityCannotExceedStock() {
        meat.setStock(2);
        menuItems.saveAndFlush(meat);
        long lineId = service.addItem(meat.getId()).lines().get(0).id();

        assertRejected(() -> service.changeQuantity(lineId, +5), "OUT_OF_STOCK");
    }

    @Test
    void addonsToggleOnAndOffOnTheSelectedLine() {
        long lineId = service.addItem(meat.getId()).lines().get(0).id();
        long onion = addons.findByActiveTrueOrderByIdAsc().get(0).getId();

        OrderView on = service.toggleAddon(lineId, onion);
        assertThat(on.lines().get(0).addons()).extracting(OrderView.AddonView::name).containsExactly("Onion");

        OrderView off = service.toggleAddon(lineId, onion);
        assertThat(off.lines().get(0).addons()).isEmpty();
    }

    @Test
    void paymentMethodIsPerOrder() {
        assertThat(service.current().paymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(service.setPayment(PaymentMethod.MOMO).paymentMethod()).isEqualTo(PaymentMethod.MOMO);
    }

    // ---- discard (FR-1.9) -------------------------------------------------------------------------------------

    @Test
    void discardDeletesTheDraftAndLeavesAFreshEmptyOne() {
        service.addItem(meat.getId());
        assertThat(orders.count()).isEqualTo(1);

        OrderView fresh = service.discard();

        assertThat(fresh.lines()).isEmpty();
        assertThat(fresh.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.count()).isEqualTo(1);              // deleted, not voided: no extra row
        assertThat(orders.findByStatus(OrderStatus.VOIDED)).isEmpty();
    }

    // ---- the print state machine (FR-2.1 .. FR-2.4) ------------------------------------------------------------

    @Test
    void happyPathDraftToPrintingToFinalizedDecrementsStockOnce() {
        service.addItem(meat.getId());
        service.addItem(meat.getId());
        service.addItem(chicken.getId());

        assertThat(service.startPrint().status()).isEqualTo(OrderStatus.PRINTING_CUSTOMER);
        assertThat(service.customerCopyPrinted().status()).isEqualTo(OrderStatus.PRINTING_KITCHEN);

        FinalizeResult result = service.finalizeOrder(1);

        assertThat(result.outcome()).isEqualTo(FinalizeResult.Outcome.FINALIZED);
        assertThat(result.order().status()).isEqualTo(OrderStatus.FINALIZED);
        assertThat(result.order().total()).isEqualTo(7000L);
        assertThat(meat.getStock()).isEqualTo(98);
        assertThat(chicken.getStock()).isEqualTo(99);
        assertThat(orders.findByOrderNumber(1).orElseThrow().getFinalizedAt()).isNotNull();
    }

    @Test
    void afterFinalizingTheNextCallStartsOrderNumberTwo() {
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();
        service.finalizeOrder(1);

        OrderView next = service.current();

        assertThat(next.orderNumber()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(next.lines()).isEmpty();
    }

    @Test
    void finalizeIsIdempotent() {
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();

        FinalizeResult first = service.finalizeOrder(1);
        FinalizeResult second = service.finalizeOrder(1);
        FinalizeResult third = service.finalizeOrder(1);

        assertThat(first.outcome()).isEqualTo(FinalizeResult.Outcome.FINALIZED);
        assertThat(second.outcome()).isEqualTo(FinalizeResult.Outcome.ALREADY_FINALIZED);
        assertThat(third.outcome()).isEqualTo(FinalizeResult.Outcome.ALREADY_FINALIZED);
        assertThat(meat.getStock()).isEqualTo(99);            // decremented exactly once
    }

    @Test
    void cannotFinalizeAnOrderThatWasNeverPrinted() {
        service.addItem(meat.getId());
        assertRejected(() -> service.finalizeOrder(1), "NOT_PRINTED");
        assertThat(meat.getStock()).isEqualTo(100);
    }

    @Test
    void finalizingAnUnknownOrderNumberIsNotFound() {
        assertRejected(() -> service.finalizeOrder(42), "NOT_FOUND");
    }

    @Test
    void emptyCartCannotBePrinted() {
        assertRejected(service::startPrint, "EMPTY_CART");
        assertThat(service.current().status()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void orderIsLockedWhilePrinting() {
        long lineId = service.addItem(meat.getId()).lines().get(0).id();
        service.startPrint();

        assertRejected(() -> service.addItem(chicken.getId()), "ORDER_LOCKED");
        assertRejected(() -> service.changeQuantity(lineId, 1), "ORDER_LOCKED");
        assertRejected(() -> service.removeLine(lineId), "ORDER_LOCKED");
        assertRejected(() -> service.setPayment(PaymentMethod.MOMO), "ORDER_LOCKED");
        assertRejected(service::discard, "ORDER_LOCKED");
    }

    @Test
    void pressingPrintTwiceMidPrintIsHarmless() {
        service.addItem(meat.getId());
        service.startPrint();
        assertThat(service.startPrint().status()).isEqualTo(OrderStatus.PRINTING_CUSTOMER);
    }

    @Test
    void customerDoneWhenNotPrintingIsRejected() {
        service.addItem(meat.getId());
        assertRejected(service::customerCopyPrinted, "NOT_PRINTING");
    }

    @Test
    void resumeAnsweredNoReopensTheOrderAsADraftAndKeepsStock() {
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();

        OrderView reopened = service.reopen();

        assertThat(reopened.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(meat.getStock()).isEqualTo(100);
        assertThat(service.addItem(chicken.getId()).lines()).hasSize(2);   // editable again
    }

    @Test
    void resumeAnsweredYesFinalizesAnOrderStuckInPrintingKitchen() {
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();
        // ... app crashes here; on restart current() still returns the stuck order ...
        OrderView stuck = service.current();
        assertThat(stuck.status()).isEqualTo(OrderStatus.PRINTING_KITCHEN);

        FinalizeResult result = service.finalizeOrder(stuck.orderNumber());

        assertThat(result.outcome()).isEqualTo(FinalizeResult.Outcome.FINALIZED);
        assertThat(meat.getStock()).isEqualTo(99);
    }

    @Test
    void stockRunningOutBeforeFinalizeReopensTheDraftAndDecrementsNothing() {
        service.addItem(meat.getId());
        service.addItem(meat.getId());
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();

        meat.setStock(2);                                   // someone else's sale / owner edit in the meantime
        menuItems.saveAndFlush(meat);

        FinalizeResult result = service.finalizeOrder(1);

        assertThat(result.outcome()).isEqualTo(FinalizeResult.Outcome.STOCK_SHORT);
        assertThat(result.message()).contains("Meat Kofta").contains("3 needed").contains("2 left");
        assertThat(result.order().status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(meat.getStock()).isEqualTo(2);           // untouched
        assertThat(orders.findByOrderNumber(1).orElseThrow().getFinalizedAt()).isNull();
    }

    @Test
    void priceChangeAfterFinalizeDoesNotRewriteTheSale() {
        service.addItem(meat.getId());
        service.startPrint();
        service.customerCopyPrinted();
        service.finalizeOrder(1);

        meat.setPrice(9999);
        menuItems.saveAndFlush(meat);

        OrderView sale = service.finalizeOrder(1).order();
        assertThat(sale.total()).isEqualTo(2500L);
        assertThat(sale.lines().get(0).unitPrice()).isEqualTo(2500L);
    }
}
