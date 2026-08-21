package build.jenesis.repository.server;

import build.jenesis.repository.server.spi.Authorization;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * re-decides authorization. A minted secret is returned <em>once</em> and never again: only its hash is stored, so
 * a lost key is re-issued rather than recovered.
 */
@RestController
public final class CredentialsController {

    /** The header a caller presents its managing key in - the same one the security chain authorizes against. */
    private static final String KEY = "Jenesis-Repository-Key";

    /** A credential id is the key's hash - 64 hex characters - and nothing else may be used to address one. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Authorization authorization;

    public CredentialsController(Authorization authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    /** Every credential of the managing key's tenant, secrets excluded - they are not stored to begin with. */
    @GetMapping("/api/credentials")
    @ResponseBody
    public List<CredentialView> credentials(@RequestHeader(value = KEY, required = false) String key)
            throws IOException {
        String tenant = tenantOf(key);
        List<CredentialView> views = new ArrayList<>();
        for (String hash : authorization.credentials(tenant)) {
            Optional<Authorization.Credential> credential = authorization.credential(tenant, hash);
            credential.ifPresent(value -> views.add(view(value)));
        }
        return views;
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
    public Minted mint(@RequestHeader(value = KEY, required = false) String key,
                       @RequestBody(required = false) MintRequest request,
                       HttpServletResponse response) throws IOException {
        String tenant = tenantOf(key);
        String minted = Authorization.mint(tenant);
        String hash = Authorization.hash(minted);
        Instant expires = authorization.mintExpiry(tenant,
                request == null ? null : expiry(request.expires()),
                request != null && Boolean.TRUE.equals(request.nonExpiring()));
        authorization.provision(tenant, hash, request == null ? null : request.label(), expires);
        response.setStatus(201);
        return new Minted(hash, minted, expires == null ? null : expires.toString());
    }

    /** Grant a credential rights at a scope: a repository name, or {@code *} for every repository of the tenant. */
    @PostMapping("/api/credentials/{id}/grants")
    public void setGrant(@PathVariable("id") String id,
                         @RequestHeader(value = KEY, required = false) String key,
                         @RequestBody GrantRequest request,
                         HttpServletResponse response) throws IOException {
        authorization.setGrant(tenantOf(key), hashId(id), request.scope(), String.join(",", request.tokens()));
        response.setStatus(200);
    }

    @DeleteMapping("/api/credentials/{id}/grants/{scope}")
    public void removeGrant(@PathVariable("id") String id, @PathVariable("scope") String scope,
                            @RequestHeader(value = KEY, required = false) String key,
                            HttpServletResponse response) throws IOException {
        authorization.removeGrant(tenantOf(key), hashId(id), scope);
        response.setStatus(200);
    }

    @PutMapping("/api/credentials/{id}/expiry")
    public void setExpiry(@PathVariable("id") String id,
                          @RequestHeader(value = KEY, required = false) String key,
                          @RequestBody(required = false) ExpiryRequest request,
                          HttpServletResponse response) throws IOException {
        authorization.setExpiry(tenantOf(key), hashId(id), request == null ? null : expiry(request.expires()));
        response.setStatus(200);
    }

    @DeleteMapping("/api/credentials/{id}")
    public void revoke(@PathVariable("id") String id,
                       @RequestHeader(value = KEY, required = false) String key,
                       HttpServletResponse response) throws IOException {
        authorization.revoke(tenantOf(key), hashId(id));
        response.setStatus(200);
    }

    /** The tenant the managing key belongs to - a key carries its own, so nothing else has to be consulted. */
    private static String tenantOf(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A managing key is required to address a tenant's credentials");
        }
        return Authorization.tenantOf(key);
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
}
