package build.jenesis.repository.application;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.discovery.DiscoverySettingsContributor;
import build.jenesis.repository.discovery.RepositoryDiscovery;
import build.jenesis.repository.discovery.ScreenedTransport;
import build.jenesis.repository.server.kernel.AuthFetcher;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.LiveUpstreams;
import build.jenesis.repository.server.kernel.PublishTenantFilter;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.BatchIngestion;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.PullThroughCache;
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
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.server.spi.CapabilityContributor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import build.jenesis.repository.gateway.SpoolStore;
import io.micrometer.observation.ObservationRegistry;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The routing and serving wiring: upstream credentials and fetcher, the {@link RepositoryRouter} and its
 * {@link RoutedServing} read side, the tenancy routing, the live {@link FormatDispatcher}, batch ingestion and the
 * {@code RepositoryController} serving bean with {@link DeployEdgeHooks} and {@link PublishTenantFilter}.
 *
 * <p>{@link ImportController} is the one import edge: the API's, beside the console's start of the same
 * migration.
 */
@Configuration(proxyBeanMethods = false)
public class ServingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServingConfig.class);

    @Bean
    public UpstreamCredentialSource upstreamCredentials(ArtifactStore store, Environment environment) {
        // NONE when no source is installed: no credential is attached and the management endpoints answer 501.
        return UpstreamCredentialSourceProvider.resolve(store,
                Features.namespaced(environment::getProperty));
    }

    @Bean
    public ProxyFormat.Fetcher upstreamFetcher(UpstreamCredentialSource upstreamCredentials, Environment environment) {
        // NONE when no fetcher is installed: the router serves local content only and imports answer 501. The
        // decorator adds per-host upstream credentials.
        ProxyFormat.Fetcher resolved = FetcherProvider.resolve(
                Features.namespaced(environment::getProperty));
        return resolved == ProxyFormat.Fetcher.NONE || upstreamCredentials == UpstreamCredentialSource.NONE
                ? resolved
                : new AuthFetcher(resolved, upstreamCredentials);
    }

    /**
     * This deployment's contribution to {@code /api/capabilities}, read from this context's
     * {@link DeploymentInfoController} per request.
     */
    @Bean
    public DeploymentCapabilities deploymentCapabilities(ObjectProvider<DeploymentInfoController> info) {
        return new DeploymentCapabilities(() -> {
            DeploymentInfoController controller = info.getIfAvailable();
            return controller == null ? null : controller.capabilityMap();
        });
    }

    /** The pre-verdict spool, sized from {@code jenrepo.spool.*}: the pass-through leg spools each untrusted upstream
     *  body through it and answers {@code 503} when a budget is exhausted. A bean so its gauges are reported. */
    @Bean
    public SpoolStore spoolStore(Environment environment) {
        return SpoolStore.fromConfig(Features.namespaced(environment::getProperty));
    }

    @Bean
    public RepositoryRouter repositoryRouter(LiveDefinitions definitions, LiveConfig liveConfig, Repositories repositories,
                                             ProxyFormat.Fetcher upstreamFetcher, Environment environment,
                                             SpoolStore spool, ArtifactStore root,
                                             ObjectProvider<UpstreamCredentialSource> credentials,
                                             ObjectProvider<DownloadTracker> downloads,
                                             RepositoryDiscovery discovery) {
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        // Size, timeout and throughput bounds on an untrusted upstream fetch, read against this spool's budget so a
        // per-artifact ceiling above it fails the boot rather than never firing.
        HardenedScreen.Bounds hardeningBounds = HardenedScreen.Bounds.fromConfig(config, spool.budget());
        // A local 404 over a withheld path is a refusal that ends the walk, not a miss that falls through to a weaker
        // fallback.
        PullThroughCache.Withheld held = held();
        RepositoryRouter.WithheldGuard withheld = held::withheld;
        RepositoryRouter router = new RepositoryRouter(definitions::definition, repositories::store, upstreamFetcher)
                .gating(liveConfig::gate, liveConfig::holdDays, liveConfig::withholdIncompleteScreens)
                // A repository's own settings decide how its fetches are screened: as a publish is where its upstreams
                // are marked internal, and under its screening mode.
                .repositorySettings((tenant, repository) -> key -> liveConfig.effective(tenant, repository, key, null))
                // The scratch carries the store's bindings, so a pass-through is screened as a publish would be.
                .passingThrough(() -> spool.acquire(root.bindings()))
                .hardening(hardeningBounds)
                .withholding(withheld);
        return redirecting(router.discovering(discovery), config, liveConfig, repositories, withheld,
                credentials.getIfAvailable(() -> UpstreamCredentialSource.NONE), downloads.getIfAvailable(),
                discovery);
    }

    /** The reader a {@code discovered} leg locates its files through, and the one the operator surfaces check with:
     *  over the screened client, refusing the private hosts the proxy dial does not admit, each domain's file
     *  remembered for the period {@code discovery-ttl} names now. */
    @Bean
    public RepositoryDiscovery repositoryDiscovery(LiveConfig liveConfig) {
        return discovery(liveConfig);
    }

    static RepositoryDiscovery discovery(LiveConfig liveConfig) {
        return new RepositoryDiscovery(new ScreenedTransport(),
                target -> !liveConfig.proxyAllowInternal() && PrivateHostGuard.internal(target),
                () -> {
                    try {
                        return Duration.parse(liveConfig.effective(DiscoverySettingsContributor.TTL,
                                DiscoverySettingsContributor.TTL_DEFAULT).strip());
                    } catch (DateTimeParseException | NullPointerException unread) {
                        return RepositoryDiscovery.DEFAULT_TTL;
                    }
                }, Clock.systemUTC());
    }

    /** Registers whether the redirect token parses, for the boot-time definition sweep that runs before the router
     *  exists: a {@code redirect} token parses exactly when an installed provider serves it. */
    static void registerRedirectTokens() {
        boolean upstream = false;
        for (RedirectHandlerProvider provider : RedirectHandlerProvider.installed()) {
            upstream |= provider.servesUpstream();
        }
        RepositoryDefinition.redirectHandlerInstalled(upstream);
    }

    /**
     * Builds every installed {@link RedirectHandlerProvider} from this deployment's configuration and the guards this
     * layer owns - the policy floor and withheld probe, the private-host and credential guards, download accounting -
     * chains them into the router, and registers whether the redirect token parses. With none installed it stays a
     * parse refusal and the router is returned unchanged.
     */
    static RepositoryRouter redirecting(RepositoryRouter router, UnaryOperator<String> config, LiveConfig liveConfig,
                                        Repositories repositories, RepositoryRouter.WithheldGuard withheld,
                                        UpstreamCredentialSource credentialSource, DownloadTracker downloadTracker,
                                        RepositoryDiscovery discovery) {
        List<RedirectHandlerProvider> providers = RedirectHandlerProvider.installed();
        boolean upstream = false;
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
            // The dial the definition sweep honours, so an admitted internal upstream is a valid redirect target.
            Predicate<URI> privateHost = target -> !liveConfig.proxyAllowInternal() && PrivateHostGuard.internal(target);
            RedirectHandlerProvider.Context context = new RedirectHandlerProvider.Context(config, screen,
                    privateHost, credentialed, recording, discovery);
            for (RedirectHandlerProvider provider : providers) {
                Optional<RepositoryRouter.RedirectHandler> handler = provider.create(context);
                if (handler.isEmpty()) {
                    LOGGER.warn("redirect handler provider {} built no handler; the legs it would serve are not served",
                            provider.getClass().getName());
                    continue;
                }
                handlers.add(handler.get());
                upstream |= provider.servesUpstream();
            }
        }
        RepositoryDefinition.redirectHandlerInstalled(upstream);
        if (handlers.isEmpty()) {
            return router;
        }
        LOGGER.info("redirect serve path wired: {} handler(s); 'fallback <url> redirect' {}", handlers.size(),
                upstream ? "parses" : "is refused");
        return router.redirecting(RedirectHandlerProvider.chain(handlers));
    }

    @Bean
    public RoutedServing routedServing(RepositoryRouter repositoryRouter) {
        // A routed repository (a proxy or a group) serves across its backings through the gating router; one with no
        // definition in the tenant declines, and the dispatcher serves it over its own store.
        return new RoutedServing() {
            @Override
            public boolean routes(String tenant, String repository) {
                return repositoryRouter.definition(tenant, repository) != null;
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
     * <p>It resolves through {@link RepositoryRoutingProvider}, so a further routing arrives as a provider; this method
     * supplies only what the application knows, the store and repository view in {@link RepositoriesRoutingContext}.
     * A name no provider answers fails the boot: routing to the wrong tenant serves the wrong data.
     */
    @Bean
    public RepositoryRouting repositoryRouting(ArtifactStore store, Repositories repositories,
                                               RepositoryProperties properties, Environment environment) {
        return RepositoryRoutingProvider.resolve(properties.getTenancy(),
                new RepositoriesRoutingContext(store, repositories, properties.getDefaultTenant(),
                        Features.namespaced(environment::getProperty)));
    }

    /** Every discovered format the {@link Features} convention leaves enabled; {@code jenrepo.<format>=false} trims
     *  one at boot exactly like an absent module. */
    /** Whether a path is held in a repository's store: withheld by an installed interceptor, or pending review. The
     *  review pointer is asked directly because a fresh quarantine has no serving pointer whose flag could say so -
     *  one read, only after a local 404. An undeterminable answer propagates, so a store outage is a failed read
     *  rather than a serve. The router's walk and the dispatcher's pull-through both ask it. */
    private static PullThroughCache.Withheld held() {
        List<PublishInterceptor> interceptors = PublishInterceptor.installed();
        return (path, store) ->
                PublishInterceptor.withheldByAny(path, store, interceptors) || Publication.reviewPending(store, path);
    }

    static List<RepositoryFormat> enabledFormats(Environment environment) {
        return RepositoryFormat.installed(Features.namespaced(environment::getProperty));
    }

    @Bean
    public FormatDispatcher formatDispatcher(LiveConfig liveConfig, ProxyFormat.Fetcher upstreamFetcher,
                                             ObservationRegistry observations, Environment environment) {
        // The upstream table is a live view over the runtime settings, so a change applies on the next fetch.
        List<RepositoryFormat> formats = enabledFormats(environment);
        // A format's proxy() caches through Publication.storeBlob and link, which run no interceptor chain, so this
        // leg - every repository without a router definition - is screened here. The hooks are one singleton into
        // which the dispatcher binds each request's tenant, repository and store, so a tenant's own proxy policy
        // screens it, through the publishing flavour where the repository marks its upstreams internal.
        FormatDispatcher.Upstreams upstreams = new LiveUpstreams(liveConfig, formats);
        return new FormatDispatcher(formats, upstreams, upstreamFetcher, observations,
                ProxyScreenHooks.perTenant(liveConfig::gate, liveConfig::holdDays,
                        liveConfig::withholdIncompleteScreens),
                held());
    }

    @Bean
    public BatchIngestion batchIngestion(LiveConfig liveConfig) {
        // Off unless batch-upload is switched on, its caps read live; an exploded entry passes the same gate and hooks
        // a single upload does.
        return new BatchIngestion(liveConfig::batchUpload, liveConfig::batchUploadMaxEntries,
                liveConfig::batchUploadMaxBytes, liveConfig::batchUploadMaxRatio);
    }

    @Bean("repositoryController")
    public build.jenesis.repository.server.RepositoryController repositoryServing(RepositoryRouting routing,
                                                                                  FormatDispatcher dispatcher,
                                                                                  ProxyFormat.Fetcher upstreamFetcher,
                                                                                  BatchIngestion batchIngestion,
                                                                                  RoutedServing routedServing,
                                                                                  DeployEdgeHooks deployEdgeHooks,
                                                                                  AuditTrail auditTrail,
                                                                                  LiveConfig liveConfig,
                                                                                  @Qualifier("repositoryAuthorizationManager")
                                                                                  ObjectProvider<AuthorizationManager<
                                                                                          RequestAuthorizationContext>>
                                                                                          authorization,
                                                                                  ObjectProvider<CapabilityContributor>
                                                                                          contributed,
                                                                                  Environment environment) {
        // The one serving and writing surface, named "repositoryController" so the free auto-configuration's
        // @ConditionalOnMissingBean(name = ...) backs off. Writes run through ScreenedDispatch with the deploy hooks;
        // the tenant is bound around it by the PublishTenantFilter.
        List<ImportSourceProvider> importSources =
                ImportSourceProvider.installed(Features.namespaced(environment::getProperty));
        // A repository-scoped setting a format reads resolves live for the addressed repository; any other key is
        // the boot environment's.
        UnaryOperator<String> environmental = Features.namespaced(environment::getProperty);
        return new build.jenesis.repository.server.RepositoryController(routing, dispatcher, importSources,
                upstreamFetcher, batchIngestion, (tenant, repository, key) ->
                        SettingsScopes.settableAt(key, Setting.Scope.REPOSITORY)
                                ? liveConfig.effective(tenant, repository, key, environmental.apply(key))
                                : environmental.apply(key), null,
                routedServing, deployEdgeHooks, auditTrail,
                new build.jenesis.repository.server.AuthorizedReads(authorization::getIfAvailable),
                // This context's contributions to /api/capabilities, beside the discovered ones.
                contributed.orderedStream().toList());
    }

    @Bean
    public DeployEdgeHooks deployEdgeHooks(LiveConfig liveConfig, ObservationRegistry observations) {
        // The release-immutability 409, the quarantine replay record and the jenrepo.deploy observation, for the
        // tenant the publishTenantFilter binds.
        return new DeployEdgeHooks(liveConfig, observations);
    }

    @Bean
    public PublishTenantFilter publishTenantFilter(RepositoryRouting routing) {
        // Binds the routed tenant to the publishing thread on /repository/** and /v2/**, so the gate resolves that
        // tenant's policy - a keyless write included.
        return new PublishTenantFilter(routing);
    }
}
