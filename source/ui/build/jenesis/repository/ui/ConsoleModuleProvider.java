package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * One removable console module, discovered with {@link java.util.ServiceLoader}: it names the Spring
 * {@code @Configuration} class that wires the module's controllers, security chains and contributors into the console
 * context, imported by {@link ConsoleModuleImports} like a Boot auto-configuration. A console capability is a module
 * the console never names, and its screens gate on whether it is installed.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()}, {@link #configuration()}, {@link #navEntries()} and
 *     {@link #repositoryPages()} are pure declarations, callable concurrently. A provider holds no mutable state and
 *     opens nothing: it is constructed during context refresh, before its beans exist.</li>
 * <li><b>Idempotency / replay.</b> All four are constant functions of what is installed, since the nav is discovered
 *     once and rendered per request.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never legal. A module with no screen (a sign-in mechanism, the SCIM
 *     API) returns empty lists, never a placeholder.</li>
 * <li><b>Selection failure.</b> An {@code ALL} SPI with nothing to select, but two providers on one {@link #name()},
 *     or one class registered twice, make {@link #installed()} and {@link #enabled} throw naming them, since they
 *     would share one {@code jenrepo.<name>} toggle. Two providers naming one {@link #configuration()} class are
 *     refused by the contract suite.</li>
 * <li><b>Tenant scoping.</b> A provider carries no tenant and declares the deployment's console surface. Its
 *     {@link NavEntry#access()} floor is a coarse role gate the shell resolves per request against the current tenant;
 *     a finer capability is the screen's own concern.</li>
 * <li><b>Error visibility.</b> An exception from any of the four, or a {@link #configuration()} class that cannot be
 *     loaded, fails the context refresh rather than dropping one module quietly.</li>
 * <li><b>Read purity.</b> None of the four performs I/O, so the rendered shell depends on nothing else being up.</li>
 * <li><b>Lifecycle / ownership.</b> {@link #installed()} and {@link #enabled} re-instantiate every provider per call;
 *     a provider is cheap to build, owns nothing and is never closed. The shell discovers the nav once at startup. The
 *     {@link #configuration()} class is Spring's to instantiate.</li>
 * <li><b>Ordering / determinism.</b> Both statics sort providers by name; a module's own {@link #navEntries()} order is
 *     the render order of its links, and no module depends on being imported before a peer.</li>
 * <li><b>Nav-entry shape.</b> A {@link NavEntry#label()} is non-blank text, escaped only by the template; a
 *     {@link NavEntry#path()} is an application-root-relative path ({@code /walks}), unique across modules, and a
 *     {@link RepositoryPage#path()} the same below {@code /repositories/<name>}, unique within a repository. A
 *     {@code requires} the console does not know is a packaging error: the module's pages are withheld with a
 *     warning.</li>
 * <li><b>Bounded work / cancellation.</b> Bounded by the installed modules: each is instantiated and asked once per
 *     pass.</li>
 * </ol>
 */
public interface ConsoleModuleProvider extends IconContributor {

    /** The SPI's selection key, the {@code <spi>} every diagnostic points at. */
    String SPI = "console-module";

    /** The module name, e.g. {@code oidc}: also its {@code jenrepo.<name>} toggle key ({@link Features}), spelled like
     *  any settings key and equal to its catalogued enablement gate's key. */
    String name();

    /** The module's {@code @Configuration} class, given full configuration-class treatment when imported. */
    Class<?> configuration();

    /** The navigation links this module adds to the console shell, shown exactly while it is installed. */
    default List<NavEntry> navEntries() {
        return List.of();
    }

    /** The pages this module adds to every repository, shown while it is installed and the reader's role and the
     *  page's {@code requires} allow. */
    default List<RepositoryPage> repositoryPages() {
        return List.of();
    }

    /**
     * Every installed console module, whatever its configuration, name-sorted, through {@link Providers#all}, which
     * refuses a duplicate name or class.
     */
    static List<ConsoleModuleProvider> installed() {
        return Providers.all(SPI,
                ServiceLoader.load(ConsoleModuleProvider.class),
                ConsoleModuleProvider::name,
                _ -> true,
                Optional::of);
    }

    /**
     * The installed modules {@code config} leaves on, name-sorted, which {@link ConsoleModuleImports} imports. A module
     * switched off ({@code jenrepo.<name>=false}) is not imported, as if absent; unset means
     * {@link #enabledByDefault()}.
     */
    static List<ConsoleModuleProvider> enabled(UnaryOperator<String> config) {
        Objects.requireNonNull(config, "config");
        return Providers.all(SPI,
                ServiceLoader.load(ConsoleModuleProvider.class),
                ConsoleModuleProvider::name,
                provider -> Features.enabled(config, provider.name(), provider.enabledByDefault()),
                Optional::of);
    }

    /**
     * This module's posture when its key is unset: on for all but a few, such as the manual upload screen, which writes
     * into repositories and ships off. Its catalogue entry's {@code defaultValue} reads the same answer.
     */
    default boolean enabledByDefault() {
        return true;
    }
}
