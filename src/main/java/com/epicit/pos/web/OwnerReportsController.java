package com.epicit.pos.web;

import com.epicit.pos.service.ReportService;
import com.epicit.pos.service.ReportService.PeriodReport;
import com.epicit.pos.service.ReportService.TodayReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-4: financial reports are owner-only; the whole /api/owner/** tree is guarded by OwnerAuthInterceptor. */
@RestController
@RequestMapping("/api/owner/reports")
public class OwnerReportsController {

    private final ReportService reports;

    public OwnerReportsController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/today")
    public TodayReport today() {
        return reports.today();
    }

    @GetMapping("/days")
    public PeriodReport days() {
        return reports.last7Days();
    }

    @GetMapping("/weeks")
    public PeriodReport weeks() {
        return reports.last4Weeks();
    }

    @GetMapping("/months")
    public PeriodReport months() {
        return reports.last12Months();
    }
}
