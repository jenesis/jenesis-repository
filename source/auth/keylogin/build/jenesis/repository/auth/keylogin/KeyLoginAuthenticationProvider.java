package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.ui.SuperadminRole;
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
 * Authenticates a pasted console login key into the same Spring Security session an OIDC or LDAP sign-in yields - a
 * {@link UsernamePasswordAuthenticationToken} with the principal's authorities - so every per-tenant rule applies
 * unchanged. Three key sources: the environment's admin key ({@code JENREPO_UI_ADMIN_KEY}, super-admin over every
 * tenant), the {@link FirstRunKey} (the same session, for its hour and until an administrator exists), and the issued
 * {@link KeyLoginKeys} (a {@code ROLE_USER} principal whose tenant role resolves per request from its membership). Keys
 * are matched only by one-way hash - the admin key in constant time - never compared in the clear or logged. Every
 * attempt is rate-limited by client address through the shared {@link RateLimiter} and every outcome audited.
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

    /** The session token: its roles, plus, for the admin key and the first-run key, the
     *  {@link StarterCredential#AUTHORITY starter-credential mark} the console's setup guide keys on. An issued key is
     *  a real identity, so it never carries it. */
    private static UsernamePasswordAuthenticationToken token(String principal, boolean superadmin, boolean starter) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (superadmin) {
            authorities.add(new SimpleGrantedAuthority(SuperadminRole.AUTHORITY));
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
