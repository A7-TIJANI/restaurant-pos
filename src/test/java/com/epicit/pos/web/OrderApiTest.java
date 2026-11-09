package com.epicit.pos.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.repo.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The JSON contract the POS screen depends on, including the error shapes it shows to the cashier. */
@IntegrationTest
class OrderApiTest {

    @Autowired MockMvc mvc;
    @Autowired MenuItemRepository menuItems;

    MenuItem meat;

    @BeforeEach
    void loadMenu() {
        meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
    }

    private void addMeat() throws Exception {
        mvc.perform(post("/api/order/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuItemId\":" + meat.getId() + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void currentOrderIsAnEmptyDraftAndNeverExposesTheSurrogateId() throws Exception {
        mvc.perform(get("/api/order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void addingAnItemReturnsTheUpdatedOrderWithServerComputedTotal() throws Exception {
        mvc.perform(post("/api/order/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuItemId\":" + meat.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].name").value("Meat Kofta"))
                .andExpect(jsonPath("$.lines[0].qty").value(1))
                .andExpect(jsonPath("$.total").value(2500));
    }

    @Test
    void outOfStockIsA409WithAMessageTheCashierCanRead() throws Exception {
        meat.setStock(0);
        menuItems.saveAndFlush(meat);

        mvc.perform(post("/api/order/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuItemId\":" + meat.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.message").value("Meat Kofta is out of stock."));
    }

    @Test
    void malformedJsonIsA400NotAStackTrace() throws Exception {
        mvc.perform(post("/api/order/items").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void paymentMethodCanBeSwitchedToMomo() throws Exception {
        mvc.perform(put("/api/order/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"MOMO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("MOMO"));
    }

    @Test
    void discardLeavesAFreshEmptyDraft() throws Exception {
        addMeat();
        mvc.perform(delete("/api/order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines", hasSize(0)));
    }

    @Test
    void fullSaleOverHttpThenFinalizeAgainIsIdempotentAndTheStripUpdates() throws Exception {
        addMeat();
        mvc.perform(post("/api/order/print/start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRINTING_CUSTOMER"));
        mvc.perform(post("/api/order/print/customer-done")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRINTING_KITCHEN"));

        mvc.perform(post("/api/order/finalize/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FINALIZED"))
                .andExpect(jsonPath("$.order.status").value("FINALIZED"));
        mvc.perform(post("/api/order/finalize/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_FINALIZED"));

        mvc.perform(get("/api/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.revenue").value(2500))
                .andExpect(jsonPath("$.cash").value(2500))
                .andExpect(jsonPath("$.momo").value(0));
        mvc.perform(get("/api/order"))
                .andExpect(jsonPath("$.orderNumber").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void finalizingAnUnprintedOrderIsRejected() throws Exception {
        addMeat();
        mvc.perform(post("/api/order/finalize/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOT_PRINTED"));
    }

    @Test
    void emptyCartCannotStartPrinting() throws Exception {
        mvc.perform(post("/api/order/print/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMPTY_CART"));
    }

    @Test
    void stockShortAtFinalizeIsA409ThatReportsTheReopenedDraft() throws Exception {
        addMeat();
        mvc.perform(post("/api/order/print/start")).andExpect(status().isOk());
        mvc.perform(post("/api/order/print/customer-done")).andExpect(status().isOk());
        meat.setStock(0);
        menuItems.saveAndFlush(meat);

        mvc.perform(post("/api/order/finalize/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STOCK_SHORT"))
                .andExpect(jsonPath("$.order.status").value("DRAFT"));
    }

    @Test
    void reopenReturnsAPrintingOrderToDraft() throws Exception {
        addMeat();
        mvc.perform(post("/api/order/print/start")).andExpect(status().isOk());
        mvc.perform(post("/api/order/print/reopen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
