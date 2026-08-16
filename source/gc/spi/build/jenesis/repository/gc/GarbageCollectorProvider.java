package build.jenesis.repository.gc;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

import module java.base;

/**
 * A named factory for a {@link GarbageCollector}, discovered at runtime with {@link ServiceLoader} - the API is an
 * SPI kept separate from its implementation, so the reclamation strategy can change without breaking a caller. An
 * optional-unique SPI: at most one collector is enabled at a time, and {@code jenesis.repository.gc=<name>} selects it
 * by name when a deployment installs more than one (the {@code mark-sweep} reference implementation is the one the
 * free distribution ships). Each provider reads its own settings through the {@code config} lookup (a property
 * accessor returning {@code null} when unset - {@code jenesis.gc.*}). With no module installed {@link #resolve} is
 * empty: <b>nothing is ever reclaimed</b> and the capability surfaces say garbage collection is off - the no-op
 * default, because deleting data is never something a deployment gets without opting in. Those surfaces report
 * {@link #resolve resolve(config).isPresent()} and not {@link #installed()}, which answers a weaker, packaging
 * question and is read by no production surface at all (D-164; see the method).
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs on the resolving thread. The {@link GarbageCollector} it returns is shared by the
 *     maintenance surfaces that drive it, and single-writer safety across nodes is the collector's own lease
 *     business, not this provider's.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building a collector claims nothing, deletes
 *     nothing and persists nothing, so resolving twice is safe. Re-running a collection pass converges - the
 *     generation/grace protocol means a repeated pass never deletes a blob the previous one would have spared.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a collector is not an error: {@link #resolve} answers an
 *     empty {@link Optional} - never {@code null}, never an exception - and {@link #installed()} is {@code false}.
 *     Empty means <em>nothing is ever reclaimed</em>, the deliberate no-op default for the one unrecoverable
 *     operation. {@link #create} also declines with an empty {@link Optional} when a capability it rides (the shared
 *     artifact walk) is absent, so a deployment without enumeration never gets a collector that enumerates its own
 *     way. {@code null} is never a legal return from {@link #create}, {@link #name()} or
 *     {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> {@code jenesis.repository.gc=<name>} that no
 *     installed provider answers to, or whose provider declines, throws {@link IllegalStateException} at resolution
 *     naming the selection and the installed provider names - it does not resolve to the no-op default. An operator
 *     who named a collector and silently got none would believe reclamation is running while storage grows without
 *     bound. An explicit selection outranks the {@code jenesis.repository.<name>=false} toggle. Only an
 *     <em>unselected</em> deployment degrades to empty.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Duplicate provider names, one provider registered
 *     twice, and more than one <em>enabled</em> collector with no selection to disambiguate them all throw, naming
 *     the candidates and the setting that resolves them - which collector deletes a deployment's data is never
 *     decided by module-path order. Malformed settings ({@code jenesis.gc.stride}, {@code jenesis.gc.grace}) fail
 *     loudly in {@link #create} rather than collecting with a silently-wrong stride or grace.</li>
 * <li><b>Lifecycle / ownership.</b> The caller owns the resolved collector: {@link #resolve} builds at most one
 *     instance per call, caches nothing and closes nothing. Provider instances are created by {@link ServiceLoader},
 *     consulted and discarded, so a provider must be a cheap, stateless factory.</li>
 * <li><b>Ordering / determinism.</b> The resolved collector is a function of the configuration and the installed
 *     providers only, never of discovery order; {@link #installed()} answers the same on every module path.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} does no I/O and no unbounded work. A pass's bounds - the
 *     reference batch a mark buffers, the checkpoint stride, the condemn-to-collect grace - are the collector's own,
 *     declared through its settings, and reaching one leaves a resumable, safely-incomplete pass rather than a
 *     partial deletion presented as a complete sweep.</li>
 * </ol>
 */
public interface GarbageCollectorProvider {

    /** The implementation name this provider answers to, e.g. {@code mark-sweep}. */
    String name();

    /** Build the collector, reading settings through {@code config}; empty when configured off or when a
     *  capability it rides (the shared artifact walk) is itself absent - either way the caller reclaims nothing. */
    Optional<GarbageCollector> create(UnaryOperator<String> config);

    /** The config keys this implementation cannot run without; empty (the default) for one that needs nothing. A
     *  provider whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Whether a garbage collector is installed and not switched off.
     *
     * <p><b>No production surface reads this</b>, and this javadoc asserted that a console and a maintenance surface
     * gated on it for as long as neither did - D-164. The reader that exists is the reclamation module's
     * {@code CapabilityContributor}, which reports the {@code gc} flag from
     * {@link #resolve resolve(config).isPresent()}.
     *
     * <p><b>It is also not the same question, which is why it must not be adopted as one.</b> This answers
     * {@link Features#enabled}: a collector whose {@link #requiredConfig} keys are unset counts as installed here
     * while {@link #resolve} - which asks {@link Features#active} - reports it absent, and two enabled collectors
     * count here while {@link #resolve} refuses them as ambiguous. A surface gated on this would promise reclamation
     * that is not going to happen. {@code resolve(config).isPresent()} is the capability question; this static answers
     * a packaging one, and its reader is {@code test/gc}.
     */
    static boolean installed() {
        return !Providers.installedNames("gc",
                ServiceLoader.load(GarbageCollectorProvider.class),
                GarbageCollectorProvider::name,
                provider -> Features.enabled(provider.name())).isEmpty();
    }

    /** The single enabled collector discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenesis.repository.gc=<name>} selects one by name
     *  and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenesis.repository.<name>=false} switches one off, more than one enabled collector is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no collector installed resolves
     *  to empty - the no-op default, never {@code null}. */
    static Optional<GarbageCollector> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("gc",
                ServiceLoader.load(GarbageCollectorProvider.class),
                GarbageCollectorProvider::name,
                Features.selection("gc"),
                provider -> Features.active(provider.name(), provider.requiredConfig()),
                provider -> provider.create(config));
    }
}
