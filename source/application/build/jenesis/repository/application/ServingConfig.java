package build.jenesis.repository.application;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.server.kernel.AuthFetcher;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.LiveUpstreams;
import build.jenesis.repository.server.kernel.PublishTenantFilter;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.BatchIngestion;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.RoutedServing;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.server.RepositoryRoutingProvider;
import build.jenesis.repository.server.kernel.RepositoriesRoutingContext;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.gateway.ProxyScreenHooks;
import build.jenesis.repository.gateway.HardenedScreen;
import build.jenesis.repository.gateway.DeployEdgeHooks;
import build.jenesis.repository.gateway.LiveDefinitions;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.gateway.RedirectHandlerProvider;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.settings.PrivateHostGuard;
import org.springframework.beans.factory.ObjectProvider;
import build.jenesis.repository.gateway.SpoolStore;
import io.micrometer.observation.ObservationRegistry;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The routing / serving / proxy-dispatch wiring split out of {@link RepositoryConfig}: the upstream
 * credential source and fetcher, the {@link RepositoryRouter} and its {@link RoutedServing} read side, the tenancy
 * routing, the live {@link FormatDispatcher}, batch ingestion, the free {@code RepositoryController} serving/writing
 * bean with the enterprise {@link DeployEdgeHooks} and {@link PublishTenantFilter} plugged in. Every bean is copied
 * verbatim from the former monolith; the split is behaviour-preserving.
 *
 * <p><b>import edge (no bean override).</b> The former {@code WebMvcRegistrations} mapping-suppression stopgap
 * that dropped the free controller's import handlers is retired. In 0.8.0 those handlers moved out of
 * {@code RepositoryController} into the free {@code ImportEdgeController}, a bean conditionally registered by
 * {@code FreeImportEdgeCondition} only when no {@code ImportEdgeProvider} is installed. The enterprise now installs
 * {@link RoutedImportEdge} through that SPI (a hook, not a cross-layer bean override), so the server's own
 * import edge is never created and the enterprise {@link ImportController} - the tenant-scoped
 * {@code /repository/<repo>/admin/import} with its {@code AuditTrail}, tenant-routed store and screening/SSRF
 * choreography - is the sole import edge, with no {@code /repository/admin/import} free literal left to shadow it
 * and no mapping override.
 */
