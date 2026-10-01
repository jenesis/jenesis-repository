package build.jenesis.repository.ui;

/**
 * Whether a signed-in principal holds anything at all in this console. Sign-in succeeds for anyone the identity
 * provider authenticates, since the provider owns who may authenticate and an administrator can only grant to an id
 * the deployment has seen; so authorization turns away a principal holding nothing, to the designed
 * {@code /no-access} screen rather than a {@code 403}.
 *
 * <p>A single-tenant console answers from its one tenant's grants or deployment administration, a multi-tenant one
 * from membership of any tenant. It answers about a principal, never a display name.
 */
@FunctionalInterface
public interface ConsoleAccess {

    /**
     * Whether {@code qualifiedId} ({@code github/1024025}, {@code oidc/<sub>}) holds anything in this console. Fails
     * closed: an unreadable store answers {@code false} or throws, never {@code true}.
     */
    boolean holdsAnything(String qualifiedId);
}
