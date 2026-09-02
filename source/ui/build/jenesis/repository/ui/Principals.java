package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.posture.ConsoleAdmins;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The single-tenant authority model: every signed-in user is a {@code USER}; a user whose provider-qualified id
 * ({@code github/<id>}, {@code oidc/<sub>}) is in the configured {@code jenreg.ui.admins} list is also an
 * {@code ADMIN}. The secure default is deny: when no admins are configured, no one is an {@code ADMIN}, so an
 * unconfigured deployment denies writes (a POST/PUT/DELETE needs {@code ROLE_ADMIN}) rather than silently granting
 * full admin to whoever signs in - matching the downstream console. Opening the console to every authenticated user
 * (the old single-tenant convenience) is an <em>explicit opt-out</em>: list {@code *} in {@code jenreg.ui.admins}.
 * A deployment that needs a richer membership model contributes its own {@link LoginAuthorities} instead of this
 * one; the seam is the same and every login mechanism goes through it either way. This deliberately stays
 * single-tenant and carries no multi-tenant machinery.
 */
public class Principals implements LoginAuthorities {

    private final Set<String> admins;

    public Principals(UiProperties properties) {
        this.admins = ConsoleAdmins.parse(properties.getAdmins());
    }

    /**
     * The authorities granted to the user with this provider-qualified id. The display name is not consulted: this
     * policy decides from the id alone, and a name a user can change must never move an authority.
     */
    @Override
    public Collection<GrantedAuthority> authorities(String id, String displayName) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        // Deny by default: ADMIN only for a configured id (or when the deployment explicitly opts every user in with
        // the * wildcard). An empty admins list therefore grants no ADMIN, so an unconfigured console cannot be
        // written to by an arbitrary sign-in.
        if (ConsoleAdmins.grantsEveryone(admins) || admins.contains(id)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }
}
