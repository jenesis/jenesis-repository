package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.identity.StarterCredential;
import build.jenesis.repository.server.spi.RateLimiter;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Authenticates a pasted console login key into the same Spring Security session an OIDC or LDAP sign-in yields - a real
 * {@link UsernamePasswordAuthenticationToken} carrying the principal's authorities - so every per-tenant rule
 * ({@code TenantAuthorization} over {@code Memberships}) applies to
 * it unchanged, with no parallel authorization path. Three key sources: the env bootstrap admin key
 * ({@code JENREG_UI_ADMIN_KEY}, full super-admin over every tenant), the {@link FirstRunKey} a deployment nobody can
 * sign in to yet prints at start (the same session, for its hour and until an administrator exists), and the
 * admin-issued {@link KeyLoginKeys} (a principal signed in at {@code ROLE_USER}, its tenant role resolved per request
 * from the membership file the key was bound to). Keys are matched only by their one-way hash - the admin key in constant time, an issued key by hash lookup
 * - never compared in the clear and never logged. Every attempt is first rate-limited by client address through the
 * shared {@link RateLimiter} (brute-force protection; nothing is limited when no rate-limit module is installed) and
 * every outcome is audited.
 */
public final class KeyLoginAuthenticationProvider implements AuthenticationProvider {

    /** The session principal and audit actor of the env bootstrap admin key. */
    static final String ADMIN_PRINCIPAL = "admin";

    private final KeyLoginKeys keys;
    private final FirstRunKey firstRun;
    private final String adminKeyHash;
    private final RateLimiter rateLimiter;
    private final double permitsPerMinute;
    private final AuditTrail audit;
    private final String auditTenant;
    private final Predicate<String> superadmin;

    public KeyLoginAuthenticationProvider(KeyLoginKeys keys, FirstRunKey firstRun, String adminKey,
                                          RateLimiter rateLimiter, double permitsPerMinute, AuditTrail audit,
                                          String auditTenant, Predicate<String> superadmin) {
        this.keys = keys;
        this.firstRun = firstRun;
        this.adminKeyHash = adminKey == null || adminKey.isBlank() ? null : Authorization.hash(adminKey.trim());
        this.rateLimiter = rateLimiter;
        this.permitsPerMinute = permitsPerMinute;
        this.audit = audit;
        this.auditTenant = auditTenant;
        this.superadmin = superadmin;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String key = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        String client = clientAddress(authentication);
        if (!rateLimiter.allow("keylogin:" + client, permitsPerMinute)) {
            audit.record(auditTenant, "anonymous", "login.throttled", KeyLoginMechanism.QUALIFIER);
            throw new AuthenticationServiceException("Too many sign-in attempts; wait a moment and try again.");
        }
        if (!key.isBlank()) {
            if (isAdminKey(key)) {
                audit.record(auditTenant, ADMIN_PRINCIPAL, "login", "keylogin:admin");
                return token(ADMIN_PRINCIPAL, true, true);
            }
            if (firstRun.accepts(key)) {
                audit.record(auditTenant, ADMIN_PRINCIPAL, "login", "keylogin:first-run");
                return token(ADMIN_PRINCIPAL, true, true);
            }
            Optional<KeyLoginKeys.Resolved> resolved = keys.resolve(key);
            if (resolved.isPresent()) {
                String principal = resolved.get().principal();
                audit.record(auditTenant, principal, "login", KeyLoginMechanism.QUALIFIER);
                return token(principal, superadmin.test(principal), false);
            }
        }
        audit.record(auditTenant, "anonymous", "login.failed", KeyLoginMechanism.QUALIFIER);
        throw new BadCredentialsException("Invalid or unknown login key.");
    }

    private boolean isAdminKey(String presented) {
        return adminKeyHash != null && MessageDigest.isEqual(
                Authorization.hash(presented).getBytes(StandardCharsets.UTF_8),
                adminKeyHash.getBytes(StandardCharsets.UTF_8));
    }

    /** The session token: its roles, and for the environment's admin key and the first-run key the
     *  {@link StarterCredential#AUTHORITY starter-credential mark} the console's first-run guide keys on - a scoped
     *  login key is a real identity somebody issued, so it never carries it. */
    private static UsernamePasswordAuthenticationToken token(String principal, boolean superadmin, boolean starter) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (superadmin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
        }
        if (starter) {
            authorities.add(new SimpleGrantedAuthority(StarterCredential.AUTHORITY));
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private static String clientAddress(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null
                ? details.getRemoteAddress()
                : "unknown";
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
