package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link GatePolicy}, discovered at runtime with {@link ServiceLoader} - so a gate dimension
 * (the license policy, the known-exploited check) is a drop-in module that {@code provides} this interface, and the
 * gate names no dimension. Each provider reads its own configuration through the {@code config} lookup (a
 * property/setting accessor returning {@code null} when unset), staying free of any framework dependency, and
 * yields empty when its dimension has nothing to gate on. A provider is asked once per gate flavor: the
 * {@link Path#PUBLISH publish} and {@link Path#PROXY proxy-fetch} paths run the same dimensions, but a policy may
 * soften itself for the proxy (an unknown license is allowed there, since a proxied artifact's descriptor is often
 * not yet cached) or skip the flavor entirely - declared once through {@link #symmetry()}.
 *
 * <p>The plumbing every dimension shares - reading a dial, yielding nothing when the dimension is unconfigured, and
 * throwing with the offending key named when it is misconfigured - is {@link GateDimension}, an immutable value a
 * provider composes with rather than a base class it extends.
 *
 * <h2>Contract</h2>
 *
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance per deployment, created once by {@link ServiceLoader}. Both
 * {@link #name()} and {@link #create} may be called concurrently: the console validates a candidate settings write
 * on a request thread while the scheduled re-read rebuilds the live gate. A provider therefore holds no mutable
 * state; the per-call {@link GateDimension} is immutable and dies with the call. The {@link GatePolicy} it returns
 * is called concurrently from every publish and proxy request thread and must be immutable too.</li>
 *
 * <li><b>Idempotency / replay.</b> {@code create} is a pure function of {@code config} and {@code path}: the same
 * lookup must yield an equivalent dimension however often it is called, and building one must have no side effect
 * an operator could observe - the console builds a whole throwaway gate purely to <em>validate</em> a candidate
 * value and discards it. {@link GatePolicy#assess} is pure in the same sense: identical subject and advisories
 * yield identical findings, in any order, on either leg, so a re-screen of a stored artifact reproduces the verdict
 * it was published under rather than drifting.</li>
 *
 * <li><b>Absence sentinel.</b> {@link Optional#empty()} - never {@code null}, and never a policy object that finds
 * nothing - means "this deployment does not carry this dimension": the module is installed but the operator
 * configured no floor, no reserved name, no rule, or no catalogue answers. An inert policy would be
 * indistinguishable from an active one in every surface that counts the gate's dimensions, and "always allows" is
 * exactly the shape a silent misconfiguration takes. {@link GatePolicy#assess} likewise returns an empty
 * {@code List}, never {@code null}, for a subject it has nothing against.</li>
 *
 * <li><b>Selection failure.</b> Dimensions are an additive family: every enabled provider contributes, none is
 * selected by name, so there is no "selected but missing" resolution to fail. The corresponding fail-fast duty is
 * on configuration instead - a dial an operator <em>did</em> set that does not parse must throw out of
 * {@code create}, naming the offending key (&sect;9), so the settings writer rolls back to the last good
 * gate and the boot / scheduled re-read refuses to run a gate the operator did not ask for. Falling back to a
 * default here would loosen the gate silently. Absence of configuration is not failure: it degrades to the clause-3
 * sentinel. A provider whose {@link #requiredConfig()} keys are unset self-disables with one log line rather than
 * throwing, because that is unconfigured, not misconfigured. The duty is on the dimension the operator is actually
 * running: {@link #resolve} filters on enablement <em>before</em> it asks anyone to create anything, so an unparseable
 * dial belonging to a dimension switched off with {@code jenreg.<name>=false} does not fail the rebuild -
 * the toggle is the operator's way out of a dimension they cannot presently configure correctly, and refusing to boot
 * over a dial nothing reads would make the off switch unusable. The verdict a dial <em>does</em> name never decides
 * presence either (see {@link GateDimension}): an {@code <dim>-action} of {@link Verdict#ALLOW} evaluates and permits.
 * What <em>is</em> a resolution failure here is a
 * packaging one: two providers answering to the same {@link #name()}, or one provider registered twice, throw out of
 * {@link #resolve} naming what collided. A shared name is a shared {@code jenreg.<name>} toggle, so the
 * operator's off switch would silently reach a dimension they never meant to disable.</li>
 *
 * <li><b>What a permit reports.</b> A dimension carrying an {@code <dim>-action} dial evaluates its subject
 * under every verdict that dial can name, {@link Verdict#ALLOW} included, and reports what it found at the verdict
 * named: a permitted subject yields {@code Finding(ALLOW, <why>)}, the same shape {@link ComplianceGate} already uses
 * for a VEX-suppressed or waived advisory. It does not return before looking at the subject, and it does not evaluate
 * and drop the finding. The reason is the clause above stated at the reporting end: a dimension that permits silently
 * produces an assessment byte-identical to one it had nothing against, so the findings ledger, the quarantine review
 * log and every report lose the difference between "this deployment decided to let that through" and "there was
 * nothing to let through" - and an incident review cannot recover it. The cost is accepted deliberately: artifacts
 * that <em>pass</em> now carry informational findings. A dimension with no single verdict dial (the policy-as-code
 * one, whose verdict rides each rule) has no ALLOW to report under and is exempt by construction, which its contract
 * kit's census records rather than the dimension declaring it of itself.</li>
 *
 * <li><b>Streaming.</b> Not applicable: a dimension sees an already-parsed {@link ComplianceGate.Subject} and the
 * gate's shared advisory lookup, never an artifact body. Reading the artifact is {@link QualityInspector}'s job and
 * is bounded there.</li>
 *
 * <li><b>Tenant scoping.</b> The {@code config} lookup handed in is already the effective per-tenant view (the
 * tenant's overrides over the deployment's settings over file/env), so a provider must read every dial through it
 * and never reach for a global property or environment variable of its own - that is how one tenant's floor stays
 * one tenant's floor. Subjects arrive already scoped to the publishing or fetching tenant.</li>
 *
 * <li><b>Error visibility.</b> Nothing on this path is best-effort. A failure to build a dimension propagates out
 * of {@code create} and aborts the whole gate rebuild, because a gate quietly missing one dimension serves and
 * publishes artifacts nothing screened for it; the caller keeps the last good gate rather than installing a partial
 * one. A failure inside {@link GatePolicy#assess} propagates to the screen, which fails the publish or refuses the
 * proxied body closed. A dimension must never swallow an exception into "no findings": the blast radius of a lost
 * finding is an artifact served or published that the operator's policy would have held.</li>
 *
 * <li><b>Read purity.</b> {@code create} reads configuration only. {@link GatePolicy#assess} performs no I/O of its
 * own: it reads the subject, the advisories the gate looked up once for all dimensions, and any signal source it
 * was built with - and those sources answer from their persisted snapshot, so a screen renders stored state and
 * never fetches (&sect;10).</li>
 *
 * <li><b>Staleness.</b> A dimension holds no freshness of its own; where it reads an externally-sourced signal the
 * staleness is the source's and is surfaced there ({@link KnownExploitedSource}, {@link HealthSource}). A source
 * that cannot answer degrades to no finding rather than to a fabricated one, so an empty result never means "the
 * feed was down" - that is the source's health surface to report.</li>
 *
 * <li><b>Lifecycle / ownership.</b> The provider is {@link ServiceLoader}-created and lives for the deployment; it
 * owns no threads, clients or connections. Its {@link GatePolicy} products are short-lived and rebuilt on every
 * settings change, so a policy must not own anything needing closing either - a shared signal source is resolved
 * through its own memoized seam and outlives any one policy.</li>
 *
 * <li><b>Ordering / concurrency.</b> {@link #resolve} sorts providers by {@link #name()} - through the shared
 * {@link Providers#all} primitive, which sorts before it filters or creates anything - so the findings order is
 * deterministic across discovery order; the verdict fold itself is order-independent, so no dimension may depend on
 * running before or after another, and every dimension sees the same subject and the same advisory list. Two
 * providers cannot share a sort key, because a duplicate name throws rather than resolving by discovery order.
 * {@link GatePolicy#assess} is order-independent in the caller's direction too: the findings for one subject depend on
 * that subject and its advisories alone, never on which subjects were assessed before it or on the order the shared
 * advisory list arrives in, so a re-screen that walks the store in a different order than the publishes did reproduces
 * every verdict rather than drifting.</li>
 *
 * <li><b>Declared asymmetry.</b> {@link #symmetry()} is honoured in <em>both</em> directions, and it is the
 * declaration that produces the behaviour rather than a description of it. A {@link Symmetry#PROXY_ONLY} dimension is
 * built for no other flavor; a {@link Symmetry#SOFTENED_ON_PROXY} one is built for both and must really be weaker on
 * the proxy for at least one subject - a dimension declaring a softening it does not perform is as wrong as one
 * performing a softening it does not declare; and a {@link Symmetry#SYMMETRIC} one must reach the <em>same</em>
 * findings on both legs for the same subject and advisories, because that is exactly what the declaration promises a
 * reader, an operator and the re-screen that assesses a stored artifact through whichever flavor it was reached
 * by.</li>
 *
 * <li><b>Bounded work / cancellation.</b> Both {@code create} and {@code assess} are bounded by their inputs -
 * configured rules and the subject's own findings - and neither blocks on I/O, so there is no cancellation seam. A
 * dimension needing an external answer takes it from a source that already bounds and persists its own fetching.</li>
 *
 * <li><b>Durability / delivery.</b> Not applicable: a dimension decides, it does not persist. The screen that
 * called in owns the commit point and the durability of the verdict it records.</li>
 * </ol>
 */
public interface GatePolicyProvider {

    /** The gate flavor a policy is built for. */
    enum Path {
        PUBLISH, PROXY
    }

    /**
     * How a dimension's two gate flavors relate - the publish/proxy asymmetry, declared as data rather than left to
     * a comment beside a {@code path} check. {@link GateDimension#of} and {@link #resolve} both honour the
     * declaration, so a dimension that declares it does not gate a flavor cannot be built for it.
     */
    enum Symmetry {

        /** The same dimension on both legs: what it gates on is present, and equally risky, whether the artifact was
         *  uploaded or pulled through. */
        SYMMETRIC,

        /** Built on both legs, but deliberately weaker on the proxy: the license dimension allows an unknown license
         *  there, since a proxied artifact's descriptor is often not yet cached. */
        SOFTENED_ON_PROXY,

        /** Built for {@link Path#PROXY} only: on the publish path the dimension has nothing to say. The reserved
         *  private names are the case - a private coordinate being published is exactly what the tenant intends,
         *  while the same coordinate arriving from an upstream is the dependency-confusion shadow. */
        PROXY_ONLY;

        /** Whether a dimension declaring this symmetry is built at all for {@code path}. */
        public boolean carries(Path path) {
            return this != PROXY_ONLY || path == Path.PROXY;
        }
    }

    /** The dimension name this provider answers to, e.g. {@code licenses}, {@code known-exploited}. */
    String name();

    /** Build the dimension for one gate flavor, reading settings through {@code config}; empty when it has nothing
     *  to gate on. A value that does not parse should throw naming its key, so a live settings rebuild can reject
     *  and roll back - {@link GateDimension} is the shared plumbing for both. */
    Optional<GatePolicy> create(UnaryOperator<String> config, Path path);

    /** How this dimension's publish and proxy legs relate; {@link Symmetry#SYMMETRIC} (the default) for a dimension
     *  gating both alike. */
    default Symmetry symmetry() {
        return Symmetry.SYMMETRIC;
    }

    /** The config keys this dimension cannot run without (a licensed credential, say); empty (the default) for one
     *  that needs nothing. A provider whose required keys are unset {@link Features#active self-disables} at
     *  discovery with one log line. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Every enabled dimension discovered via {@link ServiceLoader}, sorted by provider name so the findings order is
     * deterministic (the verdict fold itself is order-independent); empty when none is installed. A provider whose
     * declared {@link #symmetry()} does not carry {@code path} is not asked at all, and one that is switched off - or
     * whose {@link #requiredConfig()} keys are unset - is never asked to create anything.
     *
     * <p>The iterate-filter-create loop itself is the shared {@link Providers#all ALL-policy primitive}, not a copy of
     * it: dimensions are additive, so there is no selection to miss here, but a <em>duplicate</em> provider name is
     * still a packaging error this family had been the last to tolerate. Two providers answering to one name share one
     * {@code jenreg.<name>} toggle - switching one off switches both off, and the operator has no key that
     * names either - so the primitive throws, naming both classes and the name that collided, exactly as it does for
     * the unique and exclusive families. The same holds for one provider registered twice.
     */
    static List<GatePolicy> resolve(UnaryOperator<String> config, Path path) {
        return Providers.all("gate-policy",
                ServiceLoader.load(GatePolicyProvider.class),
                GatePolicyProvider::name,
                // A dimension that declares it does not carry this flavour is not asked, and neither is one switched
                // off or missing the configuration it cannot run without.
                provider -> provider.symmetry().carries(path)
                        && Features.active(config, provider.name(), provider.requiredConfig()),
                provider -> provider.create(config, path));
    }

    /**
     * Every dimension provider installed on this deployment, whatever its configuration, name-sorted - the discovery
     * seam {@link #resolve} deliberately does not expose, because {@code resolve} answers with anonymous
     * {@link GatePolicy} products and a caller that needs to know <em>which</em> dimensions a deployment carries
     * (a console listing them, the contract suite driving each one) cannot recover a name from a product.
     *
     * <p>It is the same {@link Providers#all ALL-policy primitive} {@code resolve} runs on, with the enablement
     * predicate opened out to "installed" - so a duplicate provider name or class is a packaging error here too, and
     * a caller never sees a discovery-order list. It is deliberately <em>not</em> a second discovery pipeline: the
     * {@code uses} clause and the {@link ServiceLoader} call stay in this one module beside the contract, exactly as
     * {@link QualityInspector#all()} does for the inspectors.
     */
    static List<GatePolicyProvider> installed() {
        return Providers.all("gate-policy",
                ServiceLoader.load(GatePolicyProvider.class),
                GatePolicyProvider::name,
                _ -> true,
                Optional::of);
    }
}
