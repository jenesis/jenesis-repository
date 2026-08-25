package build.jenesis.repository.walk.testkit;

import module java.base;
import build.jenesis.repository.walk.ArtifactWalk;

/**
 * The walk the contract drives a fixture against, supplied by the JUnit driver rather than by the kit, for two
 * reasons: the kit stays on the walk <em>SPI</em> (it must not require a walk implementation module), and a crash
 * check needs two things a production walk does not expose - a checkpoint stride small enough that a corpus of a
 * couple of dozen artifacts really straddles several strides, and a way to make a dead worker's claim expire without
 * the suite sleeping through a real lease.
 *
 * <p>{@link #expireClaims()} is what stands in for "the crashed node stayed dead": a claim is only ever taken over
 * once it has expired (the walk refuses to steal a live holder's segment), so a resume is not even attempted until
 * time has moved past the lease.
 */
public interface WalkHarness {

    /** The walk under test, resolved the way a deployment resolves it. */
    ArtifactWalk walk();

    /** Items per durable cursor commit - the stride the crash points are positioned against. */
    int checkpoint();

    /** Move past the claim lease, so the segment a crashed worker still holds becomes claimable and the next pass
     *  resumes it from its last committed cursor. */
    void expireClaims();
}
