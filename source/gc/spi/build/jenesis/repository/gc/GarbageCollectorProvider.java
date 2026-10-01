package build.jenesis.repository.gc;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link GarbageCollector}, discovered with {@link ServiceLoader} so the reclamation strategy can
 * change without breaking a caller. Optional-unique: at most one collector is enabled, and {@code jenrepo.gc=<name>}
 * selects among several ({@code mark-sweep} is the reference implementation). Each provider reads {@code jenrepo.gc.*}
 * through the {@code config} lookup. With none installed {@link #resolve} is empty: <b>nothing is ever reclaimed</b>
 * and the capability surfaces say collection is off, because deleting data is never something a deployment gets without
 * opting in. Those surfaces report {@link #resolve resolve(config).isPresent()}, not {@link #installed()}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations; {@link #create} runs
 *       on the resolving thread. The returned collector is shared by the surfaces driving it, and cross-node
 *       single-writer safety is the collector's own.</li>
 *   <li><b>Idempotency / replay.</b> {@link #create} is a pure factory that claims, deletes and persists nothing, so
 *       resolving twice is safe; re-running a pass converges.</li>
 *   <li><b>Absence sentinel.</b> An unselected absence is not an error: {@link #resolve} answers an empty
 *       {@link Optional} and {@link #installed()} is {@code false} - nothing is ever reclaimed. {@link #create} also
 *       declines with empty when a capability it rides (the shared artifact walk) is absent, so a deployment without
 *       enumeration never gets a collector that enumerates its own way. {@code null} is never returned from
 *       {@link #create}, {@link #name()} or {@link #requiredConfig()}.</li>
 *   <li><b>Selection failure.</b> An explicit {@code jenrepo.gc=<name>} that no installed provider answers, or whose
 *       provider declines, throws {@link IllegalStateException} at resolution naming the selection and the installed
 *       names - an operator who named a collector and silently got none would believe reclamation runs while storage
 *       grows. An explicit selection outranks the {@code jenrepo.<name>=false} toggle; only an unselected deployment
 *       degrades to empty.</li>
 *   <li><b>Error visibility.</b> Duplicate names, a provider registered twice, and several enabled collectors with no
 *       selection all throw, naming the candidates and the setting - which collector deletes data is never decided by
 *       module-path order. Malformed {@code jenrepo.gc.stride} or {@code jenrepo.gc.grace} fails loudly in
 *       {@link #create}.</li>
 *   <li><b>Lifecycle / ownership.</b> The caller owns the resolved collector; {@link #resolve} builds at most one
 *       instance per call and caches and closes nothing. Providers are cheap, stateless factories.</li>
 *   <li><b>Ordering / determinism.</b> The resolved collector depends on configuration and installed providers only,
 *       never on discovery order.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #create} does no I/O. A pass's bounds are the collector's own
 *       settings, and reaching one leaves a resumable, safely incomplete pass.</li>
 * </ol>
 */
public interface GarbageCollectorProvider {

    /** The implementation name this provider answers to, e.g. {@code mark-sweep}. */
    String name();

    /** Build the collector, reading settings through {@code config}; empty when configured off or when the shared
     *  artifact walk it rides is absent - either way nothing is reclaimed. */
    Optional<GarbageCollector> create(UnaryOperator<String> config);

    /** The config keys this implementation cannot run without; empty by default. A provider whose required keys are
     *  unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** Whether a garbage collector is installed and not switched off - a packaging question ({@link Features#enabled}),
     *  not the capability one. A collector whose {@link #requiredConfig} keys are unset counts here while
     *  {@link #resolve} reports it absent, and two enabled collectors count here while {@link #resolve} refuses them,
     *  so a surface gated on this would promise reclamation that will not happen. Capability surfaces ask
     *  {@code resolve(config).isPresent()}. */
    static boolean installed() {
        return !Providers.installedNames("gc",
                ServiceLoader.load(GarbageCollectorProvider.class),
                GarbageCollectorProvider::name,
                provider -> Features.enabled(provider.name())).isEmpty();
    }

    /** The single enabled collector, through the shared {@link Providers#optionalUnique} policy: an explicit
     *  {@code jenrepo.gc=<name>} nothing answers throws, {@code jenrepo.<name>=false} switches one off, several enabled
     *  are ambiguous, and only an unselected deployment with none installed resolves empty. */
    static Optional<GarbageCollector> resolve(UnaryOperator<String> config) {
        return resolve(providers(), config);
    }

    /** The collector providers installed, discovered here because discovery belongs to the SPI home. Not cached: a
     *  caller on a repeated path holds the list and hands it to {@link #resolve(Iterable, UnaryOperator)}. */
    static List<GarbageCollectorProvider> providers() {
        return ServiceLoader.load(GarbageCollectorProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /** The collector this configuration selects from {@code providers}. Only the discovery is fixed: which provider
     *  answers is decided per call from {@code jenrepo.gc} and each provider's required configuration, so a caller
     *  holding the list still follows a changed selection. */
    static Optional<GarbageCollector> resolve(Iterable<GarbageCollectorProvider> providers,
                                              UnaryOperator<String> config) {
        return Providers.optionalUnique("gc",
                providers,
                GarbageCollectorProvider::name,
                Features.selection("gc"),
                provider -> Features.active(provider.name(), provider.requiredConfig()),
                provider -> provider.create(config));
    }
}
