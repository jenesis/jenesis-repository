package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.ProviderPrincipal;
import build.jenesis.repository.ui.identity.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator admin API for the issued login keys - list, issue and revoke. Every method is super-admin only (a login
 * key is a deployment-wide credential), enforced here in the controller so the shared security chain is left untouched.
 * Issuing a key binds its principal into a tenant at a {@link UserDirectory.Role} through the ordinary membership file,
 * so the existing per-tenant authorization applies to it unchanged, and returns the minted key exactly once; only its
 * hash is ever stored. Issuing and revoking a key are privileged mutations, so each writes an {@link AuditTrail} event
 * (as a plain sign-in is audited) - naming the acting super-admin, the tenant, and the principal bound or unbound.
 * Readable through the console, CLI or a headless agent (subject to the console's CSRF token on the mutating calls).
 */
@RestController
@RequestMapping("/api/keylogin")
public class KeyLoginController {

    private final KeyLoginKeys keys;
    private final Documents rootStorage;
    private final Authorization authorization;
    private final AuditTrail audit;

    public KeyLoginController(KeyLoginKeys keys, Documents rootStorage, Authorization authorization,
                              AuditTrail audit) {
        this.keys = keys;
        this.rootStorage = rootStorage;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** Issue a key for {@code principal} as a member of {@code tenant} at {@code role} (admin/editor/viewer). */
    public record IssueRequest(String principal, String login, String tenant, String role) {
    }

    /** The issued key, returned once: its stored id, the plaintext {@code key}, and what it was bound to. */
    public record IssuedView(String id, String key, String principal, String tenant, String role) {
    }

    @GetMapping
    public List<KeyLoginKeys.Entry> list(Authentication authentication) {
        requireSuperadmin(authentication);
        return keys.list();
    }

    @PostMapping
    public IssuedView issue(Authentication authentication, @RequestBody IssueRequest request) throws IOException {
        requireSuperadmin(authentication);
        String principal = token(request.principal(), "principal");
        String tenant = token(request.tenant(), "tenant");
        if (!Scopes.valid(tenant) || rootStorage.scope(tenant).version(UserDirectory.TENANT_FILE) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant '" + tenant + "'.");
        }
        UserDirectory.Role role = UserDirectory.Role.parse(request.role() == null ? "" : request.role());
        String login = request.login() == null || request.login().isBlank() ? principal : request.login().trim();
        String qualified = ProviderPrincipal.qualifiedId(KeyLoginMechanism.QUALIFIER, principal);
        new UserDirectory(authorization, tenant, rootStorage).put(qualified, role, login);
        KeyLoginKeys.Issued issued = keys.issue(qualified, tenant, login);
        audit.record(tenant, actor(authentication), KeyLoginMechanism.QUALIFIER + ".issue", qualified);
        return new IssuedView(issued.id(), issued.key(), qualified, tenant, role.label());
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable String id) throws IOException {
        requireSuperadmin(authentication);
        Optional<KeyLoginKeys.Entry> entry = keys.find(id);
        keys.revoke(id);
        // Reverse the membership grant the issue wrote, so a reissued same-name key does not inherit a stale grant, and
        // audit the revoke against the tenant it affected.
        if (entry.isPresent()) {
            KeyLoginKeys.Entry revoked = entry.get();
            if (!revoked.tenant().isBlank()) {
                new UserDirectory(authorization, revoked.tenant(), rootStorage)
                        .remove(revoked.principal());
            }
            audit.record(revoked.tenant().isBlank() ? "default" : revoked.tenant(),
                    actor(authentication), "keylogin.revoke", revoked.principal());
        }
    }

    /** The acting super-admin recorded as the audit actor. */
    private static String actor(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? "anonymous" : authentication.getName();
    }

    private static void requireSuperadmin(Authentication authentication) {
        boolean superadmin = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).anyMatch("ROLE_SUPERADMIN"::equals);
        if (!superadmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super-admin only.");
        }
    }

    private static String token(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing '" + field + "'.");
        }
        String trimmed = value.trim();
        // Reject ALL whitespace, not just a literal space (consistency with KeyLoginKeys.require / UserDirectory.requireId,
        // which reject every Character.isWhitespace because the stored line is split on \s+ at read time). Not a live
        // hole here - principal is re-validated by require()/requireId and tenant by Scopes.valid downstream - but the
        // outer gate must not silently depend on the inner ones honouring the same charset.
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("/") || trimmed.contains(":") || trimmed.contains("=")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid '" + field + "'.");
        }
        return trimmed;
    }
}
