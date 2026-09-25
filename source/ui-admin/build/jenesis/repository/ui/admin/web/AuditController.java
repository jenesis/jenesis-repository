package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.ui.CurrentTenant;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The audit trail in the console: the current tenant's security-relevant changes, newest first, filterable by action
 * and exportable as CSV. Both routes sit under {@code /admin/} so only a tenant admin reaches them (see SecurityConfig).
 */
@Controller
public class AuditController {

    private final AuditTrail audit;
    private final CurrentTenant current;

    public AuditController(AuditTrail audit, CurrentTenant current) {
        this.audit = audit;
        this.current = current;
    }

    @GetMapping("/ui/admin/audit")
    public String audit(@RequestParam(name = "action", required = false) String action,
                        @RequestParam(name = "after", defaultValue = "") String after,
                        @RequestParam(name = "limit", defaultValue = "200") int limit, Model model) throws IOException {
        int size = Math.clamp(limit, 1, 500);
        // A bounded slice per render, resumed by cursor, never the whole (unrotated) trail (§7); the CSV export still
        // streams it all.
        AuditTrail.Page page = audit.query(tenant(), null, null, action, after, size);
        model.addAttribute("events", page.events());
        model.addAttribute("action", action == null ? "" : action);
        model.addAttribute("limit", size);
        model.addAttribute("more", page.more());
        model.addAttribute("next", page.next());
        model.addAttribute("hasPrev", !after.isBlank());
        return "audit";
    }

    /** Streamed a row at a time straight to the response through the audit SPI's {@code stream} seam, so neither a
     *  whole-trail StringBuilder (three full copies Spring would re-copy to a String then bytes) nor the SPI's
     *  materialised event list ever lands in heap - the store-backed trail holds only one day's events at a time, so a
     *  very large trail exports within a flat memory envelope. Matches the API's {@code /api/audit.csv}. */
    @GetMapping(value = "/ui/admin/audit.csv", produces = "text/csv;charset=UTF-8")
    public void csv(@RequestParam(name = "action", required = false) String action,
                    HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        Writer out = response.getWriter();
        out.write("at,actor,action,target\n");
        audit.stream(tenant(), null, null, action, event ->
                out.write(field(event.at().toString()) + ',' + field(event.actor()) + ',' + field(event.action()) + ','
                        + field(event.target()) + '\n'));
    }

    private String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    private static String field(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.isEmpty() || "=+-@\t\r".indexOf(value.charAt(0)) < 0 ? value : "'" + value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
