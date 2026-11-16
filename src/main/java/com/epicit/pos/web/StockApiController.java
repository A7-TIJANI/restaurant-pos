package com.epicit.pos.web;

import com.epicit.pos.service.StockRow;
import com.epicit.pos.service.StockTodayService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cashier "Stock & sales today" data (FR-1.11). No PIN, so it returns quantities only: never revenue (NFR-7). */
@RestController
public class StockApiController {

    private final StockTodayService stock;

    public StockApiController(StockTodayService stock) {
        this.stock = stock;
    }

    @GetMapping("/api/stock/today")
    public List<StockRow> today() {
        return stock.today();
    }
}
