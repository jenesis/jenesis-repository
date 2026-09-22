package build.jenesis.repository.gateway;

import module java.base;

import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.store.ArtifactDescriptor;

/**
 * How a redirect plane module reaches the router's {@link RepositoryRouter.RedirectHandler} seam: discovered through
 * {@code ServiceLoader} at the server's router-construction site, which builds every provider's handler from the
 * deployment's own configuration, chains them (the first that does not {@link RepositoryRouter.Outcome#MISS} answers
 * the leg), injects the chain through {@link RepositoryRouter#redirecting}, and registers the parse-time
 * availability of the tokens the providers serve - {@link RepositoryDefinition#redirectHandlerInstalled(boolean)} for a
 * clause-literal {@code fallback <url> redirect}, {@link RepositoryDefinition#dnsDirectoryInstalled(boolean)} for the
 * {@code fallback dns redirect} source - so a definition parses exactly when a handler exists to serve it, and the two
 * deliberately separate steps (the flag and the instance) are always wired together.
 *
 * <p>The wiring layer owns what a handler must not: the policy floor and the withheld probe come in as the
 * {@link Context#screen()}, the SSRF and credential guards as its predicates, the download recording as its hook.
 * A provider whose plane is switched off or unconfigured answers an <em>inert</em> handler (every leg MISSes) rather
 * than none, so its token still parses and a definition written for a later enablement is not refused.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Selection policy: ALL.</b> Every provider on the module path contributes a handler; the chain is built in
 *       discovery order and the first handler that does not {@link RepositoryRouter.Outcome#MISS} answers the leg.
 *       Two providers serving different tokens each answer their own legs, and the order between them is not part
 *       of this contract.</li>
 *   <li><b>A provider always answers.</b> {@link #create} returns a handler even when the provider's plane is
 *       switched off or unconfigured - an inert one, every leg a miss - so the token it serves parses whatever the
 *       configuration says; it answers empty only when it cannot construct a handler at all.</li>
 *   <li><b>Policy comes from the context.</b> The withheld probe and the policy floor ({@link Context#screen()}),
 *       the SSRF and credential guards ({@link Context#privateHost()}, {@link Context#credentialed()}) and the
 *       download recording ({@link Context#downloads()}) are the wiring layer's; a handler consults them and never
 *       decides them.</li>
 *   <li><b>Presence is declared, not inferred.</b> {@link #servesUpstream()} and {@link #servesDnsDirectory()} say
 *       which definition tokens the provider serves, so the router's parse-time availability of a token and the
 *       handler behind it are always registered together.</li>
 * </ol>
 */
public interface RedirectHandlerProvider {

    /** Whether this provider serves a clause-literal {@code fallback <url> redirect} leg - the {@code redirect}
     *  serve token parses when any installed provider does. */
    boolean servesUpstream();

    /** Whether this provider serves a {@code fallback dns redirect} leg - the {@code dns} source keyword parses when
     *  any installed provider does. */
    boolean servesDnsDirectory();

    /** The handler for this deployment, built from {@code context}; empty when the provider cannot build one at all
     *  (a missing dependency), which is logged by the wiring and leaves the token unserved by this provider. */
    Optional<RepositoryRouter.RedirectHandler> create(Context context);

    /** The byte-free screen a redirect passes before a 307 is minted: the policy floor over the parsed coordinate
     *  and the withheld probe over the path - the same answers the fetch-screen-serve walk would reach. */
    @FunctionalInterface
    interface Screen {
        boolean admits(String tenant, String repository, ArtifactDescriptor descriptor, String path) throws IOException;
    }

    /** Best-effort accounting of a minted redirect as a download, so retention does not starve a forwarded
     *  coordinate; a no-op where nothing tracks downloads. */
    @FunctionalInterface
    interface Downloads {
        void record(String tenant, String repository, ArtifactDescriptor descriptor);

        Downloads NONE = (tenant, repository, descriptor) -> { };
    }

    /** What the wiring layer hands every provider. */
    /**
     * A redirect target composed with the request's own path: {@code target ∘ residual-path}, reconciling a trailing
     * slash on the target with a leading slash on the path, so the fleet member serves the same layout the client
     * asked this repository for.
     *
     * <p>It lives here because both planes reach it. It was two private statics with identical bodies - one in the
     * static filter, one in the DNS handler - and the second's javadoc named the first as the rule it was following,
     * which is a rule restated in prose beside a copy of its implementation. They agreed; the point is that nothing
     * made them, and a pluggable layout turns them into two call sites that must resolve the same layout, where
     * fixing one diverges the other in silence.
     */
    static URI compose(URI target, String path) {
        String base = target.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + suffix);
    }

    record Context(UnaryOperator<String> config, Screen screen, Predicate<URI> privateHost,
                   Predicate<URI> credentialed, Downloads downloads) {

        public Context {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(screen, "screen");
            Objects.requireNonNull(privateHost, "privateHost");
            Objects.requireNonNull(credentialed, "credentialed");
            Objects.requireNonNull(downloads, "downloads");
        }
    }

    /** Every installed provider, in discovery order. */
    static List<RedirectHandlerProvider> installed() {
        List<RedirectHandlerProvider> providers = new ArrayList<>();
        for (RedirectHandlerProvider provider : ServiceLoader.load(RedirectHandlerProvider.class)) {
            providers.add(provider);
        }
        return providers;
    }

    /** The chain of {@code handlers}: the first whose answer is not {@link RepositoryRouter.Outcome#MISS} serves the
     *  leg; a leg every handler misses falls through as a miss. */
    static RepositoryRouter.RedirectHandler chain(List<RepositoryRouter.RedirectHandler> handlers) {
        List<RepositoryRouter.RedirectHandler> copy = List.copyOf(handlers);
        return (tenant, repository, fallback, upstream, format, exchange) -> {
            for (RepositoryRouter.RedirectHandler handler : copy) {
                RepositoryRouter.Outcome outcome = handler.redirect(tenant, repository, fallback, upstream, format,
                        exchange);
                if (outcome != RepositoryRouter.Outcome.MISS) {
                    return outcome;
                }
            }
            return RepositoryRouter.Outcome.MISS;
        };
    }
}
