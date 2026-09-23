package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.LoginAuthorities;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The single-tenant authority policy, kept as a collaborator for the principal-pipeline tests.
 *
 * <p>It used to be the console's own {@code LoginAuthorities}: sign-in grants {@code ROLE_USER}, and
 * {@code ROLE_ADMIN} to a holder of the grant {@link ConsoleAdministrators} reads, with every sign-in recorded
 * into {@link KnownPrincipals} because that is the one moment a person's opaque provider subject is known. The
 * console that ships decides authorities from membership instead, through the identity module, and nothing
 * constructed this one any more once the shell's own wiring went.
 *
 * <p>It lives here rather than being deleted because the claims it supports are about the <em>principal
 * services</em>, not about this policy: which provider-qualified id each service asks the authority seam about,
 * and that the seam records what it was asked. A realistic policy makes those readable; a mock would assert the
 * same thing less legibly. The admin-list behaviour itself is no longer asserted, because it is no longer
 * shipped - {@code jenreg.ui.admins} reaches the console through {@code Superadmins} now.
 */
public class AdminListAuthorities implements LoginAuthorities {

    private final ConsoleAdministrators administrators;

    private final KnownPrincipals known;

    public AdminListAuthorities(ConsoleAdministrators administrators, KnownPrincipals known) {
        this.administrators = administrators;
        this.known = known;
    }

    /**
     * The authorities granted to the user with this provider-qualified id. The display name is not consulted: this
     * policy decides from the id alone, and a name a user can change must never move an authority.
     */
    @Override
    public Collection<GrantedAuthority> authorities(String id, String displayName) {
        // Every sign-in passes through here, which is what makes it the place to note that this deployment has now
        // seen this person - the only moment their opaque provider subject is known to anything.
        known.record(id, displayName);
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        // Deny by default: ADMIN only for someone who holds the grant. An unconfigured console has granted it to
        // nobody, so it cannot be written to by an arbitrary sign-in - and an administrator granted through the API
        // since boot holds it too, which reading the setting could never see.
        if (administrators.is(id)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }
}
