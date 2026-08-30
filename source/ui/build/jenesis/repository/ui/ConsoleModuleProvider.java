package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.icon.IconContributor;

/**
 * One removable console module, discovered with {@link java.util.ServiceLoader}: it names the Spring
 * {@code @Configuration} class that wires the module - its controllers, security chains, contributors and
 * properties - into the console context, imported by {@link ConsoleModuleImports} exactly like a Boot
 * auto-configuration. So a console capability (a sign-in mechanism, the SCIM provisioning API) is a drop-in
 * module the console never names, and its screens gate on whether the module is installed.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()}, {@link #configuration()} and {@link #navEntries()} are pure declarations
 *     the console may call from any thread, including concurrently. A provider holds no mutable state and opens
 *     nothing: it is constructed during context refresh, before any of its own beans exist.</li>
 * <li><b>Idempotency / replay.</b> All three are constant functions of what is installed, not of when they are called
 *     or of what is stored: two calls in one JVM return the same name, the same class literal and an equal nav list.
 *     The nav is discovered once at startup and rendered per request, so a list that varied by call would show a
 *     different shell to two users of one deployment.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never a legal return. A module with no user-facing screen (a sign-in
 *     mechanism, the machine-facing SCIM API) returns the empty {@link #navEntries()} list, never {@code null} and
 *     never a placeholder entry. Absence of a capability is expressed by the module being absent from the path -
 *     which is itself the capability gate the shell reads (&sect;3).</li>
 * <li><b>Selection failure (&sect;9).</b> This is an {@code ALL} SPI: every installed module contributes and there is
 *     nothing to select. A <em>collision</em> is still a packaging error: two providers answering to one
 *     {@link #name()}, or one provider class registered twice, make {@link #installed()} and {@link #enabled} throw
 *     naming the colliding classes rather than letting module-path order pick a winner - two modules on one name
 *     share the single {@code jenreg.<name>} toggle, so switching one off switches both off. Two
 *     providers naming one {@link #configuration()} class is equally a packaging error, refused by the contract suite
 *     because the configuration class is this SPI's own concept rather than the shared discovery primitive's.</li>
 * <li><b>Tenant scoping (&sect;6).</b> A provider carries no tenant and is resolved once per JVM: it declares a
 *     deployment's installed console surface, not a tenant's. Its {@link NavEntry#access()} floor is a coarse role
 *     gate the shell resolves server-side against the current tenant per request; a finer, per-tenant capability
 *     stays the screen's own concern behind the link.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing here is best-effort. An exception from any of the three methods, or
 *     a {@link #configuration()} class that cannot be loaded or instantiated, fails the context refresh rather than
 *     dropping one module quietly out of a console that then renders a screen-less shell.</li>
 * <li><b>Read purity (&sect;10).</b> None of the three performs I/O. They are declarations read during context
 *     refresh and at nav-discovery time; a provider that reached the store or the network to decide its name or its
 *     links would make the rendered shell depend on something else being up.</li>
 * <li><b>Lifecycle / ownership.</b> {@code ServiceLoader} instances are created by {@link #installed()} and
 *     {@link #enabled}, are not cached across calls, own no threads or clients and are never closed, so a provider
 *     must be cheap to build and must open nothing. The {@link #configuration()} class is owned by Spring, which
 *     instantiates it once per context; the provider never instantiates it. The discovered nav is computed once at
 *     startup - a module's providers are static for a JVM - and never re-discovered on the request path.</li>
 * <li><b>Ordering / determinism.</b> Import and nav order never depend on module-path order: both statics sort
 *     providers by name, and a module's own {@link #navEntries()} order is the render order of its links among
 *     themselves. A module must not depend on being imported before or after a peer.</li>
 * <li><b>Nav-entry shape.</b> An entry's {@link NavEntry#label()} is non-blank display text and its
 *     {@link NavEntry#path()} is an <em>application-root-relative path</em> such as {@code /scim} - never a bare
 *     screen id - and unique across installed modules so two modules never render two links to one place. That is the
 *     entry's single meaning, and the accessor is named for it: the shell resolves it as the {@code th:each} link
 *     target, and a single-page consumer would derive its own in-page id <em>from</em> the
 *     path rather than reading the path as one. Nothing sanitises a label beyond the template's own escaping, so a
 *     module is answering for its own text.</li>
 * <li><b>Bounded work / cancellation.</b> Work is bounded by the number of installed modules: each provider is
 *     instantiated once and asked for its declarations once per pass. Nothing blocks and no timeout applies.</li>
 * </ol>
 */
public interface ConsoleModuleProvider extends IconContributor {

    /** The SPI's selection key, the {@code <spi>} every diagnostic points at. */
    String SPI = "console-module";

    /** The module name this provider answers to, e.g. {@code oidc}, {@code scim}. It is also the module's
     *  {@code jenreg.<name>} toggle key (the {@link Features} convention), so it is lowercase and
     *  dotted/hyphenated like any other settings key - and where the module catalogues its own enablement gate
     *  through a {@code SettingsContributor}, that gate's key is this same spelling. */
    String name();

    /** The module's {@code @Configuration} class, given full configuration-class treatment when imported. */
    Class<?> configuration();

    /** The navigation links this module adds to the console shell, rendered by {@code th:each} beside the core links
     *  rather than hardcoded per-screen in the template. Empty by default (a module with no user-facing screen, like a
     *  sign-in mechanism or the machine-facing SCIM API, contributes none); a module with a screen names its own link
     *  here, and it appears exactly when the module is installed. */
    default List<NavEntry> navEntries() {
        return List.of();
    }

    /**
     * Every console module installed on this deployment, whatever its configuration, name-sorted - the discovery seam
     * the shell's capability signal and its nav assembly read.
     *
     * <p>It is the shared {@link Providers#all ALL-policy primitive}, not a copy of it: console modules are additive,
     * so there is no selection to miss, but a <em>duplicate</em> provider name or class is still a packaging error
     * (clause 4). It is deliberately not a second discovery pipeline - the {@code uses} clause and the
     * {@link ServiceLoader} call stay in this one module beside the contract.
     */
    static List<ConsoleModuleProvider> installed() {
        return Providers.all(SPI,
                ServiceLoader.load(ConsoleModuleProvider.class),
                ConsoleModuleProvider::name,
                _ -> true,
                Optional::of);
    }

    /**
     * The installed modules {@code config} leaves switched on, name-sorted - what {@link ConsoleModuleImports} turns
     * into a configuration-class list. A module configured off by its provider name
     * ({@code jenreg.<name>=false}, the {@link Features} convention) is not imported, so its screens
     * degrade exactly as if the module were absent from the image; unset means enabled.
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
     * This module's posture when its key is <em>unset</em> - on for all but a few.
     *
     * <p>A module states its own default rather than the reader assuming one, because the reader governs every
     * console module and the exceptions are per module: key-based sign-in is a demo on-ramp whose catalogue entry
     * says "disabled by default", and reading it through the ordinary on-unless-off rule made the code answer
     * ENABLED on a deployment that had never stored the key while the console rendered it off. Declaring
     * it here keeps the code's answer and the catalogue's {@code defaultValue} in one place per module instead of
     * two places that can disagree silently.
     */
    default boolean enabledByDefault() {
        return true;
    }
}
