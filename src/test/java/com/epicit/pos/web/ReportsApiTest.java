package com.epicit.pos.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** Reports are owner-only on the server; the cashier screen shows only the Top-5 ranking (no money per item). */
@IntegrationTest
class ReportsApiTest {

    private static final String[] REPORTS = {"today", "days", "weeks", "months"};

    @Autowired MockMvc mvc;
    @Autowired OrderService orders;
    @Autowired MenuItemRepository menuItems;

    MenuItem meat;
    MenuItem chicken;
    MockHttpSession owner;

    @BeforeEach
    void setUp() throws Exception {
        meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        chicken = menuItems.findByActiveTrueOrderByIdAsc().get(1);
        owner = new MockHttpSession();
        mvc.perform(post("/api/auth/setup").session(owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"4821\"}")).andExpect(status().isOk());
    }

    private void sell(MenuItem item, int qty, PaymentMethod method) {
        orders.current();
        for (int i = 0; i < qty; i++) {
            orders.addItem(item.getId());
        }
        orders.setPayment(method);
        orders.startPrint();
        orders.finalizeOrder(orders.customerCopyPrinted().orderNumber());
    }

    @Test
    void everyReportRefusesAnonymousAndCashierRequests() throws Exception {
        for (String report : REPORTS) {
            mvc.perform(get("/api/owner/reports/" + report))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("OWNER_REQUIRED"));
            mvc.perform(get("/api/owner/reports/" + report).session(new MockHttpSession()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void todayReportJsonCarriesTotalsItemsAndAnIsoDate() throws Exception {
        sell(meat, 2, PaymentMethod.CASH);
        sell(chicken, 1, PaymentMethod.MOMO);

        mvc.perform(get("/api/owner/reports/today").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}")))
                .andExpect(jsonPath("$.totals.orders").value(2))
                .andExpect(jsonPath("$.totals.revenue").value(7000))
                .andExpect(jsonPath("$.totals.cash").value(5000))
                .andExpect(jsonPath("$.totals.momo").value(2000))
                .andExpect(jsonPath("$.voidedOrders").value(0))
                .andExpect(jsonPath("$.items[0].name").value("Meat Kofta"))
                .andExpect(jsonPath("$.items[0].qty").value(2))
                .andExpect(jsonPath("$.items[0].revenue").value(5000))
                .andExpect(jsonPath("$.topItems", hasSize(2)));
    }

    @Test
    void periodReportsHaveTheRightNumberOfRowsAndTotals() throws Exception {
        sell(meat, 1, PaymentMethod.CASH);

        for (Object[] spec : new Object[][] {{"days", 7}, {"weeks", 4}, {"months", 12}}) {
            mvc.perform(get("/api/owner/reports/" + spec[0]).session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize((Integer) spec[1])))
                    .andExpect(jsonPath("$.rows[" + ((Integer) spec[1] - 1) + "].orders").value(1))
                    .andExpect(jsonPath("$.totals.revenue").value(2500))
                    .andExpect(jsonPath("$.from").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}")))
                    .andExpect(jsonPath("$.to").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}")));
        }
    }

    // ---- cashier Top-5 on the POS page ---------------------------------------------------------------------------

    @Test
    void posPageShowsTheEmptyMessageBeforeAnySale() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No sales data yet")));
    }

    @Test
    void posPageRanksTodaysBestSellersByQuantityForCashiers() throws Exception {
        sell(chicken, 3, PaymentMethod.CASH);
        sell(meat, 1, PaymentMethod.CASH);

        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("rank-1").contains("rank-2").doesNotContain("rank-3");
        org.assertj.core.api.Assertions.assertThat(html.indexOf("Chicken Kofta")).isLessThan(html.indexOf("Meat Kofta", html.indexOf("rank-1")));
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("No sales data yet");
    }
}
