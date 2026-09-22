package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A factory for a tenant's {@link Vex} gate view, discovered at runtime with {@link ServiceLoader} - so VEX
 * (Vulnerability Exploitability eXchange) suppression is a drop-in module that {@code provides} this interface, and the
 * neutral server names no {@code VexStore}. The heavy store-backed implementation (parsing and matching a tenant's
 * ingested OpenVEX / CSAF statements) rides the {@code vex} plugin; this contract stays thin, carrying only the tenant,
 * its {@link ArtifactStore} and the effective-settings lookup the feature toggle is read through. Mirrors the other
 * discovered compliance capabilities ({@code GatePolicyProvider}, {@code ProvenanceSignerProvider}): the server
 * resolves it once through {@link #resolve()} and overlays the per-tenant {@link Vex} on its publish- and proxy-path
 * gates.
 *
 * <p>Semantics fail toward screening (§9): the provider yields {@link Vex#NONE} - suppressing nothing - when the VEX
 * feature is off or the store read fails, never toward silently hiding a vulnerability; and a deployment with no VEX
 * plugin at all resolves the {@link #resolve() fallback} provider, which yields {@link Vex#NONE} for every tenant so
 * the server still boots and screens exactly as before.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves the deployment: {@link #over} is called per tenant, per
 *     screening, concurrently from every publish and proxy thread, so the provider and the {@link Vex} view it
 *     returns must both be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #over} is a pure binding: calling it twice for a tenant yields equivalent
 *     views over the same ingested statements and never ingests, rewrites or expires a statement.</li>
 * <li><b>Absence sentinel.</b> {@link Vex#NONE} - suppressing nothing - is the sentinel, and it is the answer in
 *     <em>both</em> absence shapes: no VEX plugin installed (the {@link #resolve() fallback} provider) and an
 *     installed plugin whose feature is off or whose store read failed. {@code null} is never a legal return; the
 *     gate must always receive a view it can consult.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a VEX source by
 *     name - so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two
 *     installed providers would make module-path order decide whose statements suppress a vulnerability, so
 *     {@link #resolve()} <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs
 *     through the shared {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The {@code tenant} handed to {@link #over} is already resolved and
 *     validated by the caller; the provider scopes the deployment root store to that tenant's reserved VEX space
 *     and may read no other tenant's statements.</li>
 * <li><b>Error visibility (&sect;9).</b> Failure is contained in exactly one direction: a store read that fails
 *     yields {@link Vex#NONE}, so the gate screens as if no statement existed. Failing <em>toward screening</em> is
 *     the only permitted degrade - a VEX read must never be swallowed into a suppression, because that hides a
 *     vulnerability rather than over-reporting one.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #over} and the view it returns render ingested statements from the
 *     store only; no external fetch happens on the screening path.</li>
 * <li><b>Lifecycle / ownership.</b> The server resolves the provider once and calls {@link #over} per screening;
 *     {@link #resolve()} builds at most one instance per call, caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #resolve()} answers is a function of what is installed,
 *     never of discovery order.</li>
 * </ol>
 */
@FunctionalInterface
public interface VexProvider {

    /** A tenant's ingested VEX statements as the gate's {@link Vex} view, read over its {@code store}. The
     *  {@code tenant} is an already-resolved, validated tenant (the caller resolves the default tenant and rejects an
     *  unusable name); {@code store} is the deployment root store the provider scopes to the tenant's reserved VEX
     *  space; {@code config} the effective-settings lookup (stored settings over the deployment configuration) the VEX
     *  feature toggle is read through. Returns {@link Vex#NONE} when the feature is off or the store read fails - fail
     *  toward screening, never toward hiding a vulnerability. */
    Vex over(String tenant, ArtifactStore store, UnaryOperator<String> config);

    /** The single VEX provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy, or a {@link Vex#NONE}-yielding fallback when no VEX plugin is
     *  installed - so a deployment without the {@code vex} module still boots and screens, its gate suppressing
     *  nothing. A <em>second</em> installed plugin throws rather than letting module-path order decide whose
     *  statements suppress a vulnerability. */
    static VexProvider resolve() {
        return Providers.singleton("vex", ServiceLoader.load(VexProvider.class))
                .orElse((tenant, store, config) -> Vex.NONE);
    }
}
