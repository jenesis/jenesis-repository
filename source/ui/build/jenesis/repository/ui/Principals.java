package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authority model for a console serving one tenant - which is the same model as the multi-tenant one with one
 * tenant in it, not a second model. A grant is {@code (subject, tenant, scope, rights)} in both, and what differs is
 * only which tenant a request resolves to; this class used to call itself "the single-tenant authority model" while
 * granting an untenanted boolean, which is the asymmetry that made the two consoles look like two designs.
 *
 * <p>Every signed-in user is a {@code USER}; a user whose provider-qualified id
 * ({@code github/<id>}, {@code oidc/<sub>}) holds deployment-wide administration is also an {@code ADMIN}. The
 * secure default is deny: with nobody granted it, no one is an {@code ADMIN}, so an unconfigured deployment denies
 * writes (a POST/PUT/DELETE needs {@code ROLE_ADMIN}) rather than silently granting full admin to whoever signs in.
 *
 * <p>It also records the sign-in ({@link KnownPrincipals}), because this is the one moment a person's provider
 * subject is known to anything - and an administrator cannot grant to an id nobody can learn.
 *
 * <p>Administration is <em>deployment</em>-scoped rather than tenant-scoped, and that is a modelled level rather
 * than a missing one: administering a deployment is not a privilege over any tenant in it, which is what
 * {@code jenreg.ui.admins=*} was reaching for and getting wrong - a wildcard over tenants is a different claim, and
 * it names no holder. With the level named, the wildcard needs no special case beyond being refused.
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

    private final KnownPrincipals known;

    public Principals(ConsoleAdministrators administrators, KnownPrincipals known) {
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
