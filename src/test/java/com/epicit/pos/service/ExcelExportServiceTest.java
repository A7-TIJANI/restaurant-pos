package com.epicit.pos.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.IntegrationTest;
import com.epicit.pos.domain.MenuItem;
import com.epicit.pos.domain.PaymentMethod;
import com.epicit.pos.repo.MenuItemRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ExcelExportServiceTest {

    @Autowired ExcelExportService export;
    @Autowired OrderService orders;
    @Autowired MenuItemRepository menuItems;

    MenuItem meat;
    MenuItem chicken;

    @BeforeEach
    void setUp() {
        meat = menuItems.findByActiveTrueOrderByIdAsc().get(0);
        chicken = menuItems.findByActiveTrueOrderByIdAsc().get(1);
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

    private Workbook open() throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(export.export().bytes()));
    }

    /** Row whose first cell holds the label; returns the cell to its right. */
    private static Cell valueOf(Sheet sheet, String label) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && label.equals(first.getStringCellValue())) {
                return row.getCell(1);
            }
        }
        throw new AssertionError("No row labelled '" + label + "' in " + sheet.getSheetName());
    }

    @Test
    void fileNameIsDatedAndTheWorkbookHasTheFiveSheetsInOrder() throws IOException {
        assertThat(export.export().fileName()).matches("EpicIT-Report-\\d{4}-\\d{2}-\\d{2}\\.xlsx");
        try (Workbook wb = open()) {
            List<String> names = new ArrayList<>();
            wb.forEach(s -> names.add(s.getSheetName()));
            assertThat(names).containsExactly("Summary", "Today", "Last 7 Days", "Last 4 Weeks", "Last 12 Months");
        }
    }

    @Test
    void todayAndSummaryCarryNumericTotals() throws IOException {
        sell(meat, 2, PaymentMethod.CASH);
        sell(chicken, 1, PaymentMethod.MOMO);

        try (Workbook wb = open()) {
            Sheet today = wb.getSheet("Today");
            assertThat(valueOf(today, "Orders").getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(valueOf(today, "Orders").getNumericCellValue()).isEqualTo(2);
            assertThat(valueOf(today, "Revenue (RWF)").getNumericCellValue()).isEqualTo(7000);
            assertThat(valueOf(today, "Cash (RWF)").getNumericCellValue()).isEqualTo(5000);
            assertThat(valueOf(today, "MoMo (RWF)").getNumericCellValue()).isEqualTo(2000);
            assertThat(valueOf(today, "Meat Kofta").getNumericCellValue()).isEqualTo(2);   // qty column

            Sheet summary = wb.getSheet("Summary");
            assertThat(valueOf(summary, "Revenue (RWF)").getNumericCellValue()).isEqualTo(7000);   // lifetime block first
        }
    }

    @Test
    void periodSheetsHaveOneRowPerBucketATotalRowAndAnItemBreakdown() throws IOException {
        sell(meat, 1, PaymentMethod.CASH);

        try (Workbook wb = open()) {
            Sheet days = wb.getSheet("Last 7 Days");
            assertThat(days.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Date");
            assertThat(days.getRow(7).getCell(2).getNumericCellValue()).isEqualTo(1);        // today: 1 order
            assertThat(days.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(days.getRow(8).getCell(3).getNumericCellValue()).isEqualTo(2500);     // total revenue
            assertThat(valueOf(days, "Meat Kofta").getNumericCellValue()).isEqualTo(1);

            assertThat(wb.getSheet("Last 4 Weeks").getRow(5).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(wb.getSheet("Last 12 Months").getRow(13).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
    }

    @Test
    void anEmptyDatabaseStillProducesAValidWorkbook() throws IOException {
        try (Workbook wb = open()) {
            assertThat(valueOf(wb.getSheet("Today"), "Orders").getNumericCellValue()).isZero();
        }
    }
}
