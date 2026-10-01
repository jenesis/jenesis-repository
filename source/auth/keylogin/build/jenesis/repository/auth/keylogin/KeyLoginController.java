package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator API for issued login keys - list, issue, revoke - over {@link KeyLogins}, which the console screen calls
 * too. {@code /api/keylogin} is a deployment-wide route the repository chain holds to a manage key of the operator
 * tenant, since a login key can bind a principal into any tenant: the CLI's {@code keylogin} commands reach it, a
 * tenant administrator's key does not. A request refused for what it asks answers {@code 400} with the reason.
 */
@RestController
@RequestMapping("/api/keylogin")
public class KeyLoginController {

    private final KeyLogins keyLogins;

    public KeyLoginController(KeyLogins keyLogins) {
        this.keyLogins = keyLogins;
    }

    /** Issue a key for {@code principal} as a member of {@code tenant} at {@code role} (admin/editor/viewer). */
    public record IssueRequest(String principal, String login, String tenant, String role) {
    }

    @GetMapping
    public List<KeyLoginKeys.Entry> list() {
        return keyLogins.list();
    }

    @PostMapping
    public KeyLogins.Issued issue(HttpServletRequest request, @RequestBody IssueRequest issue) throws IOException {
        try {
            return keyLogins.issue(actor(request), issue.principal(), issue.login(), issue.tenant(), issue.role());
        } catch (IllegalArgumentException refused) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage());
        }
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(HttpServletRequest request, @PathVariable("id") String id) throws IOException {
        keyLogins.revoke(actor(request), id);
    }

    /** Who a request acts as on the audit trail: its key's hash, as every operator route records it. */
    private static String actor(HttpServletRequest request) {
        String key = PresentedKey.from(request);
        return key == null || key.isBlank() ? "anonymous" : Authorization.hash(key);
    }
}
