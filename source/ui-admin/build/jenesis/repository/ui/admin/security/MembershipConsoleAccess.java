package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.Superadmins;
import build.jenesis.repository.ui.ConsoleAccess;
import org.springframework.stereotype.Component;

/**
 * The multi-tenant answer to {@link ConsoleAccess}: a principal holds something here if it administers the
 * deployment, or if it is a member of at least one tenant.
 *
 * <p>This predicate is not new - it is the one {@code LoginAuthorization} used to <em>refuse sign-in</em> on. What
 * changed is which question it answers. Refusing sign-in to a principal that is provisioned nowhere duplicates a
 * control the identity provider already applies, and does it worse: the application sees an identity only after the
 * provider has decided about it. It also made granting access to a new colleague nearly impossible, because the id
 * an administrator has to grant to is an opaque {@code oidc/<sub>} that nobody can learn until its owner has signed
 * in at least once. So the same predicate now decides what a signed-in principal may reach, and a principal that
 * holds nothing gets a screen saying exactly that, with its own id on it.
 *
 * <p>Membership is read through {@link Memberships}, so it is a point read of the reverse user&rarr;tenants index
 * and it sees a revoked grant on the next request rather than at the end of a session.
 */
@Component
public class MembershipConsoleAccess implements ConsoleAccess {

    private final Memberships memberships;

    private final Superadmins superadmins;

    public MembershipConsoleAccess(Memberships memberships, Superadmins superadmins) {
        this.memberships = memberships;
        this.superadmins = superadmins;
    }

    @Override
    public boolean holdsAnything(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.isBlank()) {
            return false;
        }
        // A super-admin is a member everywhere, so it is asked first and the tenant read is skipped entirely.
        return superadmins.is(qualifiedId) || !memberships.accessibleTo(qualifiedId, false).isEmpty();
    }
}
