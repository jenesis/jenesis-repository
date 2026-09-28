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
 * The issued login keys, administered: listed, issued and revoked - the one implementation the API
 * ({@link KeyLoginController}), the console's login keys screen ({@link KeyLoginScreenController}) and so the CLI all
 * reach. Who may call it is each surface's gate, not this class's: the API route is one of the deployment-wide routes
 * the repository chain holds to a manage key of the operator tenant, and the screen sits under the console's
 * super-admin settings. A login key spans tenants in what it can be bound to, so both gates are the deployment's.
 *
 * <p>Issuing binds the principal into a tenant at a {@link UserDirectory.Role} through the ordinary membership file,
 * so the per-tenant authorization applies to it unchanged, and returns the minted key exactly once - only its hash is
 * stored. Revoking reverses the membership grant the issue wrote, so a reissued same-name key inherits nothing. Both
 * are audited, naming the actor, the tenant and the principal.
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
     * being a viewer, the least a member holds - on behalf of {@code actor}.
     *
     * @throws IllegalArgumentException naming what is wrong with the request: a missing or malformed principal or
     *                                  tenant, or a tenant that does not exist
     */
    public Issued issue(String actor, String principal, String login, String tenant, String role) throws IOException {
        String id = token(principal, "principal");
        String scope = token(tenant, "tenant");
        // A tenant by the console's own rule, not by its marker alone: a store that already held the tenant's
        // repositories before anyone created it through the console is a tenant with no marker, and it is the one
        // an image started over an existing store serves.
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

    /**
     * A principal or tenant as one token. All whitespace is refused, not just a space: the stored line is split on
     * {@code \s+} at read time, so a tab or newline would split into a forged extra field - and a {@code /}, {@code :}
     * or {@code =} has a meaning of its own in a qualified id or a stored line.
     */
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
