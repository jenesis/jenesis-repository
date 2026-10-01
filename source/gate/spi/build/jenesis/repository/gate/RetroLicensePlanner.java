package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * The dry-run seam for retroactive licence enforcement: reports what the retro sweep would newly hold in a repository
 * under the current licence policy, so an operator reviews the blast radius before enabling it. The policy lives in
 * the module that provides this seam; a web adapter resolves it through {@link #installed} and answers {@code 501}
 * without it.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One planner serves the deployment and {@link #plan} may run concurrently for different
 *     repositories; the scoped store and the config lookup are its only inputs.</li>
 * <li><b>Idempotency / replay.</b> {@link #plan} is a pure dry run: unchanged state yields an equal {@link Plan}.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} is empty when no module contributes a planner; {@link #plan}
 *     answers an empty {@link Plan} for nothing to hold. {@code null} is never legal.</li>
 * <li><b>Selection failure.</b> Nothing names a planner, so the only failure is ambiguity: two installed planners
 *     make {@link #installed()} throw naming both, through {@link Providers#singleton}.</li>
 * <li><b>Tenant scoping.</b> The caller hands in a scoped repository store, and the plan reads nothing outside
 *     it.</li>
 * <li><b>Read purity.</b> The plan reads the published releases' {@code licenses} sections and the hold and override
 *     markers - never a blob, an external source or a write - so a preview stands when the licence feeds are down. It
 *     excludes releases already held or released by an operator, so its count matches what an enabling pass
 *     holds.</li>
 * <li><b>Bounded work / cancellation.</b> Bounded by the repository's published set; {@link Plan#count()} always
 *     equals {@link Plan#held()}'s size, so a truncated plan never reads as complete.</li>
 * </ol>
 */
public interface RetroLicensePlanner {

    /** The releases a fresh enabling pass would newly hold in {@code store}; {@code includeUnknown} adds the
     *  unknown-licence bucket of the {@code denied+unknown} mode to the {@code denied} set. */
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

    /** The installed planner, empty when none is installed; a second installed planner throws. */
    static Optional<RetroLicensePlanner> installed() {
        return Providers.singleton("retro-license-planner", ServiceLoader.load(RetroLicensePlanner.class));
    }
}
