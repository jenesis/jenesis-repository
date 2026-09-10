package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The single-tenant authority model: every signed-in user is a {@code USER}; a user whose provider-qualified id
 * ({@code github/<id>}, {@code oidc/<sub>}) holds deployment-wide administration is also an {@code ADMIN}. The
 * secure default is deny: with nobody granted it, no one is an {@code ADMIN}, so an unconfigured deployment denies
 * writes (a POST/PUT/DELETE needs {@code ROLE_ADMIN}) rather than silently granting full admin to whoever signs in.
 *
 * <p><b>It asks {@link ConsoleAdministrators}, not a setting</b>, and that is the whole of the policy here. The
 * answer is a grant in the store - seeded from {@code jenreg.ui.admins} on every boot, and equally real when it
 * was made through the API since - so this authority follows administration as an operator can actually read it
 * back, rather than following one of the three things that key used to mean.
 *
 * <p><b>There is no wildcard.</b> {@code jenreg.ui.admins=*} used to open the console to every authenticated user;
 * it is refused at startup now. An administrator is a holder of rights, and a wildcard names no holder - there is
 * nothing to read back, revoke, or show in a list of who administers this deployment. Refused rather than ignored,
 * for the reason the downstream console already gives: ignoring fails in both directions at once, since the
 * operator believes they granted something while in fact nobody holds admin.
 * A deployment that needs a richer membership model contributes its own {@link LoginAuthorities} instead of this
 * one; the seam is the same and every login mechanism goes through it either way. This deliberately stays
 * single-tenant and carries no multi-tenant machinery.
 */
public class Principals implements LoginAuthorities {

    private final ConsoleAdministrators administrators;

    public Principals(ConsoleAdministrators administrators) {
        this.administrators = administrators;
    }

    /**
     * The authorities granted to the user with this provider-qualified id. The display name is not consulted: this
     * policy decides from the id alone, and a name a user can change must never move an authority.
     */
    @Override
    public Collection<GrantedAuthority> authorities(String id, String displayName) {
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
