package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;

import module java.base;

import module java.base;

/**
 * The seam that resolves an incoming request to the artifact space and format path the {@link FormatDispatcher}
 * serves it against, so one shared {@link RepositoryController} drives both the fixed-tenant deployment and a
 * multi-tenant one without a fork. Every deployment shares one store layout, {@code <tenant>/<repository>/...}:
 * a route always names its tenant and repository and always carries the doubly
 * {@link ArtifactStore#scope(String) scoped} store ({@code root.scope(tenant).scope(repository)}), so switching a
 * deployment between fixed- and multi-tenant routing is a configuration change that finds the data where it was
 * left. By default the {@link FixedTenantRouting} binds: every request resolves to the configured
 * {@code jenreg.tenant} / {@code jenreg.repository} space (each {@code default} by
 * default) with the request path unchanged beyond the {@code /repository} prefix strip. A multi-tenant deployment
 * contributes its own {@code RepositoryRouting} bean (overriding the {@code @ConditionalOnMissingBean} default).
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
     * The resolved artifact space for a request: the {@code tenant} and {@code repository} it addresses (never
     * {@code null} - the fixed-tenant deployment resolves its configured defaults), the doubly-scoped
     * {@code root.scope(tenant).scope(repository)} {@link ArtifactStore} the format reads and writes, the
     * {@code path} the format matches on (the request URI with the {@code /repository} prefix stripped on the
     * fixed-tenant deployment, the repository-prefix-stripped path under multi-tenant routing), and whether the route
     * is a valid write target ({@link #writable}).
     *
     * <p>A {@code writable} route accepts a write (a mutating verb lays out or deletes); a non-writable one answers a
     * {@code 405} at the controller's write branch before any layout - the seam a multi-tenant routing uses to reject a
     * write to a read-only repository (a proxy or group view, or one whose router resolved no write target) without a
     * fork. The fixed-tenant deployment always resolves a writable route, so the core is unchanged; the
     * four-argument convenience constructor keeps that always-writable default for every existing call site.
     */
    record Route(String tenant, String repository, ArtifactStore store, String path, boolean writable) {

        public Route {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(path, "path");
        }

        /** A writable route - the always-writable default the fixed-tenant deployment resolves, and the shape every
         *  existing call site builds. A read-only route is built with the canonical five-argument constructor. */
        public Route(String tenant, String repository, ArtifactStore store, String path) {
            this(tenant, repository, store, path, true);
        }
    }
}
