package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.server.TrustsController;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.server.spi.Authorization;

/**
 * Manages access credentials within the current tenant through the shared {@link Authorization}: a credential
 * lives under {@code auth/<tenant>/<sha256hex(key)>/} (over the console's root artifact store) holding its
 * grants ({@code scope -> tokens}, where {@code scope} is a project, {@code *} the all-projects wildcard, and a
 * token is a {@code <surface>:<verb>} right) and its metadata (label, created, optional expiry, optional last-used).
 * The grantable tokens are discovered from the {@link GrantableRights} beans on the context rather than hard-coded,
 * so a new surface needs no change here. The minted key carries its tenant in its {@code jenk_<tenant>.<secret><checksum>}
 * form, so the cache server resolves the tenant from the key alone; only the key's hash is stored, so the plaintext key
 * is shown once at creation and never again. A credential expires by default (see {@link Authorization#mintExpiry});
 * the cache and the artifact repository authorize against this same store, so a credential granted here is honoured by
 * every surface.
 */
public class CredentialService {

    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");

    private final Authorization authorization;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;
    private final List<GrantableRights> rights;

    public CredentialService(Authorization authorization, AuditTrail audit, CurrentTenant current,
                             ConsoleActor actor, List<GrantableRights> rights) {
        this.authorization = authorization;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
        this.rights = rights;
    }

    /** A grant as the console shows it: the project, the optional path prefix that narrows it to a subtree, and the
     *  role. {@code scope()} is the stored form ({@code project} or {@code project:path}) the revoke form passes back. */
    public record Grant(String project, String path, String role) {
        public String scope() {
            return path.isEmpty() ? project : project + ":" + path;
        }
    }

    public record Credential(String id, String label, String created, String expires, String lastUsed,
                             String lastUsedAddress, long useCount, String allowedAddresses, List<Grant> grants) {
        public String shortId() {
            return id.length() > 12 ? id.substring(0, 12) : id;
        }
    }

    public record Created(String id, String key, Instant expires) {
    }

    /** The grantable rights surfaces, for a view to render a role picker over the real, discovered set. */
    public List<GrantableRights> availableRights() {
        return rights;
    }

    /** The credentials a page of the console lists. */
    public static final int PAGE = 200;

    public List<Credential> list() throws IOException {
        return list(null, PAGE).credentials();
    }

    /** One page of the tenant's credentials ({@code next} resumes after it, null on the last page), shown by label
     *  within the page: a tenant that has minted keys for years is a listing to page through, never to render. */
    public record Page(List<Credential> credentials, String next) {
    }

    public Page list(String after, int limit) throws IOException {
        String tenant = tenant();
        // The tenant's named roles are the same for every credential in the list, so read them once here rather than
        // re-reading the roles document from the store on each per-credential load (an N+1 store read on the page).
        Map<String, String> roles = authorization.roles(tenant);
        List<Credential> credentials = new ArrayList<>();
        Authorization.CredentialPage page = authorization.credentials(tenant, after, Math.clamp(limit, 1, PAGE));
        for (String id : page.hashes()) {
            if (ID.matcher(id).matches()) {
                credentials.add(load(tenant, id, roles));
            }
        }
        credentials.sort(Comparator.comparing(c -> c.label().toLowerCase(Locale.ROOT)));
        return new Page(credentials, page.next());
    }

    public Credential get(String id) throws IOException {
        String tenant = tenant();
        return load(tenant, requireId(id), authorization.roles(tenant));
    }

