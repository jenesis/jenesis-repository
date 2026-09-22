package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.store.Stamp;
import build.jenesis.repository.store.StoredCounter;

/**
 * The cadence every feed-driven pass keeps: every Nth scheduled pass visits every published version, and the passes
 * between visit only the versions published since the last full one - newest first out of the recent index, never a
 * walk of the publish facts. Measured 2026-09-06 for the advisory scan alone: a full pass reads one inventory document
 * per published version, which at two million versions and an hourly cadence was 48 million reads a day over an
 * object store; the shape was then copied into the other passes that read the feeds, each with its own counter and
 * its own idea of "since", and this is that shape once.
 *
 * <p>Three things make a pass full: nothing recorded as a last full pass (a first run, or a stamp that was never
 * advanced because a pass did not land clean); the counted passes since the last full one reaching the deployment's
 * {@value #FULL_EVERY}; and a standing {@linkplain Requests request} naming the task - what the signal refresh
 * leaves when a catalogue it drew changed, since a new entry on an old version is exactly what the incremental leg
 * cannot see. The pass says which leg it ran through {@link #full()} and stamps only a clean full pass
 * ({@link #completed}): the stamp is the claim "everything published before this instant was visited", and an
 * incremental pass cannot make it.
 *
 * <p>The bookkeeping is two small objects under the pass's own space: the count of passes since the last full one
 * (a {@link StoredCounter}, folded per flush) and the last full pass's instant (a {@link Stamp}, which a pass that
 * already keeps a freshness stamp for its views hands in rather than keeping a second).
 *
 * <h2>The stamp's claim is stronger than the enumeration behind it</h2>
 *
 * <p><b>Open finding, demonstrated 2026-09-16.</b> "Everything published before this instant was visited" is a
 * claim about two different things at once, and only one of them is what a full pass measures. A full pass
 * enumerates the published key space LIVE and in KEY order; the stamp it writes is its own start instant. So a
 * version whose publish instant is before that start but whose ROW is written after the pass has passed the key it
 * sorts at was never visited, and the stamp says it was. The incremental legs then filter on the stamp, so it is
 * invisible to all of them until the next full pass.
 *
 * <p>Three different things again, as in {@code InventoryIdentity}, which carries the same gap in its rebuild and
 * closes it: the classification is on the PUBLISH INSTANT, the coverage is on WHEN THE ROW WAS WRITTEN and WHERE
 * ITS KEY SORTS. The window that produces it is ordinary rather than exotic - a publish in flight when a full pass
 * begins has an instant just before the start and a row just after it, and if its coordinate sorts early the pass
 * has already gone by.
 *
 * <p>Demonstrated directly rather than argued: record one version, run a full pass, stamp it, then record a
 * version dated thirty seconds BEFORE the stamp - a write that outlived the pass, or a publisher whose clock
 * lags - and ask for an incremental pass. It visits NOTHING, not merely "everything but the late one":
 * {@link #recent} returns at the first release below the floor, and the late release is the newest in the recent
 * index, so the floor is met on the first row and the leg returns having visited none of them.
 *
 * <p>What it costs is bounded and self-healing, which is the difference from the identity's version of this gap:
 * the next FULL pass visits every published version whatever its instant, so the exposure was at most
 * {@value #FULL_EVERY} passes - a day at the default dial and an hourly cadence. For the passes that ride this (the
 * advisory scan, KEV enforcement, reanalysis, the signature sweep, health and reachability) that is a published
 * artifact going unscanned for up to a day, not a wrong answer served to a client.
 *
 * <p><strong>Closed by a LOOKBACK</strong> ({@value #LOOKBACK}, {@value #DEFAULT_LOOKBACK}): an incremental pass
 * floors at the stamp MINUS that window rather than at the stamp, so a row that landed behind a pass is visited by
 * the next incremental one instead of waiting for the next full one. Unlike the identity's fold it is safe to
 * over-cover here - these passes recompute derived state and are idempotent, so visiting a version twice costs
 * work and changes nothing - which is why no handoff counter is needed and a plain window does.
 *
 * <p><b>Where else this shape lives.</b> Two things have to meet for it: derived state whose coverage is claimed
 * by an INSTANT, and a walk that establishes that coverage by a LIVE, KEY-ORDERED enumeration. The probe that
 * finds them is a comparison between an item's publish instant and a coverage floor - {@code grep -rn
 * "published()\.isBefore\|published()\.isAfter"} over both source trees - and when the retrofit was run on
 * 2026-09-17 it returned exactly two, this one and {@code InventoryIdentity}'s rebuild boundary, both now closed.
 * Every other stamp in the build is either a freshness label a view renders, or a composite cache token compared
 * for EQUALITY so that a mismatch rebuilds the whole generation rather than filtering by instant - which is the
 * shape that cannot have this defect, and the one to prefer when the choice is open.
 *
 * <p>The early return in {@link #recent} rests on the index being ordered by publish instant rather than by
 * insertion - {@code RecentReleases} keys it by the instant inverted, so once a row is below the floor every row
 * after it is too. An insertion-ordered index would make that return wrong, and the two orderings are
 * indistinguishable from the test's end: both explain a leg that comes back empty.
 *
 * <p>It is not free, and that is why it is a dial rather than a constant: the lookback is paid by EVERY incremental
 * pass as publish-rate times window in extra visits, and a visit here is a SCAN rather than a read. So the default
 * is sized at the write lag it has to cover - a publish in flight when a full pass began, which is seconds - and
 * not at a clock skew, which is what the full pass remains for. A deployment publishing fast enough for a minute's
 * worth of re-scans to matter turns it down; one with lagging publisher clocks turns it up. Zero restores the
 * behaviour this paragraph describes as the defect, which is why it is accepted rather than refused: an operator
 * who has measured the cost may choose it, and the full pass still heals.
 */
