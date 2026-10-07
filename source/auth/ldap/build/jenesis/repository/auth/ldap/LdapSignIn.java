package build.jenesis.repository.auth.ldap;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.ui.SuperadminRole;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(LdapSignIn.class);

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
        String username = typed.toLowerCase(Locale.ROOT);
        String id = ProviderPrincipal.qualifiedId(LdapLoginMechanism.NAME, username);
        Optional<Directory.Account> account;
        try {
            account = directory.authenticate(typed, password);
        } catch (Directory.Unreachable unreachable) {
            // Not the person's failure, so not a refused password: the sign-in page says the directory could not be
            // asked, and the log says why in words an operator can act on.
            LOGGER.warn("{} The sign-in of {} could not be decided. For an ldaps:// directory, check that this node "
                    + "trusts the directory's certificate (the JVM's javax.net.ssl.trustStore) and that the name in the "
                    + "URL is the one the certificate is issued to.", unreachable.getMessage(), id);
            audit.record(tenant, id, "login.failed", LdapLoginMechanism.NAME);
            throw new AuthenticationServiceException("The directory could not be reached", unreachable);
        }
        if (account.isEmpty()) {
            audit.record(tenant, id, "login.failed", LdapLoginMechanism.NAME);
            throw new BadCredentialsException("The directory did not accept that name and password");
        }
        Set<String> groups = account.get().groups();
        try {
            for (String each : properties.tenantList()) {
                authorization.groups().reconcile(each, id, groups, SOURCE);
            }
        } catch (IOException e) {
            throw new AuthenticationServiceException("Could not record the directory groups of " + id, e);
        }
        List<GrantedAuthority> granted = new ArrayList<>(authorities.authorities(id, username));
        if (!properties.getAdminGroup().isBlank() && groups.contains(properties.getAdminGroup().trim())
                && granted.stream().noneMatch(held -> SuperadminRole.AUTHORITY.equals(held.getAuthority()))) {
            granted.add(new SimpleGrantedAuthority(SuperadminRole.AUTHORITY));
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
