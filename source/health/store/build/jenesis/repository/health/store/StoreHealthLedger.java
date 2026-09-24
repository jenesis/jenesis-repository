package build.jenesis.repository.health.store;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The maintainer-health ledger over one repository's scoped store. Each coordinate's health lives in one small JSON
 * document at {@link HealthLedger#key} - a point lookup for the gate/read seam, the repository-wide view a walk of the
 * {@code health/} name tree (never an artifact body), and an eviction reclaims the coordinate with a single key delete.
 * A record is a last-writer-wins idempotent upsert stamped with the instant it was scored: a re-scan renews the facts
 * and the freshness instant through the store's compare-and-set with a bounded retry, and a write carrying an older
 * {@code scannedAt} than the stored one is dropped, so a stale refresh (a slow sweep finishing after a fresh rescan)
 * never rolls a coordinate's health backwards.
 *
 * <p>The read seam is fail-soft, exactly as the live {@code ScorecardHealthSource} is: {@link #health} degrades a
 * missing record, a torn document or a store read failure to {@link Optional#empty() absent} rather than throwing, so a
 * coordinate with no stored health resolves to the same safe default (a ranking signal, no finding) the live probe
 * produces for a coordinate it cannot resolve - a broken or empty ledger must never fail a gate the way a broken
 * vulnerability feed does. The only removals are the artifact lifecycle's own (the inventory's {@code evict}
 * takes a coordinate's record when its last published version goes); nothing in this class removes a record.
 *
 * <p><strong>Cut over into the {@code @coordinate} document (§5.4).</strong> Each coordinate's health now lives
 * in the {@code health} section of the consolidated per-coordinate metadata document
 * ({@code meta/<eco>/<enc(coord)>/@coordinate}, {@link HealthSection}) rather than a standalone {@code health/} sidecar,
 * and the monotonic-{@code scannedAt} guard becomes that section's merge semantics - a clean cutover, not an indefinite
 * dual-write. A write goes through the section-scoped compare-and-set and a read reads the section, with no
 * fall-through to any other key: an absent section means the coordinate has not been scored. With no metadata
 * persistence module installed the ledger stays entirely on the sidecar, so it degrades gracefully exactly as the rest
 * of the ledger does. The {@code health/scanned}
 * freshness stamp ({@link build.jenesis.repository.health.HealthLedger#scanned health stamp}) is a repo-level singleton and stays put.
 */
public final class StoreHealthLedger implements HealthLedger {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final System.Logger LOGGER = System.getLogger(StoreHealthLedger.class.getName());


    private final ArtifactStore store;

    /** The consolidated metadata store the health facts live in (§5.4), or {@code null} when no
     *  {@link MetadataProvider} is installed - the graceful-absence path stays on the {@code health/} sidecar. */
    private final MetadataStore metadata;

    public StoreHealthLedger(ArtifactStore store) {
        this(store, MetadataProvider.installed().map(provider -> provider.over(store)).orElse(null));
    }

    /** Bind an explicit metadata store - or {@code null} for the no-metadata-module deployment whose ledger stays on
     *  the {@code health/} sidecar (§5.4). The primary constructor resolves the installed provider; this overload lets
     *  a caller (a test, or a wiring that already holds the store) pin the graceful-absence path directly. */
    public StoreHealthLedger(ArtifactStore store, MetadataStore metadata) {
        this.store = store;
        this.metadata = metadata;
    }

    @Override
    public void record(String ecosystem, String coordinate, Health health, Instant scannedAt) throws IOException {
        if (metadata != null) {
            // The monotonic-scannedAt guard rides into the section merge (HealthSection.record): a stale refresh is
            // dropped there, so the write converges to the freshest answer whoever drove it.
            metadata.mutateCoordinate(ecosystem, coordinate, HealthSection.TAG,
                    HealthSection.record(health, scannedAt));
            return;
        }
        recordSidecar(ecosystem, coordinate, health, scannedAt);
    }

    /** The sidecar write, taken on the no-metadata-module deployment - the CAS upsert with the monotonic guard, the
     *  same semantics the section merge carries. */
    private void recordSidecar(String ecosystem, String coordinate, Health health, Instant scannedAt)
            throws IOException {
        byte[] serialized = serialize(health, scannedAt);
        Retries.update(store, HealthLedger.key(ecosystem, coordinate), current -> {
            Optional<Stored> existing = current.flatMap(versioned -> read(versioned.content()));
            // The stored record is at least as fresh as this write: a stale refresh (a slow sweep finishing after a
            // fresh rescan) must not roll the health backwards. Last-writer-wins by the scored instant, not by who
            // reaches the store last.
            return existing.isPresent() && !existing.get().scannedAt().isBefore(scannedAt) ? null : serialized;
        });
    }

    @Override
    public Optional<Health> health(String ecosystem, String coordinate) {
        try {
            if (metadata != null) {
                // The section is the whole answer: an absent one means this coordinate has not been scored here, not
                // that its score lives under an older key.
                return HealthSection.stored(metadata.coordinateSection(ecosystem, coordinate, HealthSection.TAG))
                        .map(HealthSection.Stored::health);
            }
            return store.readVersioned(HealthLedger.key(ecosystem, coordinate))
                    .flatMap(versioned -> read(versioned.content()))
                    .map(Stored::health);
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
     * The enumeration both layouts stream through. The visitor's contract is <em>every</em> scored coordinate - the
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
        // Two layouts, never a union of the two. With no metadata module installed the ledger lives entirely in the
        // sidecar subtree; with one installed it lives entirely in the coordinate documents' health sections. Either
        // way the walk is a pure streaming scan that buffers no coordinate set - the property the rank-index rebuild
        // rides, so a repository of millions of coordinates streams without ever materialising the fleet.
        if (metadata == null) {
            LEDGER.scan(store, HealthLedger.PREFIX, ecosystem -> {
                if (ecosystem.equals("scanned") || ecosystem.equals("evicted")) {
                    return;                                     // the freshness stamp / eviction epoch sit beside the subtrees
                }
                LEDGER.scan(store, HealthLedger.PREFIX + "/" + ecosystem, encoded ->
                        emitSidecar(ecosystem, URLDecoder.decode(encoded, StandardCharsets.UTF_8), visitor));
            });
            return;
        }
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

    /** Read a coordinate's sidecar record and deliver it, skipping a sidecar that has since been removed (a torn or
     *  concurrently-swept key reads empty rather than throwing). The no-metadata streaming scan's per-coordinate
     *  step. */
    private void emitSidecar(String ecosystem, String coordinate, LedgerVisitor visitor) throws IOException {
        Optional<Stored> stored = store.readVersioned(HealthLedger.key(ecosystem, coordinate))
                .flatMap(versioned -> read(versioned.content()));
        if (stored.isPresent()) {
            visitor.accept(new Located(ecosystem, coordinate, stored.get().health(), stored.get().scannedAt()));
        }
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

    /** The stored document written and read through the tree API, so the store shape stays a flat, forward-readable
     *  document - doubles and the instant as strings - and the module needs no reflective opening to the JSON library. */
    private static byte[] serialize(Health health, Instant scannedAt) {
        ObjectNode document = JSON.createObjectNode();
        if (health.sourceRepository() != null) {
            document.put("sourceRepository", health.sourceRepository());
        }
        document.put("overall", health.overall());
        document.put("maintenance", health.maintenance());
        document.put("review", health.review());
        document.put("provenance", health.provenance());
        document.put("scannedAt", scannedAt.toString());
        return JSON.writeValueAsBytes(document);
    }

    /** Read a coordinate's health document. Total: a torn/foreign document (non-JSON bytes, or an object another module
     *  placed under health/) reads as empty rather than throwing, so one stray object never blanks the walk - the same
     *  "one stray file never blanks the trail" the findings/storage sweeps hold to. */
    private static Optional<Stored> read(byte[] content) {
        try {
            JsonNode document = JSON.readTree(content);
            JsonNode overall = document.path("overall");
            if (!overall.isNumber()) {
                return Optional.empty();                        // not a health document (or a bare stamp)
            }
            Health health = new Health(document.path("sourceRepository").asString(null), overall.asDouble(),
                    document.path("maintenance").asDouble(Health.NOT_EVALUATED),
                    document.path("review").asDouble(Health.NOT_EVALUATED),
                    document.path("provenance").asDouble(Health.NOT_EVALUATED));
            Instant scannedAt = Instant.parse(document.path("scannedAt").asString(Instant.EPOCH.toString()));
            return Optional.of(new Stored(health, scannedAt));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Skipping an unreadable health document", e);
            return Optional.empty();
        }
    }

    /** A parsed health document: the scored health and the instant it was scored. */
    private record Stored(HealthSource.Health health, Instant scannedAt) {
    }
}
