package build.jenesis.repository.server;

import build.jenesis.repository.server.spi.Authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

/**
 * The credential surface: how an operator issues, scopes and revokes the keys this deployment authorizes with.
 *
 * <p>Without it an enforcing deployment could not be operated as configured. {@code jenreg.auth} is on by default
 * and a keyless caller is rejected, so every credential had to be created out of band - and the only advice a
 * fresh install could be given was to switch authentication off, which is not a bootstrap but a different
 * deployment. The first key comes from {@code jenreg.bootstrap-key}; every key after it comes from here.
 *
 * <p>The tenant is read from the managing key itself ({@link Authorization#tenantOf}), which is where a key's
 * tenant already lives - {@code jenk_<tenant>.<secret><checksum>} - so this surface needs no tenant routing of its
 * own and behaves the same on a single-tenant deployment as on a routed one.
 *
 * <p>Every route is under {@code /api/} and is therefore gated by {@code manage:read} (the GET) or
 * {@code manage:write} (the mutations) at scope {@code *} by the security chain before it is reached; nothing here
 * re-decides authorization. The managing key is read the way the chain reads it ({@link PresentedKey}), so a caller
 * may present it in the native header or as a bearer token. A minted secret is returned <em>once</em> and never again: only its hash is stored, so
 * a lost key is re-issued rather than recovered.
 */
@RestController
public final class CredentialsController {

    /** A credential id is the key's hash - 64 hex characters - and nothing else may be used to address one. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Authorization authorization;
    private final CredentialContext context;

    public CredentialsController(Authorization authorization, CredentialContext context) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Every credential of the managing key's tenant, secrets excluded - they are not stored to begin with. */
    /** The most credentials one answer lists; a caller past it follows the {@code X-Next-Cursor} header. */
    static final int MAX_PAGE = 500;

    /** The tenant's credentials, one page per request: at most {@code limit} (default and at most {@link #MAX_PAGE})
     *  in key order from {@code after}, with the cursor of the next page in the {@code X-Next-Cursor} header when
     *  more remain - a tenant that has minted and rotated keys for years is a listing to page, not to render. */
    @GetMapping("/api/credentials")
    @ResponseBody
    public List<CredentialView> credentials(HttpServletRequest http, HttpServletResponse response)
            throws IOException {
        String key = PresentedKey.from(http);
        String tenant = context.tenant(key);
        String after = http.getParameter("after");
        Authorization.CredentialPage page = authorization.credentials(tenant,
                after == null || after.isBlank() ? null : after, pageSize(http.getParameter("limit")));
        List<CredentialView> views = new ArrayList<>();
        for (String hash : page.hashes()) {
            Optional<Authorization.Credential> credential = authorization.credential(tenant, hash);
            credential.ifPresent(value -> views.add(view(value)));
        }
        if (page.next() != null) {
            response.setHeader("X-Next-Cursor", page.next());
        }
        return views;
    }

    private static int pageSize(String value) {
        if (value == null || value.isBlank()) {
            return MAX_PAGE;
        }
        try {
            return Math.clamp(Integer.parseInt(value.trim()), 1, MAX_PAGE);
        } catch (NumberFormatException _) {
            return MAX_PAGE;
        }
    }

    /**
     * Mint a credential and return its secret once.
     *
     * <p>A blank expiry applies the tenant's default lifetime, so a key expires unless {@code nonExpiring} is
     * asked for explicitly - the secure default, since a forgotten key that never expires is the credential most
     * likely to still work when nobody remembers issuing it.
     */
    @PostMapping("/api/credentials")
    @ResponseBody
    public Minted mint(HttpServletRequest http,
                       @RequestBody(required = false) MintRequest request,
                       HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        String tenant = context.tenant(key);
        String minted = Authorization.mint(tenant);
        String hash = Authorization.hash(minted);
        Instant expires = authorization.mintExpiry(tenant,
                request == null ? null : expiry(request.expires()),
                request != null && Boolean.TRUE.equals(request.nonExpiring()));
        authorization.provision(tenant, hash, request == null ? null : request.label(), expires);
        context.audit(key, "credential.mint", hash);
        response.setStatus(201);
        return new Minted(hash, minted, expires == null ? null : expires.toString());
    }

