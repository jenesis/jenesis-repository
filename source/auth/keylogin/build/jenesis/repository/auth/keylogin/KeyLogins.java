package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.ProviderPrincipal;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.store.TenantService;

/**
 * The issued login keys, administered - the one implementation the API ({@link KeyLoginController}), the console screen
 * ({@link KeyLoginScreenController}) and so the CLI reach. Each surface gates its callers: the API route is held to a
 * manage key of the operator tenant, the screen to the console's super-admins.
 *
 * <p>Issuing binds the principal into a tenant at a {@link UserDirectory.Role} through the ordinary membership file, so
 * per-tenant authorization applies unchanged, and returns the key once; only its hash is stored. Revoking reverses the
 * membership grant, so a reissued same-name key inherits nothing. Both are audited with actor, tenant and principal.
 */
public final class KeyLogins {

    private final KeyLoginKeys keys;
    private final Documents rootStorage;
    private final Authorization authorization;
    private final AuditTrail audit;

    public KeyLogins(KeyLoginKeys keys, Documents rootStorage, Authorization authorization, AuditTrail audit) {
        this.keys = keys;
        this.rootStorage = rootStorage;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** A key issued, returned once: its stored id, the plaintext {@code key}, and what it was bound to. */
    public record Issued(String id, String key, String principal, String tenant, String role) {
    }

    /** Every issued key, by stable id - never any key material. */
    public List<KeyLoginKeys.Entry> list() {
        return keys.list();
    }

    /**
     * Issue a key for {@code principal} as a member of {@code tenant} at {@code role} - admin or editor, anything else
     * a viewer - on behalf of {@code actor}.
     *
     * @throws IllegalArgumentException naming what is wrong: a missing or malformed principal or tenant, or an unknown
     *     tenant
     */
    public Issued issue(String actor, String principal, String login, String tenant, String role) throws IOException {
        String id = token(principal, "principal");
        String scope = token(tenant, "tenant");
        // A tenant by the console's own rule, not its marker alone: a store holding a tenant's repositories from before
        // the console created it has no marker, and an image started over that store serves it.
        if (!new TenantService(rootStorage).exists(scope)) {
            throw new IllegalArgumentException("Unknown tenant '" + scope + "'.");
        }
        UserDirectory.Role granted = UserDirectory.Role.parse(role == null ? "" : role);
        String display = login == null || login.isBlank() ? id : login.trim();
        String qualified = ProviderPrincipal.qualifiedId(KeyLoginMechanism.QUALIFIER, id);
        new UserDirectory(authorization, scope).put(qualified, granted, display);
        KeyLoginKeys.Issued issued = keys.issue(qualified, scope, display);
        audit.record(scope, actor, KeyLoginMechanism.QUALIFIER + ".issue", qualified);
        return new Issued(issued.id(), issued.key(), qualified, scope, granted.label());
    }

    /** Issue a deployment-wide login key for {@code principal} - one that belongs to no tenant, such as the
     *  administrator's the first-run guide hands out - on behalf of {@code actor}, returning its plaintext once. */
    public String issueDeployment(String actor, String principal) throws IOException {
        KeyLoginKeys.Issued issued = keys.issue(principal, "", principal.substring(principal.indexOf('/') + 1));
        audit.record(Scopes.DEFAULT_TENANT, actor, KeyLoginMechanism.QUALIFIER + ".issue", principal);
        return issued.key();
    }

    /** Revoke one issued key by its id, on behalf of {@code actor}; a no-op when it is already gone. */
    public void revoke(String actor, String id) throws IOException {
        Optional<KeyLoginKeys.Entry> entry = keys.find(id);
        keys.revoke(id);
        if (entry.isPresent()) {
            KeyLoginKeys.Entry revoked = entry.get();
            if (!revoked.tenant().isBlank()) {
                new UserDirectory(authorization, revoked.tenant()).remove(revoked.principal());
            }
            audit.record(revoked.tenant().isBlank() ? Scopes.DEFAULT_TENANT : revoked.tenant(), actor,
                    KeyLoginMechanism.QUALIFIER + ".revoke", revoked.principal());
        }
    }

    /** A principal or tenant as one token. All whitespace is refused - the stored line splits on {@code \s+}, so a tab
     *  or newline would forge a field - and so are {@code /}, {@code :} and {@code =}, which mean something in a
     *  qualified id or a stored line. */
    private static String token(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing '" + field + "'.");
        }
        String trimmed = value.trim();
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("/") || trimmed.contains(":") || trimmed.contains("=")) {
            throw new IllegalArgumentException("Invalid '" + field + "'.");
        }
        return trimmed;
    }
}
