package com.epicit.pos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import com.epicit.pos.service.OrderService;
import com.epicit.pos.service.OrderView;
import com.epicit.pos.service.OwnerOrderService;
import com.epicit.pos.service.StockRow;
import com.epicit.pos.service.StockTodayService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** FR-1.11: the cashier's "Stock & sales today" panel (A18): quantities only, no PIN, no revenue. */
@IntegrationTest
class StockApiTest {

    @Autowired MockMvc mvc;
    @Autowired StockTodayService stock;
    @Autowired OrderService service;
    @Autowired OwnerOrderService ownerOrders;
    @Autowired MenuItemRepository menuItems;

    private OrderView sell(MenuItem item, int qty) {
        service.current();
        for (int i = 0; i < qty; i++) {
            service.addItem(item.getId());
        }
        service.setPayment(PaymentMethod.CASH);
        service.startPrint();
        OrderView printing = service.customerCopyPrinted();
        service.finalizeOrder(printing.orderNumber());
        return printing;
    }

    private StockRow row(MenuItem item) {
        return stock.today().stream().filter(r -> r.id().equals(item.getId())).findFirst().orElseThrow();
    }

    @Test
    void finalizingMovesSoldUpAndLeftDown() {
        MenuItem meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        StockRow before = row(meat);
        assertThat(before.soldToday()).isZero();

        sell(meat, 2);

        StockRow after = row(meat);
        assertThat(after.soldToday()).isEqualTo(2);
        assertThat(after.stock()).isEqualTo(before.stock() - 2);
    }

    @Test
    void voidedSalesDropOutAndStockComesBack() {
        MenuItem meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        int startStock = row(meat).stock();
        OrderView done = sell(meat, 2);

        ownerOrders.voidOrder(done.orderNumber(), "test");

        StockRow after = row(meat);
        assertThat(after.soldToday()).isZero();
        assertThat(after.stock()).isEqualTo(startStock);
    }

    @Test
    void endpointNeedsNoPinAndExposesNoMoneyField() throws Exception {
        MenuItem meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        sell(meat, 1);

        MvcResult result = mvc.perform(get("/api/stock/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].soldToday").exists())
                .andExpect(jsonPath("$[0].stock").exists())
                .andReturn();

        String json = result.getResponse().getContentAsString().toLowerCase();
        assertThat(json).doesNotContain("revenue").doesNotContain("price").doesNotContain("total");
    }

    @Test
    void outOfStockIsFlaggedAndListedFirst() {
        List<MenuItem> items = menuItems.findByActiveTrueOrderByIdAsc();
        MenuItem last = items.get(items.size() - 1);
        last.setStock(0);
        menuItems.saveAndFlush(last);

        List<StockRow> rows = stock.today();
        assertThat(rows.get(0).id()).isEqualTo(last.getId());
        assertThat(rows.get(0).level()).isEqualTo(StockRow.Level.OUT);
    }

    @Test
    void deactivatedItemsAreNotListed() {
        MenuItem item = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        item.setActive(false);
        menuItems.saveAndFlush(item);

        assertThat(stock.today()).noneMatch(r -> r.id().equals(item.getId()));
    }
}
