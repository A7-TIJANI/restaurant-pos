package com.epicit.pos.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.CustomerOrder;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.OrderStatus;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.repo.OrderRepository;
import com.epicit.pos.service.OrderService;
import com.epicit.pos.service.OrderView;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Today's orders (cashier) and the owner's edit/void/reprint endpoints, end to end over HTTP. */
@IntegrationTest
class OwnerOrdersApiTest {

    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepo;
    @Autowired MenuItemRepository menuItems;
    @Autowired Clock clock;

    MenuItem meat;
    MockHttpSession owner;

    @BeforeEach
    void setUp() throws Exception {
        meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\"}")).andExpect(status().isOk());
    }

    private OrderView sell(int qty, PaymentMethod method) {
        orders.current();
        for (int i = 0; i < qty; i++) {
            orders.addItem(meat.getId());
        }
        orders.setPayment(method);
        orders.startPrint();
        OrderView printing = orders.customerCopyPrinted();
        return orders.finalizeOrder(printing.orderNumber()).order();
    }

    // ---- today's orders (cashier, no PIN) ------------------------------------------------------------------

    @Test
    void todaysListShowsFinalizedVoidedAndTheInProgressDraftNewestFirstWithoutAnyPin() throws Exception {
        sell(1, PaymentMethod.CASH);                       // #1
        OrderView second = sell(2, PaymentMethod.MOMO);    // #2
        mvc.perform(post("/api/owner/orders/" + second.orderNumber() + "/void").session(owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong table\"}"))
                .andExpect(status().isOk());
        orders.addItem(meat.getId());                      // #3 draft with a line

        mvc.perform(get("/api/orders/today"))              // no session at all
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].orderNumber").value(3))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[1].orderNumber").value(2))
                .andExpect(jsonPath("$[1].status").value("VOIDED"))
                .andExpect(jsonPath("$[1].paymentMethod").value("MOMO"))
                .andExpect(jsonPath("$[1].total").value(5000))
                .andExpect(jsonPath("$[1].items").value(2))
                .andExpect(jsonPath("$[2].orderNumber").value(1))
                .andExpect(jsonPath("$[2].status").value("FINALIZED"))
                .andExpect(jsonPath("$[2].at").exists());
    }

    @Test
    void anEmptyDraftIsNotListedAndOldOrdersAreNot() throws Exception {
        orders.current();                                   // empty draft
        CustomerOrder old = new CustomerOrder(orderRepo.maxOrderNumber() + 1);
        old.addLine(meat, 1);
        old.setTotal(2500);
        old.setStatus(OrderStatus.FINALIZED);
        old.setFinalizedAt(clock.instant().minus(Duration.ofDays(3)));
        orderRepo.saveAndFlush(old);

        mvc.perform(get("/api/orders/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- owner endpoints ---------------------------------------------------------------------------------------

    @Test
    void ownerCanReadAFinalizedOrderWithItsLines() throws Exception {
        OrderView sold = sell(2, PaymentMethod.CASH);

        mvc.perform(get("/api/owner/orders/" + sold.orderNumber()).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderNumber").value(sold.orderNumber()))
                .andExpect(jsonPath("$.order.lines[0].qty").value(2))
                .andExpect(jsonPath("$.finalizedAt").exists());
    }

    @Test
    void editBindsTheJsonQuantityMapAndAdjustsStockAndTotal() throws Exception {
        OrderView sold = sell(3, PaymentMethod.CASH);
        long lineId = sold.lines().get(0).id();

        mvc.perform(put("/api/owner/orders/" + sold.orderNumber()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"MOMO\",\"quantities\":{\"" + lineId + "\":1},"
                                + "\"additions\":[],\"reason\":\"Returned two\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.total").value(2500))
                .andExpect(jsonPath("$.order.paymentMethod").value("MOMO"))
                .andExpect(jsonPath("$.order.lines[0].qty").value(1));
        org.assertj.core.api.Assertions.assertThat(meat.getStock()).isEqualTo(99);
    }

    @Test
    void missingReasonComesBackAs400WithAReadableMessage() throws Exception {
        OrderView sold = sell(1, PaymentMethod.CASH);

        mvc.perform(put("/api/owner/orders/" + sold.orderNumber()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":\"MOMO\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("REASON_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Please enter a reason."));
    }

    @Test
    void voidAndReprintWorkForTheOwnerButNotForAnotherBrowser() throws Exception {
        OrderView sold = sell(1, PaymentMethod.CASH);
        MockHttpSession cashier = new MockHttpSession();

        mvc.perform(post("/api/owner/orders/" + sold.orderNumber() + "/reprint").session(cashier))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/owner/orders/" + sold.orderNumber() + "/void").session(cashier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/owner/orders/" + sold.orderNumber() + "/reprint").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("FINALIZED"));
        mvc.perform(post("/api/owner/orders/" + sold.orderNumber() + "/void").session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Test void\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidReason").value("Test void"));
        org.assertj.core.api.Assertions.assertThat(meat.getStock()).isEqualTo(100);
    }
}
