package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;

/**
 * The single-tenant answer to {@link ConsoleAccess}: a principal holds something if it administers the deployment
 * (checked first, as {@code jenrepo.ui.admins} seeds it with no tenant grant), or may {@code repository:read} in the
 * tenant this console acts in, the floor every console screen sits on. An unreadable store throws rather than
 * allowing.
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
