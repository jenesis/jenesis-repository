package build.jenesis.repository.ui.admin.web;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.observation.Contributions;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.compliance.AdvisorySource;
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
 * What this deployment's module path carries, computed once at startup (ServiceLoader presence is static for a
 * JVM): the one gating signal the console's pages share, so a surface whose module is absent is hidden rather than
 * broken, and no page invents its own check.
 *
 * <p><b>Installed is the gate for a capability a module reports about itself, and enabled is the gate for a console
 * module.</b> The distinction is not a nuance; getting it wrong puts a link to a 404 in the navigation bar. A
 * feature module that is installed but not configured still has its endpoints and its screens, so its surface can
 * render a "configure it" state and an operator can find what to switch on - which is why the contributed flags
 * below read installed. A console module that is switched off is not <em>imported</em>:
 * {@link build.jenesis.repository.ui.ConsoleModuleImports ConsoleModuleImports} asks
 * {@link ConsoleModuleProvider#enabled}, so its controllers do not exist, and its own contract says a
 * switched-off module degrades "exactly as if the module were absent from the image". There is no configure-it state
 * to render, because there is no screen to render it on.
 *
 * <p>The nav read installed while the imports read enabled, so every switched-off console module contributed a link
 * to a path nothing had mapped. The deploy screen ships switched off by default, so that was its every deployment:
 * an admin-only "Deploy" entry in the bar answering 404, and nothing in the console able to say why. Both now come
 * from one list, resolved once here.
 */
@Service
public class CapabilityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CapabilityService.class);

    private final Capabilities capabilities;

    private final Map<String, Boolean> named;

    private final List<NavEntry> moduleNav;

    private final List<RepositoryPage> moduleRepositoryPages;

    /**
     * @param environment the console's configuration chain, handed to the capability contributors so a flag they
     *                    resolve from a setting answers here exactly as it answers on {@code /api/capabilities},
     *                    and to the console-module discovery so the nav names what this deployment imported.
     */
    public CapabilityService(Environment environment) {
        UnaryOperator<String> config = Features.namespaced(environment::getProperty);
        // The console modules this deployment will actually import, which is the list the nav and the three
        // console-module flags below are both derived from - the same question asked once.
        List<ConsoleModuleProvider> consoleModules = ConsoleModuleProvider.enabled(config);
        // The six flags an installed feature module reports about itself are READ here, not re-derived. Each has one
        // definition - its owning module's CapabilityContributor - and one discovery pipeline, the SPI home's
        // resolve. Deriving them a second time beside the contributors is what let this gate and the served
        // /api/capabilities disagree: the console resolved `gc` through a null configuration while the contributor
        // resolved it through a real one, and answered `dependents` from the maintenance task's presence while the
        // contributor answered from the query provider's. The flags with no contributor stay below, derived here,
        // because nothing else answers them.
        Map<String, Object> contributed = CapabilityContributor.resolve(Map.of(), config).capabilities();
        this.capabilities = new Capabilities(
            !AdvisorySource.installed().isEmpty(),
            flag(contributed, "audit"),
            StagingProvider.resolve(_ -> null).isPresent(),
            RetentionProvider.resolve(_ -> null).isPresent(),
            // The GC SPI's no-op-by-absence contract made visible: with no collector resolved, a cleanup evicts
            // but reclaims nothing, and the cleanup screen says garbage collection is off.
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
            // The durable maintainer-health module: present, the health panel renders the ledger the sweep populates;
            // absent, the panel surface is hidden rather than broken (like every other capability signal).
            HealthLedgerProvider.installed().isPresent(),
            // The hardening proxy leg (EPIC 23): present when the gateway's migration-rescreen maintenance task is
            // installed, so the console shows the hardened badge/verdict panel for a hardened repository and hides the
            // surface entirely on a deployment that carries no hardening leg. The per-repository gate stays the repo's
            // own harden flag; this is the module-presence signal, discovered like every other (§2).
            MaintenanceTaskProvider.installed().contains("migration-rescreen"),
            enabled(consoleModules, "scim"),
            ImportSourceProvider.declared().stream()
                    .map(provider -> new ImportSourceView(
                            provider.name(), provider.label(), provider.requiresFormat()))
                    .toList());
        this.named = named(capabilities);
        // The pages the imported console modules contribute, discovered once at startup like every other capability
        // signal (a module's providers are static for a JVM). The shell filters these by the caller's role and the
        // pages' own requirements per request; the discovery itself is not repeated on the hot path.
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
     * One module's pages, refused whole when any names a capability this console does not answer.
     *
     * <p>A requirement nobody answers can never be met, so the page would be hidden on every deployment and nothing
     * would say why - the module's author would see a page that simply never appears. Refusing it at startup with
     * the module and the name in the log is the loud version of the same outcome.
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
                Map.entry("import", capabilities.importAvailable()));
    }

    /** One contributed flag, absent-reads-false - the SPI's no-op-by-absence contract, which is how a module that
     *  is not on this deployment's path leaves its console surface hidden rather than broken. */
    private static boolean flag(Map<String, Object> contributed, String name) {
        return contributed.get(name) instanceof Boolean value && value;
    }

    /** Whether a named console module is among the ones this deployment imported. */
    private static boolean enabled(List<ConsoleModuleProvider> modules, String name) {
        return modules.stream().map(ConsoleModuleProvider::name).anyMatch(name::equals);
    }

    /**
     * The pages a console module that threw, or named a capability nobody answers, contributes: none.
     *
     * <p>Uncontained, this fan-out ran at construction, so one optional module's {@code navEntries()} throwing took
     * the whole shell - every other module's links with it, and the Spring context with them, which is a deployment
     * that does not start rather than a console missing one entry. Contributing nothing is the honest degrade here
     * and not merely the safe one: a nav link is an offer to navigate somewhere, and a module that could not say
     * where has no offer to make. A half-built entry would be a link to a page that may not answer.
     *
     * <p>The failure names the module's implementation class and the exception type only, on the same reasoning as
     * the base console's panel card: an exception message is uncontrolled text that may quote a configured value,
     * and the whole failure belongs in the log rather than on an operator's page.
     */
    private static Contributed noPages(ConsoleModuleProvider provider, Exception failure) {
        LOGGER.warn("console module {} could not contribute its pages, so it contributes none: {}",
                Contributions.segment(provider), failure.getClass().getName(), failure);
        return Contributed.NONE;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    /** The pages the imported console modules contribute to the first level, for the shell to list beside the core
     *  ones - imported rather than installed, because a switched-off module has no screen for its link to reach.
     *  Computed once at startup; the shell decides per request which the current user may see. */
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
                               List<ImportSourceView> importSources) {

        /** An import needs both a source connector and the upstream fetcher on the module path. */
        public boolean importAvailable() {
            return upstream && !importSources.isEmpty();
        }
    }

    /** An installed import source, as the migration form's picker renders it. */
    public record ImportSourceView(String name, String label, boolean requiresFormat) {
    }
}
