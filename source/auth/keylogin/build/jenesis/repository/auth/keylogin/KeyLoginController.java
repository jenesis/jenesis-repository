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
 * The operator API for the issued login keys - list, issue and revoke - over {@link KeyLogins}, the implementation the
 * console's login keys screen calls too. What it answers to is decided before a request reaches it: {@code /api/keylogin}
 * is one of the deployment-wide routes the repository chain's authorization manager holds to a manage key of the
 * operator tenant, since a login key can bind a principal into any tenant. So the CLI's {@code keylogin} commands,
 * which present an operator key, reach it, and a tenant's own administrator key does not. A request it refuses for
 * what it asks - an unknown tenant, a malformed principal - answers {@code 400} with the reason.
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

    /** Who a request acts as on the audit trail: its key's hash, the way every operator route records it. */
    private static String actor(HttpServletRequest request) {
        String key = PresentedKey.from(request);
        return key == null || key.isBlank() ? "anonymous" : Authorization.hash(key);
    }
}
