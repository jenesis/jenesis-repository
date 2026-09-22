package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * One removable server feature module, discovered with {@link java.util.ServiceLoader}: it names the Spring
 * {@code @Configuration} class that contributes the module's own {@code @RestController} beans (and whatever else it
 * wires) into the repository server context, imported by {@code ServerModuleImports} exactly like a Boot
 * auto-configuration. This is the {@code build.jenesis.repository.ui.ConsoleModuleProvider} pattern applied to the
 * server: a feature (staging, quarantine, the maintenance endpoints, ...) becomes a thin per-feature {@code web}
 * adapter the server never names, kept out of the core controllers, and its endpoints appear only when the
 * module is installed. The web adapter is the single Spring-facing piece over a framework-free implementation, so the
 * feature's own modules stay pure.
 *
 * <p><strong>What the composition root may still carry.</strong> An endpoint lives in {@code application} only when
 * it is the application's own rather than a feature's: the migration edge and the deployment-info read are, because
 * they exist in every composition and describe the composition itself. A feature's route - a console screen's API
 * twin, a plug-in's surface - arrives through this seam, however small, so that switching the feature off or leaving
 * its module out of an image removes the route with it. Three such twins used to sit in the root beside the two
 * routes that belong there, and were the only feature endpoints a toggle could not reach.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #configuration()} are pure declarations the server may call
 *     from any thread, including concurrently, and re-calls per import pass. A provider holds no mutable state, opens
 *     nothing and must never lazily initialise anything a second caller could observe half-built - it is constructed
 *     during context refresh, before any of its own beans exist.</li>
 * <li><b>Idempotency / replay.</b> Both methods are constant functions of what is installed, not of when they are
 *     called: two calls in one JVM return the same name and the same class literal. The import selector may run more
 *     than once (a nested or re-created context), and a differing answer would import a different server surface into
 *     an otherwise identical deployment.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never a legal return from either method, and {@link #name()} is never
 *     blank. Absence of a feature is expressed by the module being absent from the path - there is no "disabled"
 *     provider and no null configuration standing in for one. A feature whose endpoints must answer when the module
 *     is <em>not</em> installed leaves that answer to the built-in surface, which reports it as not installed
 *     (&sect;3), never to a provider that declares itself and then does nothing.</li>
 * <li><b>Selection failure (&sect;9).</b> This is an {@code ALL} SPI: every installed module contributes and there is
 *     nothing to select, so nothing degrades. What is still a packaging error is a <em>collision</em>: two providers
 *     answering to one {@link #name()}, or one provider class registered twice. Two providers on one name share the
 *     single {@code jenreg.<name>} toggle - switching one off switches both off and an operator has no key
 *     naming either - so {@link #installed()} and {@link #enabled} throw, naming the colliding classes, rather than
 *     letting module-path order pick a winner. Two providers naming one {@link #configuration()} class is equally a
 *     packaging error - the import list is de-duplicated by class name, so the loser's toggle would silently govern
 *     the winner's beans - and is refused by the contract suite, the configuration class being this SPI's own concept
 *     rather than the shared discovery primitive's.</li>
 * <li><b>Tenant scoping (&sect;6).</b> A provider carries no tenant and is resolved once per JVM, never per request:
 *     it declares a deployment's installed surface, not a tenant's. The controllers its configuration wires derive
 *     their tenant per request exactly as the core ones do.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing here is best-effort. An exception from either method, or a
 *     {@link #configuration()} class that cannot be loaded or instantiated, fails the context refresh - a feature
 *     that half-imports would leave the deployment serving some of a module's endpoints and 404-ing the rest, which
 *     is indistinguishable to a caller from a partly-broken product.</li>
 * <li><b>Read purity (&sect;10).</b> Neither method performs I/O. They are declarations read during context refresh;
 *     a provider that reached the store, the network or the filesystem to decide its name would make the deployment's
 *     installed surface depend on the reachability of something else at boot.</li>
 * <li><b>Lifecycle / ownership.</b> {@code ServiceLoader} instances are created by {@link #installed()} and
 *     {@link #enabled}, are not cached across calls, own no threads or clients and are never closed, so a provider
 *     must be cheap to build and must open nothing. The {@link #configuration()} class is owned by Spring, which
 *     instantiates it once per context and closes the beans it declares; the provider never instantiates it.</li>
 * <li><b>Ordering / determinism.</b> The import order never depends on module-path order: both statics sort providers
 *     by name. A feature module must not depend on being imported before or after a peer - it composes over the
 *     kernel and the SPIs, never over a sibling surface (the peer-isolation rule
 *     the extension contract states).</li>
 * <li><b>Bounded work / cancellation.</b> Work is bounded by the number of installed modules: each provider is
 *     instantiated once and asked for its name and its configuration once per pass. Nothing blocks, no thread is
 *     started and no timeout applies.</li>
 * </ol>
 */
public interface ServerModuleProvider {

    /** The SPI's selection key, the {@code <spi>} every diagnostic points at. */
    String SPI = "server-module";

    /** The module name this provider answers to, e.g. {@code staging}, {@code quarantine}. It is also the module's
     *  {@code jenreg.<name>} toggle key (the {@link Features} convention) and the key
     *  {@code ModuleTogglesSettingsContributor} catalogues, so it is lowercase and dotted/hyphenated like any other
     *  settings key. */
    String name();

    /** The module's {@code @Configuration} class, given full configuration-class treatment when imported. */
    Class<?> configuration();

    /**
     * Every server feature module installed on this deployment, whatever its configuration, name-sorted - the
     * discovery seam a catalogue (the modules console's toggle list) and the contract suite read.
     *
     * <p>It is the shared {@link Providers#all ALL-policy primitive}, not a copy of it: server modules are additive,
     * so there is no selection to miss, but a <em>duplicate</em> provider name or class is still a packaging error
     * (clause 4). It is deliberately not a second discovery pipeline - the {@code uses} clause and the
     * {@link ServiceLoader} call stay in this one module beside the contract, exactly as {@link #enabled} does for
     * the import selector.
     */
    static List<ServerModuleProvider> installed() {
        return Providers.all(SPI,
                ServiceLoader.load(ServerModuleProvider.class),
                ServerModuleProvider::name,
                _ -> true,
                Optional::of);
    }

    /**
     * The installed modules {@code config} leaves switched on, name-sorted - what the deferred import selector turns
     * into a configuration-class list. A module configured off by its provider name
     * ({@code jenreg.<name>=false}, the {@link Features} convention, settable as
     * {@code JENREG_<NAME>=false} through relaxed binding) is left out, so its endpoints degrade exactly
     * as if the module were absent from the image; unset means enabled, so the one image carries every module until
     * configuration trims it.
     *
     * <p>The collision refusal of {@link #installed()} applies here too, and applies to providers that are switched
     * <em>off</em> as well: a name collision an operator has hidden by disabling one of the two is still a packaging
     * error, and finding it only once someone switches it back on is exactly the late failure &sect;9 forbids.
     */
    static List<ServerModuleProvider> enabled(UnaryOperator<String> config) {
        Objects.requireNonNull(config, "config");
        return Providers.all(SPI,
                ServiceLoader.load(ServerModuleProvider.class),
                ServerModuleProvider::name,
                provider -> Features.enabled(config, provider.name()),
                Optional::of);
    }
}
