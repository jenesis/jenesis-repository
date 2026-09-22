package build.jenesis.repository.inventory;

import java.time.Duration;

/**
 * The two windows a rollup rebuild under concurrent publishes is built on, as durations a scenario can size a claim by.
 * {@link #ALLOWANCE} is how far past its own clock a rebuild sets its boundary, so a publisher on a peer whose clock
 * runs ahead by less still classifies the same way; {@link #GRACE} is how long after the boundary the walk begins, so
 * a publish stamped at or before it has landed its member before the walk can pass it. A publish whose write outlives
 * the grace is the one bounded drift the design admits - the next reconcile heals it - so a scenario that provokes a
 * storm during a rebuild may claim the folded rollup is true only when no publish took longer than this, and must
 * otherwise claim the healing. The values themselves live on {@code InventoryIdentity}, which is what applies them.
 */
public final class IdentityWindows {

    public static final Duration ALLOWANCE = Duration.ofSeconds(InventoryIdentity.ALLOWANCE_SECONDS);

    public static final Duration GRACE = Duration.ofSeconds(InventoryIdentity.GRACE_SECONDS);

    private IdentityWindows() {
    }
}
