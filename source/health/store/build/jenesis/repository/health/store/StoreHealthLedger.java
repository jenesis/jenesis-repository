package build.jenesis.repository.health.store;

import module java.base;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The maintainer-health ledger over one repository's scoped store. Each coordinate's health is the {@code health}
 * section of its consolidated per-coordinate metadata document ({@code meta/<eco>/<enc(coord)>/@coordinate},
 * {@link HealthSection}) - a point lookup for the gate/read seam, the repository-wide view a walk of the {@code meta/}
 * name tree (never an artifact body), and an eviction reclaims the coordinate with its document. A record is an
 * idempotent upsert stamped with the instant it was scored: a re-scan renews the facts and the freshness instant
 * through the section-scoped compare-and-set, and the section's merge drops a write carrying an older {@code scannedAt}
 * than the stored one, so a stale refresh (a slow sweep finishing after a fresh rescan) never rolls a coordinate's
 * health backwards.
 *
 * <p>The read seam is fail-soft, exactly as the live {@code ScorecardHealthSource} is: {@link #health} degrades a
 * missing record, a torn document or a store read failure to {@link Optional#empty() absent} rather than throwing, so a
 * coordinate with no stored health resolves to the same safe default (a ranking signal, no finding) the live probe
 * produces for a coordinate it cannot resolve - a broken or empty ledger must never fail a gate the way a broken
 * vulnerability feed does. The only removals are the artifact lifecycle's own (the inventory's {@code evict}
 * takes a coordinate's record when its last published version goes); nothing in this class removes a record.
 *
 * <p>The {@code health/scanned} freshness stamp ({@link build.jenesis.repository.health.HealthLedger#scanned health
 * stamp}) is a repo-level singleton and stays outside the document.
 */
public final class StoreHealthLedger implements HealthLedger {

    private static final System.Logger LOGGER = System.getLogger(StoreHealthLedger.class.getName());


    private final ArtifactStore store;

    /** The consolidated metadata store the health facts live in. */
    private final MetadataStore metadata;

    public StoreHealthLedger(ArtifactStore store) {
        this(store, MetadataProvider.installed().over(store));
    }

    /** Bind an explicit metadata store rather than the discovered one - for a caller (a test, or a wiring that
     *  already holds the store) that has one in hand. */
    public StoreHealthLedger(ArtifactStore store, MetadataStore metadata) {
        this.store = store;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public void record(String ecosystem, String coordinate, Health health, Instant scannedAt) throws IOException {
        // The monotonic-scannedAt guard rides into the section merge (HealthSection.record): a stale refresh is dropped
        // there, so the write converges to the freshest answer whoever drove it.
        metadata.mutateCoordinate(ecosystem, coordinate, HealthSection.TAG, HealthSection.record(health, scannedAt));
    }

    @Override
    public Optional<Health> health(String ecosystem, String coordinate) {
        try {
            // The section is the whole answer: an absent one means this coordinate has not been scored.
            return HealthSection.stored(metadata.coordinateSection(ecosystem, coordinate, HealthSection.TAG))
                    .map(HealthSection.Stored::health);
        } catch (IOException | RuntimeException e) {
            // A durable-health read is a ranking signal, not a hard gate: a store read failure degrades to no score
            // (the review ranks on severity and reachability, the gate raises no health finding - the same safe default
            // a never-scored coordinate produces) rather than failing closed the way a vulnerability feed does.
            LOGGER.log(System.Logger.Level.WARNING, "Could not read stored maintainer-health for " + coordinate + " ("
                    + ecosystem + "); ranking without a health signal", e);
            return Optional.empty();
        }
    }

    /**
     * When this ledger's records were last refreshed, and whether they may be acted on - the {@code health/scanned}
     * stamp the health sweep marks, read straight off the store. It is the &sect;10 half of the ledger's job: a
     * console showing a coordinate with no score can say whether the deployment has ever been swept, so an empty
     * panel is never ambiguous between "clean" and "never scanned".
     *
     * <p>Never {@link Freshness#FIXED}, even though this reads no vendor: the ledger is a mirror of what a health
     * source scored, so "nothing has been swept yet" must read as unconfirmed rather than as authoritative emptiness -
     * a floor consulting an unswept ledger would otherwise treat every coordinate as genuinely unrated.
     */
    @Override
    public Freshness freshness() {
        try {
            return HealthLedger.scanned(store).read().map(Freshness::at).orElse(Freshness.NEVER);
        } catch (IOException | RuntimeException e) {
            // A stamp that cannot be read is not evidence of a sweep, so the reading stays unconfirmed rather than
            // claiming a freshness nothing backs.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not read the maintainer-health scan stamp; reporting the ledger as never scanned", e);
            return Freshness.NEVER;
        }
    }

    @Override
    public List<Located> all() throws IOException {
        List<Located> collected = new ArrayList<>();
        all(collected::add);
        return collected;
    }

    /**
     * The enumeration the ledger streams through. The visitor's contract is <em>every</em> scored coordinate - the
     * rank-index rebuild and the console fold both need the whole ledger - so neither the entry cap nor the step
     * budget may end this scan; what it bounds is <b>heap</b>, one page of names at a time.
     *
     * <p>That is the fix. This override answered a {@code LedgerVisitor} - the streaming leg the whole-collection
     * ratchet points callers at, whose {@code InheritedBound}-ceilinged default is correct - by calling
     * {@code store.list(prefix)} on the per-ecosystem coordinate subtree, materialising every scored coordinate of an
     * ecosystem into one list before visiting any of them. The visitor never saw a bound and the caller never learnt
     * it was one allocation, so the override dropped exactly the protection the default carries.
     */
    private static final BoundedChildren LEDGER =
            BoundedChildren.draining();

    @Override
    public void all(LedgerVisitor visitor) throws IOException {
        // A pure streaming scan of the coordinate documents' health sections that buffers no coordinate set - the
        // property the rank-index rebuild rides, so a repository of millions of coordinates streams without ever
        // materialising the fleet.
        LEDGER.scan(store, MetadataKey.PREFIX, ecosystem ->
                LEDGER.scan(store, MetadataKey.PREFIX + "/" + ecosystem, encoded -> {
                    String coordinate = MetadataKey.decodeCoordinate(encoded);
                    Optional<HealthSection.Stored> stored = HealthSection.stored(
                            metadata.coordinateSection(ecosystem, coordinate, HealthSection.TAG));
                    if (stored.isPresent()) {
                        visitor.accept(new Located(ecosystem, coordinate, stored.get().health(),
                                stored.get().scannedAt()));
                    }
                }));
    }

    @Override
    public HealthLedger.Ranking worstFirst(String cursor, int limit) throws IOException {
        // The durable rank index serves a bounded, ordered page whenever a generation has been committed - eventually
        // consistent: it serves the last built generation regardless of whether a scan has moved the records since, so
        // the panel never pays an in-heap whole-ledger sort, and the page carries the index's own build-time freshness
        // so it is never shown fresher than it is.
        Optional<HealthLedger.Ranking.Ranked> ranked = new HealthRankIndex(store).read(cursor, limit);
        if (ranked.isPresent()) {
            return ranked.get();
        }
        // Before the first rank-index pass commits a generation there is no ranking, and this says so rather than
        // deriving one here: a whole-ledger sort on the request thread would answer "worst first" from
        // whatever the ledger buffered, which reads as authoritative while being nothing of the kind. What the panel
        // gets instead is the fact plus the ledger's own last sweep, so "swept but not yet ranked" and "nothing has
        // ever run here" are two different answers and neither reads as "nothing is unhealthy".
        return new HealthLedger.Ranking.NotBuilt(freshness().refreshed());
    }

    @Override
    public void reindex() throws IOException {
        // Rebuild the rank index from the current records, stamped with a composite of the live scan freshness and the
        // eviction epoch - a no-op when neither has moved since the last build. The scan stamp catches every scan/rescan
        // (which records or re-scores coordinates); the eviction epoch catches a coordinate reclaimed by its last-version
        // eviction, which does NOT move the scan stamp - so an evicted coordinate drops on this next pass rather than
        // lingering until the next scan. The scan stamp stays the composite's leading token, so a read still surfaces the
        // ranking's honest as-of instant (Principle 10) and the epoch never leaks into it. Called after an explicit
        // rescan and on the maintenance cadence.
        String scanStamp = HealthLedger.scanned(store).read().map(Instant::toString).orElse("");
        String stamp = scanStamp + ' ' + HealthLedger.evictions(store).current();
        new HealthRankIndex(store).rebuild(this, stamp);
    }
}
