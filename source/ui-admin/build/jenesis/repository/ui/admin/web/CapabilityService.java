package build.jenesis.repository.ui.admin.web;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.observation.Contributions;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;
import build.jenesis.repository.store.Features;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * What this deployment's module path carries, computed once at startup: the one gating signal the console's pages
 * share, so a surface whose module is absent is hidden rather than broken.
 *
 * <p>A capability a feature module reports about itself gates on installed, since an installed but unconfigured
 * module still has screens that can say what to switch on. A console module gates on enabled, the list
 * {@link build.jenesis.repository.ui.ConsoleModuleImports ConsoleModuleImports} imports
 * ({@link ConsoleModuleProvider#enabled}): a switched-off one has no controllers, so a link to it would answer 404.
 */
@Service
public class CapabilityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CapabilityService.class);

    private final Capabilities capabilities;

    private final Map<String, Boolean> named;

    private final List<NavEntry> moduleNav;

    private final List<RepositoryPage> moduleRepositoryPages;

    /**
     * @param environment the console's configuration chain, so a contributed flag answers as on
     *                    {@code /api/capabilities} and the nav names what this deployment imported
     */
    public CapabilityService(Environment environment) {
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        // The console modules this deployment imports, from which the nav and the console-module flags derive.
        List<ConsoleModuleProvider> consoleModules = ConsoleModuleProvider.enabled(config);
        // A flag a module contributes is read from its CapabilityContributor, as /api/capabilities reads it; only the
        // flags no contributor answers are derived here.
        Map<String, Object> contributed = CapabilityContributor.resolve(Map.of(), config).capabilities();
        this.capabilities = new Capabilities(
            !AdvisorySource.installed().isEmpty(),
            flag(contributed, "audit"),
            StagingProvider.resolve(_ -> null).isPresent(),
            RetentionProvider.resolve(_ -> null).isPresent(),
            // Without a collector a cleanup evicts but reclaims nothing, and the cleanup screen says so.
            flag(contributed, "gc"),
            flag(contributed, "scan"),
            flag(contributed, "provenance"),
            FetcherProvider.resolve(_ -> null) != ProxyFormat.Fetcher.NONE,
            UpstreamCredentialSourceProvider.installed(),
            RateLimiterProvider.resolve(_ -> null) != RateLimiter.NONE,
            flag(contributed, "dependents"),
            flag(contributed, "search"),
            MaintenanceTaskProvider.installed().contains("index"),
            MaintenanceTaskProvider.installed().contains("license-retro-enforce"),
            FindingsProvider.installed().isPresent(),
            // The health page needs the ledger and a source that fills it.
            HealthLedgerProvider.installed().isPresent() && !HealthSource.installed().isEmpty(),
            // The hardened proxy leg is present with its migration-rescreen task; each repository's own flag still
            // decides whether it hardens.
            MaintenanceTaskProvider.installed().contains("migration-rescreen"),
            enabled(consoleModules, "scim"),
            flag(contributed, "leak-webhook"),
            // The AI review queue needs the findings ledger and a code audit to fill it.
            FindingsProvider.installed().isPresent() && flag(contributed, "ai-review"),
            ImportSourceProvider.declared().stream()
                    .map(provider -> new ImportSourceView(
                            provider.name(), provider.label(), provider.requiresFormat()))
                    .toList(),
            // The build tools the cache serves, as contributed to /api/capabilities.
            cacheProtocols(contributed));
        this.named = named(capabilities);
        // Discovered once; the shell filters them per request by role and requirement.
        List<Contributed> modulePages = Contributions.collect("console module", consoleModules, this::contributed,
                CapabilityService::noPages);
        this.moduleNav = modulePages.stream().flatMap(module -> module.nav().stream()).toList();
        this.moduleRepositoryPages = modulePages.stream().flatMap(module -> module.pages().stream()).toList();
    }

    /** What one console module contributes to the two navigation levels. */
    private record Contributed(List<NavEntry> nav, List<RepositoryPage> pages) {

        static final Contributed NONE = new Contributed(List.of(), List.of());
    }

    /**
     * One module's pages, refused whole, with a log line naming the module and the capability, when any page requires
     * a capability this console does not answer and so could never be shown.
     */
    private Contributed contributed(ConsoleModuleProvider provider) {
        List<NavEntry> nav = List.copyOf(provider.navEntries());
        List<RepositoryPage> pages = List.copyOf(provider.repositoryPages());
        Stream.concat(nav.stream().map(NavEntry::requires), pages.stream().map(RepositoryPage::requires))
                .filter(requires -> !requires.isEmpty() && !named.containsKey(requires))
                .findFirst()
                .ifPresent(unknown -> {
                    throw new IllegalArgumentException("a page requires the capability '" + unknown
                            + "', which this console does not know; it knows " + new TreeSet<>(named.keySet()));
                });
        return new Contributed(nav, pages);
    }

    /** Every capability by the name a page's {@code requires} uses. */
    private static Map<String, Boolean> named(Capabilities capabilities) {
        return Map.ofEntries(
                Map.entry("advisories", capabilities.advisories()),
                Map.entry("audit", capabilities.audit()),
                Map.entry("staging", capabilities.staging()),
                Map.entry("retention", capabilities.retention()),
                Map.entry("gc", capabilities.gc()),
                Map.entry("scan", capabilities.scan()),
                Map.entry("provenance", capabilities.provenance()),
                Map.entry("upstream", capabilities.upstream()),
                Map.entry("upstreamCredentials", capabilities.upstreamCredentials()),
                Map.entry("rateLimit", capabilities.rateLimit()),
                Map.entry("dependents", capabilities.dependents()),
                Map.entry("search", capabilities.search()),
                Map.entry("index", capabilities.index()),
                Map.entry("licensePolicy", capabilities.licensePolicy()),
                Map.entry("findings", capabilities.findings()),
                Map.entry("maintainerHealth", capabilities.maintainerHealth()),
                Map.entry("hardening", capabilities.hardening()),
                Map.entry("scim", capabilities.scim()),
                Map.entry("leakWebhook", capabilities.leakWebhook()),
                Map.entry("aiReview", capabilities.aiReview()),
                Map.entry("import", capabilities.importAvailable()));
    }

    /** One contributed flag; absent reads false, so an absent module's surface is hidden. */
    private static boolean flag(Map<String, Object> contributed, String name) {
        return contributed.get(name) instanceof Boolean value && value;
    }

    /** The contributed {@code cacheProtocols} list, empty where the cache contributed none. */
    private static List<CacheProtocolView> cacheProtocols(Map<String, Object> contributed) {
        if (!(contributed.get("cacheProtocols") instanceof List<?> protocols)) {
            return List.of();
        }
        List<CacheProtocolView> views = new ArrayList<>();
        for (Object protocol : protocols) {
            if (protocol instanceof Map<?, ?> entry && entry.get("name") instanceof String name
                    && entry.get("endpoint") instanceof String endpoint) {
                views.add(new CacheProtocolView(name, endpoint));
            }
        }
        return List.copyOf(views);
    }

    /** Whether a named console module is among the ones this deployment imported. */
    private static boolean enabled(List<ConsoleModuleProvider> modules, String name) {
        return modules.stream().map(ConsoleModuleProvider::name).anyMatch(name::equals);
    }

    /**
     * The pages a console module contributes when it threw or named an unanswered capability: none, so one module
     * cannot stop the console starting. The log names its class and exception type only, since a message may quote a
     * configured value.
     */
    private static Contributed noPages(ConsoleModuleProvider provider, Exception failure) {
        LOGGER.warn("console module {} could not contribute its pages, so it contributes none: {}",
                Contributions.segment(provider), failure.getClass().getName(), failure);
        return Contributed.NONE;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    /** The first-level pages the imported console modules contribute; the shell decides per request which the user may
     *  see. */
    public List<NavEntry> moduleNav() {
        return moduleNav;
    }

    /** The pages the imported console modules add to every repository, on the same terms as {@link #moduleNav()}. */
    public List<RepositoryPage> moduleRepositoryPages() {
        return moduleRepositoryPages;
    }

    /** Whether the capability a page {@code requires} is present; the empty requirement always is. */
    public boolean has(String requires) {
        if (requires.isEmpty()) {
            return true;
        }
        Boolean present = named.get(requires);
        if (present == null) {
            throw new IllegalArgumentException("Unknown capability '" + requires + "'; known: "
                    + new TreeSet<>(named.keySet()));
        }
        return present;
    }

    /** The installed feature modules and import sources the console gates its surface on. */
    public record Capabilities(boolean advisories, boolean audit, boolean staging, boolean retention, boolean gc,
                               boolean scan,
                               boolean provenance, boolean upstream, boolean upstreamCredentials, boolean rateLimit,
                               boolean dependents, boolean search, boolean index, boolean licensePolicy,
                               boolean findings, boolean maintainerHealth, boolean hardening, boolean scim,
                               boolean leakWebhook, boolean aiReview, List<ImportSourceView> importSources,
                               List<CacheProtocolView> cacheProtocols) {

        /** An import needs both a source connector and the upstream fetcher on the module path. */
        public boolean importAvailable() {
            return upstream && !importSources.isEmpty();
        }
    }

    /**
     * A build tool the cache serves, as the projects page lists it: its name, and the endpoint its client is pointed
     * at with {@code <tenant>} and {@code <project>} left to fill in.
     */
    public record CacheProtocolView(String name, String endpoint) {

        /** The endpoint within {@code tenant}'s cache. */
        public String endpointFor(String tenant) {
            return endpoint.replace("<tenant>", tenant);
        }
    }

    /** An installed import source, as the migration form's picker renders it. */
    public record ImportSourceView(String name, String label, boolean requiresFormat) {
    }
}
