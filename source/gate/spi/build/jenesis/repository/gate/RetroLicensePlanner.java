package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * The dry-run seam for retroactive license enforcement: a {@code ServiceLoader}-discovered planner that reports what
 * the retro sweep <em>would</em> newly hold in a repository under the current license policy, so an operator can review
 * the blast radius before turning enforcement on. The heavy logic (the license policy, the declared-license sidecars)
 * rides in the {@code compliance/licenses} module that provides this seam; a thin {@code web} adapter resolves it
 * through {@link #installed} and answers {@code 501} when the module is absent, so the preview surface degrades
 * gracefully without either forking the policy or hard-wiring the endpoint to the plugin.
 *
 * <p>The plan is read-only - it enumerates the {@code published/} sidecars and reads the {@code licenses/} sidecars and
 * the hold/override markers, never an artifact blob and never a write - and lists only what a fresh enabling pass would
 * <em>newly</em> hold: a release already held (by this sweep, the gate or the KEV sweep) or already released by an
 * operator is excluded, so the count matches the number an enabling pass then holds.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One planner instance serves the deployment and {@link #plan} may be called concurrently
 *     for different repositories; the planner holds no per-call state - the scoped store and the config lookup are
 *     the only inputs.</li>
 * <li><b>Idempotency / replay.</b> {@link #plan} is a pure dry run: repeating it over unchanged state yields an
 *     equal {@link Plan}, and running it never brings the sweep's holds any closer to existing.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} when no license-policy module
 *     contributes a planner; the preview surface then answers {@code 501}. {@code null} is never a legal return
 *     from {@link #installed()} or {@link #plan} - a repository with nothing to hold answers an empty
 *     {@link Plan}, which is a different statement from "no planner is installed".</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a planner by name -
 *     so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two installed
 *     planners would make module-path order decide which policy the operator previews, so {@link #installed()}
 *     <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs through the shared
 *     {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The caller hands in an already-scoped repository store; the plan may read
 *     nothing outside it.</li>
 * <li><b>Read purity (&sect;10).</b> The plan is read-only: it enumerates {@code published/} sidecars and reads
 *     {@code licenses/} sidecars and hold/override markers, never an artifact blob, never an external fetch and
 *     never a write. A preview must stand when the license feeds are down.</li>
 * <li><b>Bounded work / cancellation.</b> The plan walks an existing corpus, so it is bounded by the repository's
 *     published set rather than by a cap; a caller that must bound it bounds the repository it asks about.
 *     {@link Plan#count()} always agrees with {@link Plan#held()}'s size - a truncated plan is never presented as a
 *     complete one.</li>
 * <li><b>Ordering / determinism.</b> Which planner {@link #installed()} answers is a function of what is installed,
 *     never of discovery order.</li>
 * </ol>
 */
public interface RetroLicensePlanner {

    /** Whether the {@code unknown-license} bucket is included - the {@code denied+unknown} mode's larger, riskier
     *  blast radius versus the {@code denied}-only high-confidence set. */
    Plan plan(UnaryOperator<String> config, ArtifactStore store, boolean includeUnknown) throws IOException;

    /** The prospective holds for one repository store: the count and the per-coordinate reasons a fresh enabling pass
     *  would write. */
    record Plan(int count, List<Held> held) {

        public Plan {
            held = List.copyOf(held);
        }
    }

    /** One release the sweep would newly hold, with the human-readable reasons behind the hold. */
    record Held(String ecosystem, String coordinate, String version, List<String> reasons) {

        public Held {
            reasons = List.copyOf(reasons);
        }
    }

    /** The installed planner, resolved through the shared {@link Providers#optionalUnique} policy: empty when no
     *  license-policy module contributes one - the signal a preview surface answers {@code 501} on - and a
     *  <em>second</em> installed planner throws rather than letting module-path order decide which policy is
     *  previewed. */
    static Optional<RetroLicensePlanner> installed() {
        return Providers.singleton("retro-license-planner", ServiceLoader.load(RetroLicensePlanner.class));
    }
}
