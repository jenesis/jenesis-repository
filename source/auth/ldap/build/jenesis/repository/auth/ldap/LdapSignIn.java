package build.jenesis.repository.auth.ldap;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.ui.LoginAuthorities;
import build.jenesis.repository.ui.ProviderPrincipal;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * A directory sign-in: the name and password are checked by the {@link Directory}, the person is named
 * {@code ldap/<name>}, their directory groups are reconciled into each configured tenant's group memberships under
 * the source {@code ldap}, and their console authorities come from the shared {@link LoginAuthorities} policy - with
 * super-admin added when they are in the configured administrators group.
 *
 * <p>The name is compared case-insensitively, the way directories compare it, so one person signing in as
 * {@code Alice} and as {@code alice} is one member rather than two. Attempts are rate-limited per client address and
 * every outcome is audited as the key sign-in's are, so one query over the trail answers for both.
 */
public final class LdapSignIn implements AuthenticationProvider {

    /** The source a directory's memberships are recorded under, so a reconcile never touches an operator's own. */
    static final String SOURCE = "ldap";

    private final Directory directory;
    private final LdapProperties properties;
    private final Authorization authorization;
    private final LoginAuthorities authorities;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;

    public LdapSignIn(Directory directory, LdapProperties properties, Authorization authorization,
               LoginAuthorities authorities, RateLimiter rateLimiter, AuditTrail audit) {
        this.directory = directory;
        this.properties = properties;
        this.authorization = authorization;
        this.authorities = authorities;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Override
    public Authentication authenticate(Authentication presented) {
        String typed = presented.getName() == null ? "" : presented.getName().trim();
        String password = presented.getCredentials() == null ? "" : presented.getCredentials().toString();
        String tenant = properties.tenantList().isEmpty() ? "default" : properties.tenantList().getFirst();
        if (!rateLimiter.allow("ldap:" + client(presented), properties.getRateLimit())) {
            audit.record(tenant, "anonymous", "login.throttled", LdapLoginMechanism.NAME);
            throw new BadCredentialsException("Too many sign-in attempts");
        }
        if (typed.isEmpty() || password.isEmpty()) {
            throw new BadCredentialsException("A name and a password are required");
        }
        Optional<Directory.Account> account = directory.authenticate(typed, password);
        String username = typed.toLowerCase(Locale.ROOT);
        String id = ProviderPrincipal.qualifiedId(LdapLoginMechanism.NAME, username);
        if (account.isEmpty()) {
            audit.record(tenant, id, "login.failed", LdapLoginMechanism.NAME);
            throw new BadCredentialsException("The directory did not accept that name and password");
        }
        Set<String> groups = account.get().groups();
        try {
            for (String each : properties.tenantList()) {
                authorization.reconcileMembership(each, id, groups, SOURCE);
            }
        } catch (IOException e) {
            throw new AuthenticationServiceException("Could not record the directory groups of " + id, e);
        }
        List<GrantedAuthority> granted = new ArrayList<>(authorities.authorities(id, username));
        if (!properties.getAdminGroup().isBlank() && groups.contains(properties.getAdminGroup().trim())
                && granted.stream().noneMatch(held -> "ROLE_SUPERADMIN".equals(held.getAuthority()))) {
            granted.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
        }
        audit.record(tenant, id, "login", LdapLoginMechanism.NAME);
        return UsernamePasswordAuthenticationToken.authenticated(id, null, granted);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String client(Authentication presented) {
        return presented.getDetails() instanceof WebAuthenticationDetails details && details.getRemoteAddress() != null
                ? details.getRemoteAddress()
                : "unknown";
    }
}
