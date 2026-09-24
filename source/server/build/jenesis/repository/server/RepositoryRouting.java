package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The seam that resolves an incoming request to the artifact space and format path the {@link FormatDispatcher}
 * serves it against, so one shared {@link RepositoryController} drives both the fixed-tenant deployment and a
 * multi-tenant one without a fork. Every deployment shares one store layout, {@code <tenant>/<repository>/...}:
 * a route always names its tenant and repository and always carries the doubly
 * {@link ArtifactStore#scope(String) scoped} store ({@code root.scope(tenant).scope(repository)}), so switching a
 * deployment between fixed- and multi-tenant routing is a configuration change that finds the data where it was
 * left. Which routing a deployment runs on is <strong>discovered</strong>, through
 * {@link RepositoryRoutingProvider}: {@code jenreg.tenancy} names one of the installed providers, and naming none
 * binds the {@link FixedTenantRouting}, where every request resolves to the configured
 * {@code jenreg.default-tenant}. Every routing takes the repository from the URL the same way ({@link #target}), so
 * they differ only in where the tenant comes from. A multi-tenant deployment installs a provider; it does not
 * override a bean.
 *
 * <p><strong>Where the tenant comes from is the implementation's business, not this seam's.</strong> A downstream
 * routing may read it from the {@code Jenesis-Repository-Key} header (taking the repository from the first path
 * segment), from the first path segment itself (the repository then being the second), or from the request's
 * {@code Host}. The last two matter because they let a request <em>name</em> a tenant without carrying a
 * credential, which is what separates addressing a tenant from authenticating as one - a keyless request names the
 * tenant and the deployment's anonymous rights decide what it may do, while a request that carries both a key and
 * a routing path is confined to the key's tenant. This interface deliberately says none of that: it hands back a
 * {@link Route} and every caller above it is blind to how the tenant was resolved, which is what lets one
 * {@code RepositoryController} serve all of those deployments.
 */
public interface RepositoryRouting {

    /** Resolve the request to a {@link Route}; never {@code null}. */
    Route route(HttpServletRequest request);

    /**
     * Resolve a route for a caller that has no request: a publish issued <em>in process</em>, naming its target
     * rather than carrying it in a URI.
     *
     * <p>It exists so that such a caller gets the routing's own answer - which store, and whether the target accepts
     * a write - instead of assembling a {@link Route} itself. Assembling one is the failure this is here to prevent:
     * a caller that scopes a store by hand has bypassed the writability decision, so a publish into a proxy or a
     * group view or a read-only repository would land where a request would have been refused with a {@code 405}.
     *
     * <p><b>The default answers empty, and empty means "this routing cannot say".</b> Not "no such repository" and
     * not "not writable" - a routing that resolves a tenant from the request itself (from a host name, a path
     * prefix, or a key) has nothing to resolve when there is no request, and saying so is the only honest answer.
     * A caller treats empty as a refusal to publish, never as permission: fail-closed is the direction, because the
     * alternative is a publish that skipped a check nobody can see was skipped.
     *
     * @param tenant     the tenant to publish into.
     * @param repository the repository within it.
     * @param path       the format-facing path, as {@link Route#path} would carry it.
     * @return the route, or empty when this routing cannot resolve one without a request.
     */
    default Optional<Route> route(String tenant, String repository, String path) {
        return Optional.empty();
    }

    /**
     * The document of a route's repository - the format it holds - or empty when it has none. A routing built over
     * a {@link RoutingContext} reads it through the node's cache ({@link RoutingContext#document}), so the request
     * path pays no store read for it in the steady state; the default reads it from the route's own store.
     */
    default Optional<RepositoryDocument> document(Route route) throws IOException {
        return route.repository().isEmpty() ? Optional.empty() : RepositoryDocument.read(route.store());
    }

    /**
     * The repository a request URI names, and the path within it. Every routing answers from the same shape, and
     * differs only in where it finds the tenant:
     * <ul>
     * <li>{@code /repository/<repository>/<path>} - a repository holds one format, so the URL carries no format
     *     segment, and the path within the repository is what follows its name;</li>
     * <li>{@code /v2/<repository>/<image>/...} - the OCI registry API, which every OCI client addresses at the host's
     *     root. The repository is the image name's first segment, and the path keeps it: an image is named
     *     {@code <repository>/<image>} inside its repository, so every {@code Location} the format answers with
     *     names it the way the client does;</li>
     * <li>{@code /v2} and {@code /v2/} - the registry's version probe, which names no repository: the target's
     *     repository is empty.</li>
     * </ul>
     * The path is what follows the format's {@link build.jenesis.repository.format.RepositoryFormat#mount mount}:
     * the dispatcher puts the mount back once it knows the repository's format.
     *
     * @param uri the request URI, after whatever prefix a routing reads its tenant from.
     */
    static Target target(String uri) {
        if (uri.equals("/v2") || uri.equals("/v2/")) {
            return new Target("", "/");
        }
        if (uri.startsWith("/v2/")) {
            String name = uri.substring("/v2/".length());
            int slash = name.indexOf('/');
            return new Target(slash < 0 ? name : name.substring(0, slash), "/" + name);
        }
        String rest = uri.startsWith("/repository/") ? uri.substring("/repository/".length())
                : uri.equals("/repository") ? "" : uri.startsWith("/") ? uri.substring(1) : uri;
        int slash = rest.indexOf('/');
        return new Target(slash < 0 ? rest : rest.substring(0, slash), slash < 0 ? "/" : rest.substring(slash));
    }

    /** A repository a request names and the path within it; an empty repository is the OCI registry's root. */
    record Target(String repository, String path) {
    }

    /**
     * The resolved artifact space for a request: the {@code tenant} and {@code repository} it addresses (never
     * {@code null}; the repository is empty only for the OCI registry's version probe, which names none), the
     * doubly-scoped {@code root.scope(tenant).scope(repository)} {@link ArtifactStore} the format reads and writes,
     * the {@code path} within the repository ({@link Target#path}), and whether the route is a valid write target
     * ({@link #writable}).
     *
     * <p>A {@code writable} route accepts a write (a mutating verb lays out or deletes); a non-writable one answers a
     * {@code 405} at the controller's write branch before any layout - the seam a multi-tenant routing uses to reject a
     * write to a read-only repository (a proxy or group view, or one whose router resolved no write target) without a
     * fork. The four-argument convenience constructor builds a writable route.
     */
    record Route(String tenant, String repository, ArtifactStore store, String path, boolean writable) {

        public Route {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(path, "path");
        }

        /** A writable route. A read-only one is built with the canonical five-argument constructor. */
        public Route(String tenant, String repository, ArtifactStore store, String path) {
            this(tenant, repository, store, path, true);
        }
    }
}
