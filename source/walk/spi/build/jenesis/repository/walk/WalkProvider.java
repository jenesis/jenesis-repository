package build.jenesis.repository.walk;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

import module java.base;

/**
 * A named factory for the shared {@link ArtifactWalk}, discovered at runtime with {@link ServiceLoader} - the API is
 * an SPI kept separate from its implementation, so the enumeration strategy can change without breaking a consumer.
 * An optional-unique SPI: at most one walk is enabled at a time, and {@code jenesis.repository.walk=<name>} selects it
 * by name when a deployment installs more than one (the {@code paged-descent} reference implementation is the one the
 * free distribution ships). Each provider reads its own settings through the {@code config} lookup (a property accessor
 * returning {@code null} when unset - {@code jenesis.walk.checkpoint}, {@code jenesis.walk.segments}, ... for the
 * reference implementation). With no module installed {@link #resolve} is empty: every walk-riding surface then
 * degrades gracefully - nothing enumerates, and the console / capabilities say so - exactly like retention with no
 * retention provider. They say so from {@link #resolve resolve(config).isPresent()}, not from {@link #installed()},
 * which answers a weaker, packaging question and is read by no production surface at all (D-164; see the method).
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs on the resolving thread. The {@link ArtifactWalk} it returns is shared by every
 *     walk-riding sweep, so <em>that</em> object must tolerate concurrent passes - the walk's own claim/lease
 *     mechanics, not this provider, decide who may run a pass.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} is a pure factory: building a walk starts nothing, claims nothing
 *     and persists nothing, so resolving twice is always safe. Crash-resume semantics belong to the walk's
 *     checkpoints, and re-running a pass converges rather than duplicating work.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a walk module is not an error: {@link #resolve} answers an
 *     empty {@link Optional} - never {@code null}, never an exception - and {@link #installed()} is {@code false}. A
 *     consumer must then degrade visibly (nothing enumerates, the capability surface says so) rather than
 *     hand-rolling its own listing loop. {@link #create} declines with an empty {@link Optional}; {@code null} is
 *     never a legal return from it, from {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> {@code jenesis.repository.walk=<name>} that
 *     no installed provider answers to, or whose provider declines, throws {@link IllegalStateException} at
 *     resolution naming the selection and the installed provider names. It does <em>not</em> resolve to empty:
 *     silently answering "no walk installed" to an operator who named one turns every sweep that rides the walk -
 *     garbage collection, reconcile, retroactive hold enforcement - into a no-op that looks like a healthy idle
 *     system. An explicit selection outranks the {@code jenesis.repository.<name>=false} toggle. Only an
 *     <em>unselected</em> deployment degrades to the empty sentinel.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Duplicate provider names, one provider registered
 *     twice, and more than one <em>enabled</em> walk with no selection to disambiguate them all throw, naming the
 *     candidates and the setting that resolves them - the walk a deployment gets is never decided by module-path
 *     order. A provider's own settings parse eagerly in {@link #create} and a malformed value fails loudly rather
 *     than enumerating with a silently-wrong stride.</li>
 * <li><b>Lifecycle / ownership.</b> The caller owns the resolved walk: {@link #resolve} builds at most one instance
 *     per call, caches nothing and closes nothing. Provider instances are created by {@link ServiceLoader}, consulted
 *     and discarded, so a provider must be a cheap, stateless factory holding no state a later call depends on.</li>
 * <li><b>Ordering / determinism.</b> The resolved walk is a function of the configuration and the installed providers
 *     only, never of discovery order; {@link #installed()} answers the same on every module path.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #create} itself does no I/O and no unbounded work - it parses
 *     settings and constructs. The bounds that matter (checkpoint stride, segment fan-out, per-pass claim TTL) are
 *     the walk's own, declared through its settings, and a pass that reaches one persists its cursor and reports an
 *     incomplete pass rather than an apparently complete one.</li>
 * </ol>
 */
public interface WalkProvider {

    /** The implementation name this provider answers to, e.g. {@code paged-descent} - and, because
     *  {@link Features} spends one namespace on both shapes, the key {@code jenesis.repository.<name>=false}
     *  switches it off by. It may therefore not be the name of any SPI <em>family</em>: a walk called {@code store}
     *  keyed its toggle to the artifact store's selection key (D-005). */
    String name();

    /** Build the walk, reading settings through {@code config}; empty when configured off. */
    Optional<ArtifactWalk> create(UnaryOperator<String> config);

    /** The config keys this implementation cannot run without; empty (the default) for one that needs nothing. A
     *  provider whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Whether a walk implementation is installed and not switched off.
     *
     * <p><b>No production surface reads this</b>, and this javadoc asserted that a console and a walk-riding
     * maintenance surface gated on it for as long as neither did - D-164. The reader that exists is the reclamation
     * module's {@code CapabilityContributor}, which reports the {@code walk} flag from
     * {@link #resolve resolve(config).isPresent()}, and every walk-riding pass resolves the walk itself.
     *
     * <p><b>It is also not the same question, which is why it must not be adopted as one.</b> This answers
     * {@link Features#enabled}: a provider whose {@link #requiredConfig} keys are unset counts as installed here while
     * {@link #resolve} - which asks {@link Features#active} - reports it absent, and two enabled providers count here
     * while {@link #resolve} refuses them as ambiguous. A surface gated on this would therefore open for a walk that
     * self-disabled, and open just before {@link #resolve} throws. {@code resolve(config).isPresent()} is the
     * capability question; this static answers a packaging one, and its readers are {@code test/walk} and the
     * walk-consumer census.
     */
    static boolean installed() {
        return !Providers.installedNames("walk",
                ServiceLoader.load(WalkProvider.class),
                WalkProvider::name,
                provider -> Features.enabled(provider.name())).isEmpty();
    }

    /** The single enabled walk discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenesis.repository.walk=<name>} selects one by name
     *  and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenesis.repository.<name>=false} switches one off, more than one enabled walk is ambiguous rather than a
     *  discovery-order winner, and only an <em>unselected</em> deployment with no walk installed resolves to empty -
     *  the degrade-gracefully signal, never {@code null}. */
    static Optional<ArtifactWalk> resolve(UnaryOperator<String> config) {
        return Providers.optionalUnique("walk",
                ServiceLoader.load(WalkProvider.class),
                WalkProvider::name,
                Features.selection("walk"),
                provider -> Features.active(provider.name(), provider.requiredConfig()),
                provider -> provider.create(config));
    }
}