public final class IncrementalPasses {

    /** The cadence dial, shared by every pass that reads the feeds: bare key, as every dial's,
     *  {@code jenreg.scan-full-every}. */
    public static final String FULL_EVERY = "scan-full-every";

    public static final int DEFAULT_FULL_EVERY = 24;

    /** The lookback dial, shared the same way: {@code jenreg.scan-lookback}. How far BEFORE the last full pass's
     *  stamp an incremental pass still looks - see the finding above, which it closes. */
    public static final String LOOKBACK = "scan-lookback";

    /** A minute: long enough to cover a publish that was in flight when a full pass began, short enough that the
     *  re-scans it buys are a minute's worth of publishing rather than an hour's. */
    public static final String DEFAULT_LOOKBACK = "PT1M";

    /** How many of the newest releases one incremental page asks the inventory for. */
    private static final int RECENT_PAGE = 500;

    private final StoredCounter passes;

    private final Stamp lastFull;

    private final Optional<Instant> since;

    private final Duration lookback;

    private final boolean full;

    private final String reason;

    private IncrementalPasses(StoredCounter passes, Stamp lastFull, Optional<Instant> since, Duration lookback,
                              boolean full, String reason) {
        this.passes = passes;
        this.lastFull = lastFull;
        this.since = since;
        this.lookback = lookback;
        this.full = full;
        this.reason = reason;
    }

    /**
     * Decide this pass's leg for the task named {@code task} over {@code store}, counting under {@code passesKey} and
     * reading the last full pass from {@code lastFull}.
     */
    public static IncrementalPasses over(ArtifactStore store, String task, String passesKey, Stamp lastFull,
                                         UnaryOperator<String> config) throws IOException {
        StoredCounter passes = new StoredCounter(store, passesKey);
        Optional<Instant> since = lastFull.read();
        Duration lookback = lookback(config);
        if (since.isEmpty()) {
            return new IncrementalPasses(passes, lastFull, since, lookback, true, "no full pass has landed yet");
        }
        long count = passes.read();
        int every = fullEvery(config);
        if (count + 1 >= every) {
            return new IncrementalPasses(passes, lastFull, since, lookback, true,
                    "the " + (count + 1) + "th pass since the last full one, at " + FULL_EVERY + "=" + every);
        }
        Optional<ArtifactStore> root = Requests.root();
        if (root.isPresent()) {
            Optional<Requests.Request> requested = Requests.pending(root.get(), task);
            if (requested.isPresent()) {
                return new IncrementalPasses(passes, lastFull, since, lookback, true,
                        "requested: " + requested.get().reason());
            }
        }
        return new IncrementalPasses(passes, lastFull, since, lookback, false,
                "incremental since " + since.get() + " less the " + LOOKBACK + " of " + lookback);
    }