    /** Grant a credential rights at a scope: a repository name, or {@code *} for every repository of the tenant. */
    @PostMapping("/api/credentials/{id}/grants")
    public void setGrant(@PathVariable("id") String id,
                         HttpServletRequest http,
                         @RequestBody GrantRequest request,
                         HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.setGrant(context.tenant(key), hashId(id), request.scope(), String.join(",", request.tokens()));
        context.audit(key, "grant.set", id + " " + request.scope());
        response.setStatus(200);
    }

    @DeleteMapping("/api/credentials/{id}/grants/{scope}")
    public void removeGrant(@PathVariable("id") String id, @PathVariable("scope") String scope,
                            HttpServletRequest http,
                            HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.removeGrant(context.tenant(key), hashId(id), scope);
        context.audit(key, "grant.remove", id + " " + scope);
        response.setStatus(200);
    }

    @PutMapping("/api/credentials/{id}/expiry")
    public void setExpiry(@PathVariable("id") String id,
                          HttpServletRequest http,
                          @RequestBody(required = false) ExpiryRequest request,
                          HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.setExpiry(context.tenant(key), hashId(id), request == null ? null : expiry(request.expires()));
        context.audit(key, "credential.expiry", id);
        response.setStatus(200);
    }

    @DeleteMapping("/api/credentials/{id}")
    public void revoke(@PathVariable("id") String id,
                       HttpServletRequest http,
                       HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.revoke(context.tenant(key), hashId(id));
        context.audit(key, "credential.revoke", id);
        response.setStatus(200);
    }

    /**
     * Rotate a credential: mint a successor that inherits its grants and allowlist, and expire the old one after
     * an overlap so the swap needs no downtime. The successor's secret is returned once, like any mint.
     */
    @PostMapping("/api/credentials/{id}/rotate")
    @ResponseBody
    public Minted rotate(@PathVariable("id") String id,
                         HttpServletRequest http,
                         @RequestBody(required = false) RotateRequest request,
                         HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        Authorization.Rotated rotated = authorization.rotate(context.tenant(key), hashId(id),
                request == null ? null : overlap(request.overlap()));
        context.audit(key, "credential.rotate", id + " -> " + Authorization.hash(rotated.key()));
        response.setStatus(201);
        return new Minted(Authorization.hash(rotated.key()), rotated.key(),
                rotated.expires() == null ? null : rotated.expires().toString());
    }

    /**
     * Set or clear a credential's source-IP allowlist (comma-separated CIDRs or addresses). A request from an
     * address in none of them is forbidden even with a valid key, which is what makes a leaked key survivable.
     */
    @PutMapping("/api/credentials/{id}/allowed-ips")
    public void setAllowedAddresses(@PathVariable("id") String id,
                                    HttpServletRequest http,
                                    @RequestBody(required = false) AllowedAddressesRequest request,
                                    HttpServletResponse response) throws IOException {
        String key = PresentedKey.from(http);
        authorization.setAllowedAddresses(context.tenant(key), hashId(id),
                request == null ? null : request.addresses());
        context.audit(key, "credential.allowed-ips", id);
        response.setStatus(200);
    }

    /** A blank overlap applies the default week, so a rotation without one is still downtime-free. */
    private static Duration overlap(String value) {
        return value == null || value.isBlank() ? null : Duration.parse(value.trim());
    }

    private static String hashId(String id) {
        if (id == null || !HASH.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid credential id");
        }
        return id;
    }

    /** Blank clears the expiry; a leading {@code P} is an ISO-8601 duration from now, else an absolute instant. */
    private static Instant expiry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "P", 0, 1)
                ? Instant.now().plus(Duration.parse(trimmed))
                : Instant.parse(trimmed);
    }

    private static CredentialView view(Authorization.Credential credential) {
        return new CredentialView(credential.hash(), credential.label(), text(credential.created()),
                text(credential.expires()), text(credential.lastUsed()), credential.lastUsedAddress(),
                credential.useCount(), credential.allowedAddresses(), credential.grants());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public record CredentialView(String id, String label, String created, String expires, String lastUsed,
                                 String lastUsedAddress, long useCount, String allowedAddresses,
                                 Map<String, String> grants) {
    }

    public record MintRequest(String label, String expires, Boolean nonExpiring) {
    }

    public record Minted(String id, String key, String expires) {
    }

    public record GrantRequest(String scope, List<String> tokens) {
    }

    public record ExpiryRequest(String expires) {
    }

    public record RotateRequest(String overlap) {
    }

    public record AllowedAddressesRequest(String addresses) {
    }
}
