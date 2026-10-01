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
 * section of its per-coordinate metadata document ({@code meta/<eco>/<enc(coord)>/@coordinate}, {@link HealthSection}):
 * a point lookup for the gate, a walk of {@code meta/} for the repository-wide view, reclaimed with its document. A
 * record is an upsert stamped with its scored instant, merged so a stale refresh never rolls health backwards.
 *
 * <p>The read is fail-soft, as the live {@code ScorecardHealthSource} is: {@link #health} degrades a missing record, a
 * torn document or a read failure to absent, the same safe default a never-scored coordinate gets - a broken ledger
 * must never fail a gate. Only eviction of a coordinate's last version removes a record.
 */
public final class StoreHealthLedger implements HealthLedger {

    private static final System.Logger LOGGER = System.getLogger(StoreHealthLedger.class.getName());


    private final ArtifactStore store;

    /** The consolidated metadata store the health facts live in. */
    private final MetadataStore metadata;

    public StoreHealthLedger(ArtifactStore store) {
        this(store, MetadataProvider.installed().over(store));
    }

    /** Bind an explicit metadata store rather than the discovered one. */
    public StoreHealthLedger(ArtifactStore store, MetadataStore metadata) {
        this.store = store;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public void record(String ecosystem, String coordinate, Health health, Instant scannedAt) throws IOException {
        // The monotonic scannedAt guard is the section merge (HealthSection.record), so the write converges to the
        // freshest.
        metadata.mutateCoordinate(ecosystem, coordinate, HealthSection.TAG, HealthSection.record(health, scannedAt));
    }

    @Override
    public Optional<Health> health(String ecosystem, String coordinate) {
        try {
            // An absent section means this coordinate has not been scored.
            return HealthSection.stored(metadata.coordinateSection(ecosystem, coordinate, HealthSection.TAG))
                    .map(HealthSection.Stored::health);
        } catch (IOException | RuntimeException e) {
            // Health is a ranking signal, not a hard gate: a read failure degrades to no score, the never-scored
            // default, rather than failing closed as a vulnerability feed does.
            LOGGER.log(System.Logger.Level.WARNING, "Could not read stored maintainer-health for " + coordinate + " ("
                    + ecosystem + "); ranking without a health signal", e);
            return Optional.empty();
        }
    }

    /**
     * When this ledger was last refreshed and whether it may be acted on - the {@code health/scanned} stamp the sweep
     * marks - so an empty panel is never ambiguous between "clean" and "never scanned".
     *
     * <p>Never {@link Freshness#FIXED}: the ledger mirrors what a source scored, so "nothing swept yet" reads as
     * unconfirmed rather than authoritative emptiness.
     */
    @Override
    public Freshness freshness() {
        try {
            return HealthLedger.scanned(store).read().map(Freshness::at).orElse(Freshness.NEVER);
        } catch (IOException | RuntimeException e) {
            // An unreadable stamp is no evidence of a sweep: unconfirmed.
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

    /** The enumeration the ledger streams through. The visitor wants every scored coordinate - the rank-index rebuild
     *  and the console fold need the whole ledger - so neither cap may end the scan; it bounds heap, one page of names
     *  at a time. An override of the streaming leg must not {@code store.list} a whole ecosystem's coordinates, which
     *  would drop that protection. */
    private static final BoundedChildren LEDGER =
            BoundedChildren.draining();

    @Override
    public void all(LedgerVisitor visitor) throws IOException {
        // A streaming scan of the coordinate documents' health sections, buffering no coordinate set.
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
        // The rank index serves a bounded page whenever a generation stands, carrying its own build-time freshness.
        Optional<HealthLedger.Ranking.Ranked> ranked = new HealthRankIndex(store).read(cursor, limit);
        if (ranked.isPresent()) {
            return ranked.get();
        }
        // Before the first generation there is no ranking, and this says so with the ledger's last sweep rather than
        // sorting the whole ledger on the request thread - so "swept, not yet ranked" and "never run" stay distinct and
        // neither reads as "nothing is unhealthy".
        return new HealthLedger.Ranking.NotBuilt(freshness().refreshed());
    }

    @Override
    public void reindex() throws IOException {
        // The composite stamp: the scan freshness catches every scan and rescan, the eviction epoch a coordinate
        // evicted with its last version, which does not move the scan stamp. A no-op when neither moved.
        String scanStamp = HealthLedger.scanned(store).read().map(Instant::toString).orElse("");
        String stamp = scanStamp + ' ' + HealthLedger.evictions(store).current();
        new HealthRankIndex(store).rebuild(this, stamp);
    }
}