    private Credential load(String tenant, String id, Map<String, String> roles) throws IOException {
        Optional<Authorization.Credential> found = authorization.credential(tenant, id);
        if (found.isEmpty()) {
            return new Credential(id, "", "", "", "", "", 0, "", List.of());
        }
        Authorization.Credential credential = found.get();
        List<Grant> grants = new ArrayList<>();
        for (Map.Entry<String, String> grant : credential.grants().entrySet()) {
            String scope = grant.getKey();
            int colon = scope.indexOf(':');
            String project = colon < 0 ? scope : scope.substring(0, colon);
            String path = colon < 0 ? "" : scope.substring(colon + 1);
            grants.add(new Grant(project, path, roleName(roles, grant.getValue())));
        }
        String label = credential.label() == null ? "" : credential.label();
        String allowedAddresses = credential.allowedAddresses() == null ? "" : credential.allowedAddresses();
        String lastUsedAddress = credential.lastUsedAddress() == null ? "" : credential.lastUsedAddress();
        return new Credential(id, label, instant(credential.created()), instant(credential.expires()),
                instant(credential.lastUsed()), lastUsedAddress, credential.useCount(), allowedAddresses, grants);
    }

    /** Mint a credential whose key is shown once. A null {@code expires} stamps the default lifetime so the key
     *  expires by default; {@code nonExpiring} is the explicit, discouraged opt-out that mints an unbounded key. */
    public Created create(String label, Instant expires, boolean nonExpiring) throws IOException {
        String tenant = tenant();
        String key = Authorization.mint(tenant);
        String id = Authorization.hash(key);
        Instant expiry = authorization.mintExpiry(tenant, expires, nonExpiring);
        authorization.provision(tenant, id, label, expiry);
        audit("credential.mint", id);
        return new Created(id, key, expiry);
    }

    /** Rotate a credential: mint a successor inheriting its grants and allowlist, and expire the old key after the
     *  {@code overlap} (null defaults to a week). The successor's key is shown once. */
    public Created rotate(String id, Duration overlap) throws IOException {
        Authorization.Rotated rotated = authorization.rotate(tenant(), requireId(id), overlap);
        audit("credential.rotate", id + " -> " + Authorization.hash(rotated.key()));
        return new Created(Authorization.hash(rotated.key()), rotated.key(), rotated.expires());
    }

    public void setGrant(String id, String project, String path, String role) throws IOException {
        authorization.setGrant(tenant(), requireId(id), scope(project, path), tokensForRole(role));
        audit("grant.set", id + " " + project);
    }

