package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.audit.AuditCsv;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.ui.CurrentTenant;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import build.jenesis.repository.ui.ConsoleScreen;

/**
 * The audit trail in the console: the current tenant's security-relevant changes, newest first, filterable by action
 * and exportable as CSV. Both routes sit under {@code /admin/}, for tenant admins.
 */
@Controller
@ConsoleScreen
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
        // A bounded slice per render, resumed by cursor.
        AuditTrail.Page page = audit.query(tenant(), null, null, action, after, size);
        model.addAttribute("events", page.events());
        model.addAttribute("action", action == null ? "" : action);
        model.addAttribute("limit", size);
        model.addAttribute("more", page.more());
        model.addAttribute("next", page.next());
        model.addAttribute("hasPrev", !after.isBlank());
        return "audit";
    }

    /** The trail as CSV - the export {@code /api/audit.csv} writes, between {@code from} and {@code to} when named -
     *  streamed a row at a time, and {@code 501} on a deployment with no audit module. */
    @GetMapping(value = "/ui/admin/audit.csv", produces = AuditCsv.CONTENT_TYPE)
    public void csv(@RequestParam(name = "from", required = false) String from,
                    @RequestParam(name = "to", required = false) String to,
                    @RequestParam(name = "action", required = false) String action,
                    HttpServletResponse response) throws IOException {
        if (audit == AuditTrail.none()) {
            response.setStatus(501);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(AuditCsv.NOT_INSTALLED);
            return;
        }
        response.setContentType(AuditCsv.CONTENT_TYPE);
        AuditCsv.write(audit, tenant(), instant(from), instant(to), action, response.getWriter());
    }

    /** A blank value is no bound; otherwise an absolute ISO-8601 instant. */
    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    private String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }
}
