package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;

/**
 * The single-tenant answer to {@link ConsoleAccess}: a principal holds something here if it administers the
 * deployment, or if it holds a grant in the tenant this console acts in.
 *
 * <p>{@code repository:read} is the right asked for because it is the floor every console read sits on - a screen
 * of this console shows the repository's contents, so a principal that may not read the repository may not read the
 * console either. A principal with more than that passes on the same read, since {@code authorize} answers about
 * what a grant <em>allows</em> rather than what it exactly equals; a principal with a grant that does not reach
 * reading holds nothing a screen could show, which is the honest answer.
 *
 * <p>Deployment administration is checked first and separately, because an administrator of a deployment with no
 * tenant-scoped grant of their own is still an administrator - that is what {@code jenreg.ui.admins} seeds, and it
 * is the grant that lets a fresh deployment be administered at all.
 *
 * <p>An unreadable store answers by throwing rather than by allowing: a console that opened because it could not
 * read its own grants is the failure this whole seam exists to prevent.
 */
public class GrantedConsoleAccess implements ConsoleAccess {

    private final Authorization authorization;

    private final ConsoleAdministrators administrators;

    private final CurrentTenant current;

    public GrantedConsoleAccess(Authorization authorization, ConsoleAdministrators administrators,
                                CurrentTenant current) {
        this.authorization = authorization;
        this.administrators = administrators;
        this.current = current;
    }

    @Override
    public boolean holdsAnything(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.isBlank()) {
            return false;
        }
        if (administrators.is(qualifiedId)) {
            return true;
        }
        try {
            return authorization.authorize(current.name(), Authorization.Subject.principal(qualifiedId),
                    null, Authorization.REPOSITORY_READ) == Authorization.Decision.ALLOWED;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read what " + qualifiedId + " holds in this console",
                    unreadable);
        } catch (IllegalArgumentException notASubject) {
            return false;   // an id that cannot name a subject holds nothing
        }
    }
}