@Configuration(proxyBeanMethods = false)
public class ServingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServingConfig.class);

    @Bean
    public UpstreamCredentialSource upstreamCredentials(ArtifactStore store, Environment environment) {
        // Where upstream credentials live is a discovered plugin (the store-backed source); NONE when absent -
        // no credential is ever attached and the management endpoints answer 501.
        return UpstreamCredentialSourceProvider.resolve(store,
                Features.namespaced(environment::getProperty));
    }

    @Bean
    public ProxyFormat.Fetcher upstreamFetcher(UpstreamCredentialSource upstreamCredentials, Environment environment) {
        // The upstream fetcher is a discovered plugin (the free http module, with revalidation and negative
        // caching composed by its own proxy-miss-ttl key); NONE when absent - the router then serves local
        // content only and imports answer 501. The auth decorator adds per-host upstream credentials around
        // whatever resolves.
        ProxyFormat.Fetcher resolved = FetcherProvider.resolve(
                Features.namespaced(environment::getProperty));
        return resolved == ProxyFormat.Fetcher.NONE || upstreamCredentials == UpstreamCredentialSource.NONE
                ? resolved
                : new AuthFetcher(resolved, upstreamCredentials);
    }

    @Bean
    public RepositoryRouter repositoryRouter(LiveDefinitions definitions, LiveConfig liveConfig, Repositories repositories,
                                             ProxyFormat.Fetcher upstreamFetcher, Environment environment,
                                             ObjectProvider<UpstreamCredentialSource> credentials,
                                             ObjectProvider<DownloadTracker> downloads) {
        // liveConfig::proxyGate binds the tenant-aware proxyGate(String) overload, so a routed proxy fetch is screened
        // by the serving tenant's own gate; liveConfig::holdDays stays the deployment-wide immaturity window.
        // The pre-verdict spool store is sized from jenreg.spool.* (max-bytes, max-spools) and
        // installed as the live observability source so its bounded budget gauges surface; the nocache pass-through
        // leg spools each untrusted upstream body through it and refuses with 503 when a budget is exhausted, rather
        // than growing unbounded.
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        SpoolStore spool = SpoolStore.fromConfig(config);
        SpoolStore.install(spool);
        // The hardened leg's untrusted-upstream fetch bounds are sized from jenreg.spool.*
        // (max-artifact-bytes, fetch-timeout-millis, fetch-min-throughput-bytes) - deploy-time resource dials sized to
        // the node's disk and links, like the spool budget - so an oversize or slow-loris upstream is refused rather
        // than exhausting the spool or pinning a connection. They are read against THIS spool's budget: the
        // per-artifact ceiling is only reachable at or below the shared in-flight budget the body is spooled through,
        // so a configured ceiling above it fails the boot here rather than never firing.
        HardenedScreen.Bounds hardeningBounds = HardenedScreen.Bounds.fromConfig(config, spool.budget());
        // EPIC 25 §4.2: the read-side withheld guard is the free core's discovered PublishInterceptor chain (the
        // staging withhold) plus the /quarantine review pointer itself, consumed read-only. A local 404 over a path
        // the gate has retracted is then a REFUSED that ends the walk rather than a MISS that falls through to a
        // weaker fallback - the closure for locally-withheld content. The review pointer is read HERE rather than by
        // the gate's screen because this is the miss path: the serve reads the hold off the serving pointer's own
        // flag, and a path with no serving pointer (a fresh quarantine) is the one case the flag cannot answer, so
        // the guard asks the queue directly - one read, only after a local 404, never on a hit.
        List<PublishInterceptor> interceptors = PublishInterceptor.installed();
        // The one whole-chain probe. This edge propagates "could not determine" rather than answering it, so a
        // store outage surfaces as a failed read instead of as a serve.
        RepositoryRouter.WithheldGuard withheld = (path, store) ->
                PublishInterceptor.withheldByAny(path, store, interceptors) || Publication.reviewPending(store, path);
        RepositoryRouter router = new RepositoryRouter(definitions::definition, repositories::store, upstreamFetcher)
                .gating(liveConfig::gate, liveConfig::holdDays)
                .passingThrough(spool::acquire)
                .hardening(hardeningBounds)
                .withholding(withheld);
        return redirecting(router, config, liveConfig, repositories, withheld,
                credentials.getIfAvailable(() -> UpstreamCredentialSource.NONE), downloads.getIfAvailable());
    }

    /**
     * The router-construction site of the redirect serve path (RD-5 / EPIC 30): every installed
     * {@link RedirectHandlerProvider} - the {@code redirect-directory} module's clause-literal handler, the
     * {@code redirect-dns} module's DNS-directory handler - is built from this deployment's configuration and the
     * guards this layer owns (the policy floor and withheld probe as the screen, the SSRF and credential guards, the
     * download accounting), chained, and injected; and the parse-time availability of the tokens they serve is
     * registered in the same step, so a {@code fallback <url> redirect} or {@code fallback dns redirect} definition
     * parses exactly when a handler exists to serve it. With no provider installed nothing changes: both tokens stay
     * a fail-loud parse refusal and the router keeps its absent-handler sentinel.
     */
    /** The parse-time half alone, for the boot-time definition sweep that runs before the router exists: a
     *  {@code redirect} or {@code dns} token parses exactly when an installed provider serves it. The router
     *  construction registers the same answer again beside the handlers it injects. */
    static void registerRedirectTokens() {
        boolean upstream = false;
        boolean dns = false;
        for (RedirectHandlerProvider provider : RedirectHandlerProvider.installed()) {
            upstream |= provider.servesUpstream();
            dns |= provider.servesDnsDirectory();
        }
        RepositoryDefinition.redirectHandlerInstalled(upstream);
        RepositoryDefinition.dnsDirectoryInstalled(dns);
    }

    static RepositoryRouter redirecting(RepositoryRouter router, UnaryOperator<String> config, LiveConfig liveConfig,
                                        Repositories repositories, RepositoryRouter.WithheldGuard withheld,
                                        UpstreamCredentialSource credentialSource, DownloadTracker downloadTracker) {
        List<RedirectHandlerProvider> providers = RedirectHandlerProvider.installed();
        boolean upstream = false;
        boolean dns = false;
        List<RepositoryRouter.RedirectHandler> handlers = new ArrayList<>();
        if (!providers.isEmpty()) {
            RedirectHandlerProvider.Screen screen = (tenant, repository, descriptor, path) ->
                    !withheld.withheld(path, repositories.store(tenant, repository))
                            && liveConfig.proxyGate(tenant).assessUnclaimed(new ComplianceGate.Subject(
                                    descriptor.ecosystem(), descriptor.coordinate(), descriptor.version(), List.of()))
                            .allowed();
            Predicate<URI> credentialed = target -> !credentialSource.headers(target).isEmpty()
                    || (target.getUserInfo() != null && !target.getUserInfo().isBlank());
            RedirectHandlerProvider.Downloads recording = downloadTracker == null || !downloadTracker.enabled()
                    ? RedirectHandlerProvider.Downloads.NONE
                    : (tenant, repository, descriptor) -> downloadTracker.record(new DownloadTracker.Hit(tenant,
                            repository, descriptor.ecosystem(), descriptor.coordinate(), descriptor.version()));
            // The serve-time SSRF guard honours the same proxy-allow-internal dial the definition sweep does, so an
            // operator who admits an internal upstream for pull-through admits it as a redirect target too.
            Predicate<URI> privateHost = target -> !liveConfig.proxyAllowInternal() && PrivateHostGuard.internal(target);
            RedirectHandlerProvider.Context context = new RedirectHandlerProvider.Context(config, screen,
                    privateHost, credentialed, recording);
            for (RedirectHandlerProvider provider : providers) {
                Optional<RepositoryRouter.RedirectHandler> handler = provider.create(context);
                if (handler.isEmpty()) {
                    LOGGER.warn("redirect handler provider {} built no handler; the legs it would serve are not served",
                            provider.getClass().getName());
                    continue;
                }
                handlers.add(handler.get());
                upstream |= provider.servesUpstream();
                dns |= provider.servesDnsDirectory();
            }
        }
        RepositoryDefinition.redirectHandlerInstalled(upstream);
        RepositoryDefinition.dnsDirectoryInstalled(dns);
        if (handlers.isEmpty()) {
            return router;
        }
        LOGGER.info("redirect serve path wired: {} handler(s); 'fallback <url> redirect' {}, 'fallback dns redirect' {}",
                handlers.size(), upstream ? "parses" : "is refused", dns ? "parses" : "is refused");
        return router.redirecting(RedirectHandlerProvider.chain(handlers));
    }

    @Bean
    public RoutedServing routedServing(RepositoryRouter repositoryRouter) {
        // The read side of the router: the free serving controller consults this on a GET/HEAD, so a routed
        // repository (a per-repository proxy of an upstream, or a group view over members) serves across its
        // backings behind the free path - retiring the interim where a routed read served only its own hosted
        // space. A repository with no definition declines (routes() == false), leaving the free FormatDispatcher to
        // dispatch it over its own store with the deployment-wide format-level pull-through intact. The router is the
        // gating() one, so a routed proxy fetch is screened by the same compliance gate a direct proxy is, and the
        // withheld() read guard still bites on the hosted/group legs through each format's own handle.
        return new RoutedServing() {
            @Override
            public boolean routes(String repository) {
                return repositoryRouter.definition(repository) != null;
            }

            @Override
            public void serve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange)
                    throws IOException {
                repositoryRouter.serve(tenant, repository, format, exchange);
            }
        };
    }

    /**
     * The routing this deployment runs on, discovered rather than chosen here.
     *
     * <p>This used to be an {@code if}-chain over {@code jenreg.tenancy} naming the four routings by constructor,
     * which made tenancy a composition choice: the setting was real, the seam was not, and a fifth routing could
     * only arrive by editing the method that names the other four. It resolves through
     * {@link RepositoryRoutingProvider} now - the same {@code EXCLUSIVE_WITH_DEFAULT} discovery the artifact store
     * uses - so what this method still owns is the one thing only the application knows: which store and which
     * repository view a routing's questions are answered from, which is
     * {@link RepositoriesRoutingContext}.
     *
     * <p>A name no installed provider answers to fails here, at boot, rather than falling back. Routing to the
     * wrong tenant is not a degraded service, it is the wrong data - a deployment that asked for host routing and
     * silently got fixed would serve every tenant's request out of one space and find nothing wrong with it.
     */
    @Bean
    public RepositoryRouting repositoryRouting(ArtifactStore store, Repositories repositories,
                                               RepositoryProperties properties, Environment environment) {
        return RepositoryRoutingProvider.resolve(properties.getTenancy(),
                new RepositoriesRoutingContext(store, repositories, properties.getDefaultTenant(),
                        properties.getDefaultRepository(), Features.namespaced(environment::getProperty)));
    }

    /** Every discovered format that the {@link Features} convention leaves enabled - one image carries every
     *  format module and {@code jenreg.<format>=false} trims it at boot, degrading exactly like an
     *  absent module. (The free core applies the same gate inside its own auto-configuration; this shell builds its
     *  format list itself, so it applies the convention at its own discovery sites.) */
    static List<RepositoryFormat> enabledFormats(Environment environment) {
        return RepositoryFormat.installed(Features.namespaced(environment::getProperty));
    }

    @Bean
    public FormatDispatcher formatDispatcher(LiveConfig liveConfig, ProxyFormat.Fetcher upstreamFetcher,
                                             ObservationRegistry observations, Environment environment) {
        // The upstream table is a live view over the runtime settings (format-upstream.<format> or the format's
        // own declared default, nothing when the proxy switch is off), so a settings change applies on the next
        // fetch where the free core's boot-time map could not. The observation registry rides in so a proxied miss
        // is timed and traced as jenreg.proxy.fetch (format, outcome) through the free PullThroughCache.
        List<RepositoryFormat> formats = enabledFormats(environment);
        // ... and screened. This is the leg every repository WITHOUT a router definition uses, the deployment-wide
        // jenreg.proxy.<format> upstream map among them, and it delegated with PullThroughHooks.NONE - so no quality
        // inspector, no licence or advisory dimension, no operator deny-list and no quarantine ran on it. A format's
        // proxy() caches the fetched body through Publication.storeBlob + link, and neither runs the
        // PublishInterceptor chain (only Publication.commit does), so there was no gate anywhere on this path
        // (D-208). The routed gateway's legs were screened and the demo seeder's was; this one was not, which is the
        // reachability shape: both contracts held and the wiring between them was the hole.
        //
        // The DEPLOYMENT-WIDE gate is the right one here rather than a tenant's: this leg exists for the
        // deployment-wide upstream map, and the dispatcher is one singleton across every tenant. The store the screen
        // records into arrives per call, which is what lets a singleton carry a screen at all.
        return new FormatDispatcher(formats, new LiveUpstreams(liveConfig, formats), upstreamFetcher, observations,
                new ProxyScreenHooks(liveConfig::proxyGate, liveConfig.holdDays(),
                        liveConfig.withholdIncompleteScreens()));
    }

    @Bean
    public BatchIngestion batchIngestion(LiveConfig liveConfig) {
        // Batch archive ingestion, gated live: off unless the operator switches batch-upload on, its entry cap the
        // live zip-bomb bound. Driven by the free serving controller for every write (both tenancy modes), so an
        // exploded entry rides the same discovered compliance gate - and the same EdgeHooks - a single upload does.
        return new BatchIngestion(liveConfig::batchUpload, liveConfig::batchUploadMaxEntries);
    }

    @Bean("repositoryController")
    public build.jenesis.repository.server.RepositoryController repositoryServing(RepositoryRouting routing,
                                                                                  FormatDispatcher dispatcher,
                                                                                  ProxyFormat.Fetcher upstreamFetcher,
                                                                                  BatchIngestion batchIngestion,
                                                                                  RoutedServing routedServing,
                                                                                  DeployEdgeHooks deployEdgeHooks,
                                                                                  Environment environment) {
        // The free controller is the one serving AND writing surface now: reads and writes both dispatch
        // through the routing seam and the free ScreenedDispatch edge. Registered under the bean name
        // "repositoryController" so the free RepositoryAutoConfiguration's own
        // @ConditionalOnMissingBean(name = "repositoryController") backs off - this one richer instance serves, never
        // two - now that the auto-config is no longer excluded. A write is a store-then-screen over the free
        // Publication with the discovered compliance gate riding the interceptor chain and the enterprise EdgeHooks bean
        // (deployEdgeHooks) plugged in for the immutability 409 / quarantine record / deploy observation; a batch
        // explode header is walked here too. A format reads a runtime toggle (the Maven metadata computation opt-in) off
        // the exchange, resolved against the jenreg.* environment into which a stored setting is layered at
        // boot, so the free format needs no settings dependency.
        List<ImportSourceProvider> importSources =
                ImportSourceProvider.installed(Features.namespaced(environment::getProperty));
        // the enterprise EdgeHooks bean is threaded into the free screening edge, retiring the DeployController
        // fork onto this one shared write path. It carries the fork's ingress concerns - the release-immutability 409
        // (beforeLayout), the quarantine-dispatch record (held) and the deploy observation (verdict) - while the tenant
        // binding the gate resolution needs is opened around this controller by the PublishTenantFilter. The write
        // target / 405 rides Route.writable() (MultiTenantRouting), the quota 507 the store + this controller's own
        // handler, and the format-claim/verdict/batch loop ScreenedDispatch + BatchIngestion - so no fork remains.
        return new build.jenesis.repository.server.RepositoryController(routing, dispatcher, importSources,
                upstreamFetcher, batchIngestion, Features.namespaced(environment::getProperty), null,
                routedServing, deployEdgeHooks);
    }

    @Bean
    public DeployEdgeHooks deployEdgeHooks(LiveConfig liveConfig, ObservationRegistry observations) {
        // The enterprise deploy edge's ingress concerns, plugged into the free ScreenedDispatch through the EdgeHooks
        // seam rather than forked into a second controller: the release-immutability 409 (post-hash, pre-layout), the
        // quarantine-dispatch replay record around the 202, and the jenreg.deploy observation. The tenant
        // each concern needs is read from PublishTenant (bound by publishTenantFilter).
        return new DeployEdgeHooks(liveConfig, observations);
    }

    @Bean
    public PublishTenantFilter publishTenantFilter(RepositoryRouting routing) {
        // Binds the request's tenant to the publishing thread on /repository/** and /v2/** so the discovered compliance
        // gate resolves that tenant's own policy - the binding the retired DeployController opened inline, now opened
        // around the free serving controller writes flow through. Resolving through the active routing (not the key
        // header alone) is what makes path/host tenancy screen a keyless CDN write with the path-named tenant's policy.
        return new PublishTenantFilter(routing);
    }
}
