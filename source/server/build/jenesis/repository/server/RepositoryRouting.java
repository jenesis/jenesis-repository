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
 * binds the {@link FixedTenantRouting}, which serves the one configured tenant, {@code jenreg.default-tenant}.
 * A multi-tenant deployment installs a provider; it does not override a bean.
 *
 * <p><strong>The URL always names the tenant, and a routing decides only which tenants a request may
 * address.</strong> Every routing reads the same URL ({@link #target}), so switching a deployment's tenancy never
 * moves a URL a client already has. The fixed routing answers its one tenant and nothing else; a downstream routing
 * may let the URL choose, confine a request to the tenant its {@code Jenesis-Repository-Key} names, or to the tenant
 * its {@code Host} maps to. This interface deliberately says none of that: it hands back a {@link Route} and every
 * caller above it is blind to how the tenant was decided, which is what lets one {@code RepositoryController} serve
 * all of those deployments.
 */
public interface RepositoryRouting {

    /**
     * Resolve the request to a {@link Route}; never {@code null}. A request the routing refuses - a name that is not
     * routable ({@code 400}), a tenant the request may not address ({@code 404}, or {@code 403} for a credential
     * naming another) - throws the refusal as a {@code ResponseStatusException}.
     */
    Route route(HttpServletRequest request);

    /**
     * The tenant a request that addresses none answers for - an {@code /api} call, which names its repository in a
     * parameter rather than in the URL. It is the tenant an addressed request naming no tenant would route to: the
     * fixed routing's one tenant, the key's under a key-confined routing, the host's under a host routing. The default
     * asks {@link #route}, which is right for a routing that ignores the URL; a routing that reads the tenant from the
     * URL answers without it.
     */
    default String tenant(HttpServletRequest request) {
        return route(request).tenant();
    }

    /**
     * The route for a request, or empty when the routing refuses it - for a caller ahead of the dispatcher, a servlet
     * filter or the security chain, where a {@code ResponseStatusException} would surface as a {@code 500} rather
     * than its status. Such a caller lets the request through untouched, and the controller, which routes it again,
     * answers the refusal with its own status.
     */
    default Optional<Route> resolve(HttpServletRequest request) {
        try {
            return Optional.of(route(request));
        } catch (RuntimeException refused) {
            return Optional.empty();
        }
    }

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
     * @param path       the path within the repository, as {@link Route#path} would carry it.
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
     * The tenant and repository a request URI names, and the path within the repository. Every routing reads the URL
     * the same way, whatever its tenancy: the tenant is always the URL's first segment, so a deployment switched from
     * one tenancy to another answers every URL it answered before, and the routing decides only which tenants a
     * request may address.
     * <ul>
     * <li>{@code /repository/<tenant>/<repository>/<path>} - a repository holds one format, so the URL carries no
     *     format segment, and the path within the repository is what follows its name;</li>
     * <li>{@code /v2/<tenant>/<repository>/<image>/...} - the OCI registry API, which every OCI client addresses at
     *     the host's root. The tenant and repository are the image name's first two segments and the path within the
     *     repository is the rest, so an image is named inside its repository without either - an image imported as
     *     {@code acme/app} serves at {@code /v2/<tenant>/<repository>/acme/app} - and every {@code Location} the
     *     format answers with goes back through the exchange, which puts both back;</li>
     * <li>{@code /v2} and {@code /v2/} - the registry's version probe, which names neither: the target's tenant and
     *     repository are empty.</li>
     * </ul>
     * A URL that names a tenant and no repository has an empty repository. The path is what follows the format's
     * {@link build.jenesis.repository.format.RepositoryFormat#mount mount}: the dispatcher puts the mount back once it
     * knows the repository's format.
     */
    static Target target(String uri) {
        String rest;
        if (uri.equals("/v2") || uri.equals("/v2/")) {
            return new Target("", "", "/");
        } else if (uri.startsWith("/v2/")) {
            rest = uri.substring("/v2/".length());
        } else if (uri.startsWith("/repository/")) {
            rest = uri.substring("/repository/".length());
        } else {
            rest = uri.equals("/repository") ? "" : uri.startsWith("/") ? uri.substring(1) : uri;
        }
        int slash = rest.indexOf('/');
        String tenant = slash < 0 ? rest : rest.substring(0, slash);
        String within = slash < 0 ? "" : rest.substring(slash + 1);
        slash = within.indexOf('/');
        return new Target(tenant, slash < 0 ? within : within.substring(0, slash),
                slash < 0 ? "/" : within.substring(slash));
    }

    /**
     * The tenant and repository a request names and the path within the repository. An empty tenant is the OCI
     * registry's version probe; an empty repository names none, which only that probe answers.
     */
    record Target(String tenant, String repository, String path) {
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