    /** {@link #over(ArtifactStore, String, String, Stamp, UnaryOperator)} with the pass's own stamp under {@code space}. */
    public static IncrementalPasses over(ArtifactStore store, String task, String space, UnaryOperator<String> config)
            throws IOException {
        return over(store, task, space + "-passes", new Stamp(store, space + "-full"), config);
    }

    /** Whether this pass visits every published version. */
    public boolean full() {
        return full;
    }

    /** Why this pass took the leg it took, for a log line. */
    public String reason() {
        return reason;
    }

    /** The last full pass's instant: what an incremental pass visits since. */
    public Optional<Instant> since() {
        return since;
    }

    /** Every published version on a full pass; the versions published since the last full pass otherwise. */
    public void coordinates(StoreRepositoryInventory inventory, StoreRepositoryInventory.CoordinateVisitor visitor)
            throws IOException {
        if (full) {
            inventory.coordinates(visitor);
            return;
        }
        recent(inventory, release -> visitor.accept(
                new StoreRepositoryInventory.Coordinate(release.ecosystem(), release.coordinate(), release.version())));
    }

    /** Every published release on a full pass; the releases published since the last full pass otherwise. */
    public void releases(StoreRepositoryInventory inventory, RepositoryInventory.ReleaseVisitor visitor)
            throws IOException {
        if (full) {
            inventory.releases(visitor);
            return;
        }
        recent(inventory, visitor);
    }

    /**
     * The pass is over. A clean full pass stamps its instant and resets the count; a full pass that did not land
     * clean leaves the stamp where it was, so the next pass is full again; an incremental pass counts itself.
     */
    public void completed(Instant now, boolean clean) throws IOException {
        if (!full) {
            passes.add(1);     // one compare-and-set per incremental pass, read back by the next one at once
            return;
        }
        if (clean) {
            lastFull.mark(now);
            passes.set(0);
        }
    }

    public static int fullEvery(UnaryOperator<String> config) {
        String value = config == null ? null : config.apply(FULL_EVERY);
        if (value == null || value.isBlank()) {
            return DEFAULT_FULL_EVERY;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException _) {
            return DEFAULT_FULL_EVERY;
        }
    }

    /**
     * The lookback an incremental pass floors below the last full pass's stamp, from {@code config} - degrading to
     * {@value #DEFAULT_LOOKBACK} on anything the one duration grammar refuses, and never negative, which would move
     * the floor the wrong way and hide what the stamp already covers.
     */
    public static Duration lookback(UnaryOperator<String> config) {
        String value = config == null ? null : config.apply(LOOKBACK);
        if (value == null || value.isBlank()) {
            return Durations.parse(DEFAULT_LOOKBACK);
        }
        try {
            Duration parsed = Durations.parse(value);
            return parsed.isNegative() ? Duration.ZERO : parsed;
        } catch (RuntimeException _) {
            return Durations.parse(DEFAULT_LOOKBACK);
        }
    }

    /** Every release published since the last full pass LESS the lookback, newest first out of the recent index -
     *  the window that carries a row which landed behind a full pass, per the finding on this class. */
    private void recent(StoreRepositoryInventory inventory, RepositoryInventory.ReleaseVisitor visitor)
            throws IOException {
        Instant floor = since.orElseThrow().minus(lookback);
        String after = null;
        do {
            StoreRepositoryInventory.ReleasePage page = inventory.recent(after, RECENT_PAGE);
            for (Release release : page.releases()) {
                if (release.published() != null && release.published().isBefore(floor)) {
                    return;
                }
                visitor.visit(release);
            }
            after = page.next();
        } while (after != null);
    }
}
