package build.jenesis.repository.auth.ldap;

import module java.base;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;

/**
 * The directory over Spring Security's LDAP support: a bind as the person checks their password - Spring escapes the
 * typed name into the DN pattern and the search filter, and refuses an empty password rather than binding
 * anonymously - and a search for the groups whose members name them.
 */
final class SpringLdapDirectory implements Directory {

    private final LdapProperties properties;
    private final BindAuthenticator authenticator;
    private final SpringSecurityLdapTemplate template;

    SpringLdapDirectory(LdapProperties properties) {
        this.properties = properties;
        DefaultSpringSecurityContextSource source = new DefaultSpringSecurityContextSource(properties.getUrl().trim());
        if (!properties.getBindDn().isBlank()) {
            source.setUserDn(properties.getBindDn());
            source.setPassword(properties.getBindPassword());
        }
        if (properties.isStartTls()) {
            source.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy());
        }
        source.afterPropertiesSet();
        authenticator = new BindAuthenticator(source);
        if (!properties.getUserDnPattern().isBlank()) {
            authenticator.setUserDnPatterns(new String[]{properties.getUserDnPattern()});
        }
        if (!properties.getUserSearchBase().isBlank()) {
            authenticator.setUserSearch(new FilterBasedLdapUserSearch(properties.getUserSearchBase(),
                    properties.getUserSearchFilter(), source));
        }
        try {
            authenticator.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("The LDAP sign-in is misconfigured: " + e.getMessage(), e);
        }
        template = new SpringSecurityLdapTemplate(source);
    }

    @Override
    public Optional<Account> authenticate(String username, String password) {
        DirContextOperations user;
        try {
            user = authenticator.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (BadCredentialsException | UsernameNotFoundException _) {
            return Optional.empty();
        }
        Set<String> groups = properties.getGroupSearchBase().isBlank()
                ? Set.of()
                : template.searchForSingleAttributeValues(properties.getGroupSearchBase(),
                        properties.getGroupSearchFilter(), new String[]{user.getNameInNamespace(), username},
                        properties.getGroupNameAttribute());
        return Optional.of(new Account(username, groups));
    }
}