    /** Resolve a named role to its grant tokens for the current tenant. */
    private String tokensForRole(String role) throws IOException {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("A role is required.");
        }
        String tokens = authorization.roles(tenant()).get(role.trim());
        if (tokens == null) {
            throw new IllegalArgumentException("Unknown role '" + role + "'.");
        }
        return tokens;
    }

    /** A grant scope: a project (or {@code *}), optionally narrowed to a {@code project:<prefix>} path subtree. */
    private static String scope(String project, String path) {
        String repository = validProject(project);
        if (path == null || path.isBlank()) {
            return repository;
        }
        return repository + ":" + validPath(path);
    }

    private static String validPath(String path) {
        String trimmed = path.strip();
        if (trimmed.endsWith("/*")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty() || !trimmed.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException(
                    "Invalid path prefix '" + path + "': use letters, digits, '.', '_', '-' and '/'.");
        }
        return trimmed;
    }

    public void removeGrant(String id, String project) throws IOException {
        authorization.removeGrant(tenant(), requireId(id), project);
        audit("grant.remove", id + " " + project);
    }

    /** Set or clear ({@code null}) a credential's expiry. */
    public void setExpiry(String id, Instant expires) throws IOException {
        authorization.setExpiry(tenant(), requireId(id), expires);
        audit("credential.expiry", id);
    }

    /** Set or clear (blank) a credential's source-IP allowlist: comma-separated CIDRs or plain addresses. */
    public void setAllowedAddresses(String id, String addresses) throws IOException {
        authorization.setAllowedAddresses(tenant(), requireId(id), addresses);
        audit("credential.allowed-ips", id);
    }

    /** The current tenant's OIDC trusts: each exchanges a matching id-token for a short-lived credential. */
    public List<Authorization.Trust> trusts() throws IOException {
        return authorization.trusts(tenant());
    }

    /** Add or replace an OIDC trust on the current tenant. */
    public void setTrust(String name, String issuer, String audience, String subject, String scope, String rights,
                         Duration ttl) throws IOException {
        authorization.setTrust(tenant(),
                new Authorization.Trust(name, issuer, audience, subject, scope, rights, ttl));
        audit(TrustsController.SET, name);
    }

    public void removeTrust(String name) throws IOException {
        authorization.removeTrust(tenant(), name);
        audit(TrustsController.REMOVE, name);
    }

    /** The current tenant's role names, for a console role picker. */
    public List<String> roleNames() throws IOException {
        return new ArrayList<>(authorization.roles(tenant()).keySet());
    }

    /** The current tenant's named roles (name to comma-separated tokens), built-in defaults plus custom ones. */
    public Map<String, String> roles() throws IOException {
        return authorization.roles(tenant());
    }

    /** Add or replace a custom role from comma-separated tokens, validated against the known rights. */
    public void setRole(String name, String tokens) throws IOException {
        authorization.setRole(tenant(), name, validRole(tokens));
        audit(AuditActions.ROLE_SET, name);
    }

    public void removeRole(String name) throws IOException {
        authorization.removeRole(tenant(), name);
        audit(AuditActions.ROLE_REMOVE, name);
    }

    /** The role name whose tokens match {@code tokens}, else {@code tokens} verbatim - so a grant shows its role. */
    private static String roleName(Map<String, String> roles, String tokens) {
        Set<String> target = tokenSet(tokens);
        for (Map.Entry<String, String> role : roles.entrySet()) {
            if (tokenSet(role.getValue()).equals(target)) {
                return role.getKey();
            }
        }
        return tokens;
    }

    private static Set<String> tokenSet(String tokens) {
        Set<String> set = new LinkedHashSet<>();
        for (String token : tokens.split(",")) {
            set.add(token.trim());
        }
        return set;
    }

    /** The current tenant's effective credential-lifetime policy: the default stamped on a blank-expiry mint and the
     *  optional ceiling beyond which no key may live. */
    public Authorization.Policy policy() throws IOException {
        return authorization.policy(tenant());
    }

    /** Set or clear ({@code null}) the current tenant's default and maximum credential lifetimes. */
    public void setPolicy(Duration defaultLifetime, Duration maxLifetime) throws IOException {
        authorization.setPolicy(tenant(), defaultLifetime, maxLifetime);
        audit(AuditActions.POLICY_SET, "lifetime");
    }

    public void delete(String id) throws IOException {
        authorization.revoke(tenant(), requireId(id));
        audit("credential.revoke", id);
    }

    private String tenant() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return tenant;
    }

    /** Record a console mutation in the shared audit trail, attributing it to the acting member. */
    private void audit(String action, String target) {
        audit.record(tenant(), actor.name(), action, target);
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid credential id.");
        }
        return id;
    }

    private static String validProject(String project) {
        if (Names.WILDCARD.equals(project)) {
            return Names.WILDCARD;
        }
        if (project == null || !Names.isProject(project.trim())) {
            throw new IllegalArgumentException(
                    "Invalid project '" + project + "': use letters, digits and underscores, or * for all.");
        }
        return project.trim();
    }

    private String validRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role must not be blank.");
        }
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(Names.WILDCARD);
        for (GrantableRights surface : rights) {
            allowed.addAll(surface.tokens());
        }
        List<String> tokens = new ArrayList<>();
        for (String token : role.split(",")) {
            String trimmed = token.trim();
            if (!allowed.contains(trimmed)) {
                throw new IllegalArgumentException("Unknown role token '" + trimmed + "'.");
            }
            if (!tokens.contains(trimmed)) {
                tokens.add(trimmed);
            }
        }
        return String.join(",", tokens);
    }
}
