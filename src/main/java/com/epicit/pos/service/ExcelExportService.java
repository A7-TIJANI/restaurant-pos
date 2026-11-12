package com.epicit.pos.service;

import com.epicit.pos.domain.Settings;
import com.epicit.pos.repo.SettingsRepository;
import com.epicit.pos.service.ReportService.ItemRow;
import com.epicit.pos.service.ReportService.PeriodReport;
import com.epicit.pos.service.ReportService.PeriodRow;
import com.epicit.pos.service.ReportService.TodayReport;
import com.epicit.pos.service.ReportService.Totals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-5 / section 13: one .xlsx with Summary, Today, Last 7 Days, Last 4 Weeks and Last 12 Months. Numbers are real
 * numeric cells (so an accountant can sum and chart them), headers are bold on a grey fill, widths are set.
 * Built from ReportService, so the workbook can never disagree with the on-screen reports.
 */
@Service
@Transactional(readOnly = true)
public class ExcelExportService {

    public record Export(String fileName, byte[] bytes) {
    }

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReportService reports;
    private final SettingsRepository settings;
    private final Clock clock;

    public ExcelExportService(ReportService reports, SettingsRepository settings, Clock clock) {
        this.reports = reports;
        this.settings = settings;
        this.clock = clock;
    }

    public Export export() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            TodayReport today = reports.today();
            summarySheet(wb, st, today);
            todaySheet(wb, st, today);
            periodSheet(wb, st, "Last 7 Days", reports.last7Days(), new String[] {"Date", "Weekday"},
                    r -> new String[] {r.start().toString(), r.start().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)});
            periodSheet(wb, st, "Last 4 Weeks", reports.last4Weeks(), new String[] {"Week of (Monday)"},
                    r -> new String[] {r.start().toString()});
            periodSheet(wb, st, "Last 12 Months", reports.last12Months(), new String[] {"Month"},
                    r -> new String[] {YearMonth.from(r.start()).toString()});
            wb.write(out);
            return new Export("EpicIT-Report-" + LocalDate.now(clock) + ".xlsx", out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the Excel workbook", e);
        }
    }

    // ---- sheets -----------------------------------------------------------------------------------------------

    private void summarySheet(XSSFWorkbook wb, Styles st, TodayReport today) {
        Sheet sheet = wb.createSheet("Summary");
        String name = settings.findById(Settings.SINGLETON_ID).map(Settings::getRestaurantName).orElse("");
        Totals life = reports.lifetime();
        int r = 0;
        text(sheet.createRow(r++), 0, name, st.title);
        r++;
        pair(sheet, r++, "Restaurant", name, st);
        pair(sheet, r++, "Exported at", STAMP.format(ZonedDateTime.now(clock)) + " (Africa/Kigali)", st);
        r++;
        header(sheet.createRow(r++), st, "Lifetime", "");
        pairNum(sheet, r++, "Orders", life.orders(), st);
        pairNum(sheet, r++, "Revenue (RWF)", life.revenue(), st);
        pairNum(sheet, r++, "Cash (RWF)", life.cash(), st);
        pairNum(sheet, r++, "MoMo (RWF)", life.momo(), st);
        r++;
        header(sheet.createRow(r++), st, "Today (" + today.date() + ")", "");
        pairNum(sheet, r++, "Orders", today.totals().orders(), st);
        pairNum(sheet, r++, "Revenue (RWF)", today.totals().revenue(), st);
        pairNum(sheet, r++, "Cash (RWF)", today.totals().cash(), st);
        pairNum(sheet, r, "MoMo (RWF)", today.totals().momo(), st);
        widths(sheet, 28, 34);
    }

    private void todaySheet(XSSFWorkbook wb, Styles st, TodayReport today) {
        Sheet sheet = wb.createSheet("Today");
        int r = 0;
        text(sheet.createRow(r++), 0, "Today — " + today.date(), st.title);
        r++;
        header(sheet.createRow(r++), st, "Summary", "");
        pairNum(sheet, r++, "Orders", today.totals().orders(), st);
        pairNum(sheet, r++, "Revenue (RWF)", today.totals().revenue(), st);
        pairNum(sheet, r++, "Cash (RWF)", today.totals().cash(), st);
        pairNum(sheet, r++, "MoMo (RWF)", today.totals().momo(), st);
        pairNum(sheet, r++, "Voided orders (not counted)", today.voidedOrders(), st);
        pairNum(sheet, r++, "Voided total (RWF, not counted)", today.voidedTotal(), st);
        r++;
        r = itemTable(sheet, st, r, "Items sold today", today.items());
        r++;
        header(sheet.createRow(r++), st, "Top 5 by quantity", "Item", "Qty");
        int rank = 1;
        for (ItemRow item : today.topItems()) {
            Row row = sheet.createRow(r++);
            num(row, 0, rank++, st.number);
            text(row, 1, item.name(), null);
            num(row, 2, item.qty(), st.number);
        }
        widths(sheet, 34, 26, 18);
    }

    private void periodSheet(XSSFWorkbook wb, Styles st, String sheetName, PeriodReport report, String[] leadHeaders,
                             Function<PeriodRow, String[]> lead) {
        Sheet sheet = wb.createSheet(sheetName);
        int lead0 = leadHeaders.length;
        String[] headers = new String[lead0 + 4];
        System.arraycopy(leadHeaders, 0, headers, 0, lead0);
        headers[lead0] = "Orders";
        headers[lead0 + 1] = "Revenue (RWF)";
        headers[lead0 + 2] = "Cash (RWF)";
        headers[lead0 + 3] = "MoMo (RWF)";
        int r = 0;
        header(sheet.createRow(r++), st, headers);
        for (PeriodRow pr : report.rows()) {
            Row row = sheet.createRow(r++);
            String[] labels = lead.apply(pr);
            for (int i = 0; i < labels.length; i++) {
                text(row, i, labels[i], null);
            }
            num(row, lead0, pr.orders(), st.number);
            num(row, lead0 + 1, pr.revenue(), st.number);
            num(row, lead0 + 2, pr.cash(), st.number);
            num(row, lead0 + 3, pr.momo(), st.number);
        }
        Row total = sheet.createRow(r++);
        text(total, 0, "Total", st.bold);
        for (int i = 1; i < lead0; i++) {
            text(total, i, "", st.bold);
        }
        num(total, lead0, report.totals().orders(), st.totalNumber);
        num(total, lead0 + 1, report.totals().revenue(), st.totalNumber);
        num(total, lead0 + 2, report.totals().cash(), st.totalNumber);
        num(total, lead0 + 3, report.totals().momo(), st.totalNumber);
        r++;
        itemTable(sheet, st, r, "Items sold in this period", reports.items(report.from(), report.to()));
        int[] w = new int[headers.length];
        java.util.Arrays.fill(w, 18);
        w[0] = 26;
        widths(sheet, w);
    }

    /** "title" row, then Item / Qty / Revenue header and one row per item. Returns the next free row index. */
    private int itemTable(Sheet sheet, Styles st, int r, String title, List<ItemRow> items) {
        text(sheet.createRow(r++), 0, title, st.bold);
        header(sheet.createRow(r++), st, "Item", "Qty", "Revenue (RWF)");
        for (ItemRow item : items) {
            Row row = sheet.createRow(r++);
            text(row, 0, item.name(), null);
            num(row, 1, item.qty(), st.number);
            num(row, 2, item.revenue(), st.number);
        }
        return r;
    }

    // ---- cell helpers -------------------------------------------------------------------------------------------

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void num(Row row, int col, long value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue((double) value);
        cell.setCellStyle(style);
    }

    private static void header(Row row, Styles st, String... labels) {
        for (int i = 0; i < labels.length; i++) {
            text(row, i, labels[i], st.header);
        }
    }

    private static void pair(Sheet sheet, int rowIndex, String label, String value, Styles st) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, label, st.bold);
        text(row, 1, value, null);
    }

    private static void pairNum(Sheet sheet, int rowIndex, String label, long value, Styles st) {
        Row row = sheet.createRow(rowIndex);
        text(row, 0, label, st.bold);
        num(row, 1, value, st.number);
    }

    private static void widths(Sheet sheet, int... characters) {
        for (int i = 0; i < characters.length; i++) {
            sheet.setColumnWidth(i, characters[i] * 256);
        }
    }

    /** Workbook-scoped styles (a workbook has a hard limit on distinct styles, so they are created once). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle bold;
        final CellStyle number;
        final CellStyle totalNumber;

        Styles(XSSFWorkbook wb) {
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            short thousands = wb.createDataFormat().getFormat("#,##0");

            title = wb.createCellStyle();
            title.setFont(titleFont);

            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            bold = wb.createCellStyle();
            bold.setFont(boldFont);

            number = wb.createCellStyle();
            number.setDataFormat(thousands);

            totalNumber = wb.createCellStyle();
            totalNumber.setFont(boldFont);
            totalNumber.setDataFormat(thousands);
            totalNumber.setBorderTop(BorderStyle.THIN);
        }
    }
}
