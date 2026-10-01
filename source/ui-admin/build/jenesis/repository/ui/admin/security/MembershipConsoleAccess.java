package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.Superadmins;
import build.jenesis.repository.ui.ConsoleAccess;
import org.springframework.stereotype.Component;

/**
 * The multi-tenant answer to {@link ConsoleAccess}: a principal holds something if it administers the deployment or
 * is a member of any tenant, read through {@link Memberships} per request, so a revoked grant counts on the next
 * request.
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
        // A super-admin is a member everywhere, so no tenant read is needed.
        return superadmins.is(qualifiedId) || !memberships.accessibleTo(qualifiedId, false).isEmpty();
    }
}
