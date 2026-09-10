package build.jenesis.repository.ui;

/**
 * Whether a signed-in principal holds <em>anything at all</em> in this console.
 *
 * <h2>Why this is a question the console has to ask</h2>
 * Sign-in succeeds for anyone the identity provider authenticates. That is deliberate: the provider owns who may
 * authenticate - app assignment in Entra or Okta, an OAuth app scoped to one organisation - and refusing sign-in in
 * the application duplicates that control while doing it worse, since the application only ever sees an identity
 * the provider has already decided about. It also removes a chicken-and-egg that has no good answer: refusing
 * sign-in to someone who is provisioned nowhere means an administrator frequently cannot grant access to a person
 * the deployment has never seen, because the id to grant to is an opaque {@code oidc/<sub>} nobody can type.
 *
 * <p>What that obliges is this seam. An open sign-in without it is not permissive, it is wrong: a signed-in
 * stranger would read every screen. So authorization - not authentication - is where a principal holding nothing
 * is turned away, and it is turned away to a designed screen ({@code /no-access}) rather than to a {@code 403},
 * because the honest answer is "you are signed in and hold nothing", not "something went wrong".
 *
 * <h2>The two consoles answer it differently, and that difference is the point</h2>
 * The free console is single-tenant: holding something means holding a grant in the deployment's one tenant, or
 * administering the deployment. A multi-tenant console means membership of at least one tenant. Both are the same
 * question about the same grants; only the shape of "somewhere" differs, which is exactly what a seam is for.
 *
 * <p>It answers about a principal, never about a display name: a name a person can change must not move an
 * authority.
 */
@FunctionalInterface
public interface ConsoleAccess {

    /**
     * Whether {@code qualifiedId} ({@code github/1024025}, {@code oidc/<sub>}) holds anything in this console.
     *
     * <p><b>Fail closed.</b> An implementation that cannot read the grants answers {@code false} or throws; it never
     * answers {@code true} on an unreadable store, because that is the one wrong answer that opens a console.
     */
    boolean holdsAnything(String qualifiedId);
}
