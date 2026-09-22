package build.jenesis.repository.compliance.testkit;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;

/**
 * One deliberately broken substitution for the dimension a {@link GatePolicyContract.Property} is about - and, unlike
 * every other kit in this family, it is injected at the <em>resolved policy</em> rather than at the fixture's provider
 *.
 *
 * <h2>Why the seam is here and not on the fixture</h2>
 * {@link GatePolicyContract} deliberately drives the dimension two ways: {@code provider.create(config, path)}, which a
 * fixture substitution could reach, and {@link GatePolicyProvider#resolve}, the SPI's own static {@code ServiceLoader}
 * path a publish or proxy screen really takes - which no fixture substitution can reach at all. Worse, the kit resolves
 * even its {@code create} leg through the <em>discovered</em> provider ({@code GatePolicyProvider.installed()}, looked
 * up by the class the fixture names) and only falls back to {@link GatePolicyFixture#provider()} when discovery misses.
 * A registered fixture's discovery never misses, so a probe that replaced {@code provider()} substituted an object the
 * kit never asked for.
 *
 * <p><b>That was measured, not reasoned.</b> tried exactly that probe and withdrew the number rather than ship
 * it; reproduced it and got the sharper reading: an inert dimension substituted at the fixture's provider
 * <em>appeared</em> to pass <b>64 of 64</b> checks, with all <b>8</b> fixtures wholly satisfied. Every fixture wholly
 * satisfied is not a vacuity measurement, it is the signature of a mutant that is never driven - which is why the kit's
 * mirror leg ("the probe must demonstrably bite on every fixture") exists and why a contaminated figure is worse than
 * no figure at all.
 *
 * <p>Substituting the <em>policy</em> instead reaches both entry points, because both hand a {@link GatePolicy} back:
 * {@link GatePolicyContract} routes every policy it obtains - through {@code create} and through {@code resolve} alike -
 * through {@link #substitute}. The dimension therefore still resolves, is still present exactly where its declared
 * {@link GatePolicyProvider.Symmetry} says it is, still reads its own dials and still refuses an unparseable one; only
 * what it <em>decides</em> is removed. That is the shape this SPI's own contract calls out by name - "the sentinel is
 * {@link java.util.Optional#empty()}, not a policy object that finds nothing" - so the probe asks exactly the question
 * that matters.
 *
 * <p>{@link #NONE} is the identity: the unmutated dimension every fixture's ordinary leg drives.
 */
public enum GatePolicyMutant {

    /** Nothing is removed - the deployment's own dimension, which is what the contract's ordinary leg drives. */
    NONE("nothing") {
        @Override
        public GatePolicy substitute(GatePolicy resolved) {
            return resolved;
        }
    },

    /**
     * Everything the dimension decides. It is built exactly as it would be, from the same settings, on the same legs,
     * through the same discovery - and then finds nothing about any subject. The inert-but-present shape the SPI's
     * absence clause forbids, and the one an operator cannot distinguish from a dimension that has nothing against the
     * repository.
     */
    A_DIMENSION_THAT_FINDS_NOTHING("everything the dimension decides - it still resolves, is still present exactly "
            + "where its declared symmetry says it is, and still reads and refuses its own dials; it simply finds "
            + "nothing about any subject") {
        @Override
        public GatePolicy substitute(GatePolicy resolved) {
            Objects.requireNonNull(resolved, "resolved");
            return (_, _) -> List.<ComplianceGate.Finding>of();
        }
    };

    private final String removes;

    GatePolicyMutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, for the failure message of a check that survived it. */
    public String removes() {
        return removes;
    }

    /** {@code resolved} with this mutant's behaviour removed. Applied by {@link GatePolicyContract} to every policy it
     *  obtains, whichever entry point produced it, so a substitution can never be one the kit does not drive. */
    public abstract GatePolicy substitute(GatePolicy resolved);
}
