package build.jenesis.repository.health.store;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.bounds.GenerationIndex;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A durable weakest-first rank index over one repository's stored maintainer-health, so the console panel serves a
 * bounded, ordered page rather than buffering every scored coordinate and sorting the whole set in heap on each render.
 *
 * <p><strong>Shape.</strong> Each scored coordinate is one flat child of a generation directory,
 * {@code healthrank/g<gen>/<pad>-<sha>}, where {@code pad} is the coordinate's overall score scaled to
 * {@code [00000..10000]} (worst first, so lexicographic order over the flat child set <em>is</em> ascending-overall
 * order) and {@code sha} is a content hash of {@code ecosystem\0coordinate} - a stable, unique tie-break within an
 * equal-score band. The child's body is the whole record, so a page read reconstructs each {@link HealthLedger.Located}
 * from the body without decoding the key. A page is {@link ArtifactStore#page a single ordered, seekable, bounded read}
 * of that flat child set - never a whole-tree list, never an in-heap sort.
 *
 * <p><strong>Generations.</strong> The whole generation lifecycle - the fresh generation, the atomic flip, the reclaim
 * of the superseded one and of a crashed orphan - is {@link GenerationIndex}, shared with the findings-filter and
 * vulnerability-rank indexes; this class is the health bucket: the row body, the weakest-first key and the read.
 *
 * <p><strong>Freshness.</strong> The marker records the {@link build.jenesis.repository.health.HealthLedger#scanned health stamp} the records
 * carried when it was built. The stamp drives the <em>rebuild</em> decision only - a rebuild whose stamp already matches
 * the marker is a no-op, so a pass rebuilds exactly when the records moved (and never skips one while they have, so it
 * re-derives from truth and cannot miss a change). The <em>read</em> is eventually consistent - it serves the last built
 * generation regardless of the stamp - so a request never pays an in-heap sort once an index stands. The page carries
 * the index's own build-time freshness (its build stamp), so a surface renders the ranking's honest as-of instant and
 * never shows it fresher than it is. Before the first generation is committed there is nothing to serve and the read
 * says exactly that ({@link HealthLedger.Ranking.NotBuilt}) rather than recomputing a ranking on the request thread.
 */
final class HealthRankIndex {

    /** The repository-scope key root the rank index owns - declared in the storage manifest by the persistence module. */
    static final String PREFIX = "healthrank";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final System.Logger LOGGER = System.getLogger(HealthRankIndex.class.getName());

    private final ArtifactStore store;
    private final GenerationIndex index;

    HealthRankIndex(ArtifactStore store) {
        this.store = store;
        this.index = new GenerationIndex(store, PREFIX);
    }

    /**
     * Rebuild the index from the ledger's current records if they have moved since the last build, else do nothing. The
     * records are streamed (never buffered whole) into a fresh generation the {@link GenerationIndex} then flips to.
     *
     * @param ledger       the records to index, streamed through {@link HealthLedger#all(HealthLedger.LedgerVisitor)}
     * @param currentStamp the composite build stamp - the live scan freshness ({@code ""} when never scanned) followed
     *                     by the {@link HealthLedger#evictions eviction epoch}, so an eviction that did not move the
     *                     scan stamp still moves this composite and so triggers a rebuild; its leading token is the scan
     *                     freshness a read surfaces, the trailing epoch never leaking into what a surface renders
     */
    void rebuild(HealthLedger ledger, String currentStamp) throws IOException {
        index.rebuild(currentStamp, generationPrefix -> {
            long[] count = {0};
            ledger.all(located -> {
                store.write(entryKey(generationPrefix, located), new ByteArrayInputStream(serialize(located)));
                count[0]++;
            });
            return count[0];
        }, index::reclaimFlat);
    }

    /**
     * One weakest-first page of the built index, or {@link Optional#empty()} when <em>no generation has ever been
     * committed</em> - the caller then answers {@link HealthLedger.Ranking.NotBuilt}, because there is no ranking to
     * page and no honest way to invent one on the request thread. Built-ness is the marker's presence and nothing else:
     * an empty ranking and an unbuilt one are different facts, and the records cannot separate them.
     *
     * <p>The read is <strong>eventually consistent</strong>: it serves the last built generation whenever one stands
     * and does <em>not</em> fall back on a moved freshness stamp, so a request never pays an in-heap sort once an index
     * stands. The page carries the index's own build-time freshness
     * ({@link HealthLedger.Ranking#scannedAt()}), the honest as-of instant a surface renders rather than the live scan
     * stamp the records may have moved past. It is a single ordered, seekable, bounded read of the live generation's
     * flat child set followed by a bounded body read per row; no whole-tree list and no in-heap sort.
     */
    Optional<HealthLedger.Ranking.Ranked> read(String cursor, int limit) throws IOException {
        Optional<GenerationIndex.Marker> marker = index.marker();
        if (marker.isEmpty()) {
            return Optional.empty();                            // never built: the caller answers the not-built state
        }
        int count = (int) marker.get().count();
        if (limit <= 0) {
            return Optional.of(new HealthLedger.Ranking.Ranked(List.of(), null, count,
                    builtScanStamp(marker.get().stamp())));
        }
        String generationPrefix = marker.get().generationPrefix(PREFIX);
        List<String> names = new ArrayList<>();
        store.page(generationPrefix, cursor == null ? "" : cursor, limit, names::add);
        List<HealthLedger.Located> entries = new ArrayList<>(names.size());
        for (String name : names) {
            located(generationPrefix + "/" + name).ifPresent(entries::add);
        }
        // A short page is the last page (the ordered child set is exhausted); a full page resumes strictly after its
        // last child's name, the same seek-resume cursor the shared artifact walk uses.
        String nextCursor = names.size() < limit ? null : names.getLast();
        return Optional.of(new HealthLedger.Ranking.Ranked(entries, nextCursor, count,
                builtScanStamp(marker.get().stamp())));
    }

    /** The ledger scan freshness the index was built at - the leading token of the composite build stamp, the honest
     *  as-of instant a surface renders for the eventually-consistent ranking rather than the live scan stamp the
     *  records may have moved past. Empty for a generation built over a never-scanned repository, and empty for a torn
     *  or foreign token that does not parse (built, as-of unknown - the honest degrade, never a fabricated instant);
     *  either way the generation still stands, so this can never turn a built ranking into an unbuilt one. The trailing
     *  token is the eviction epoch (folded in so an eviction rebuilds the ranking) and is deliberately never surfaced. */
    private static Optional<Instant> builtScanStamp(String stamp) {
        String scan = stamp.isBlank() ? "" : stamp.split(" ", 2)[0];
        if (scan.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(scan));
        } catch (java.time.format.DateTimeParseException _) {
            return Optional.empty();
        }
    }

    private Optional<HealthLedger.Located> located(String key) {
        if (!store.exists(key)) {
            return Optional.empty();
        }
        // Parsed straight off the stream (never a whole-blob read into a heap buffer): an index row is a small bounded
        // record, and the ordered page reads them one at a time.
        try (InputStream in = store.open(key)) {
            JsonNode document = JSON.readTree(in);
            JsonNode overall = document.path("overall");
            if (!overall.isNumber()) {
                return Optional.empty();                        // a torn index row is skipped, never blanks the page
            }
            Health health = new Health(document.path("sourceRepository").asString(null), overall.asDouble(),
                    document.path("maintenance").asDouble(Health.NOT_EVALUATED),
                    document.path("review").asDouble(Health.NOT_EVALUATED),
                    document.path("provenance").asDouble(Health.NOT_EVALUATED));
            Instant scannedAt = Instant.parse(document.path("scannedAt").asString(Instant.EPOCH.toString()));
            return Optional.of(new HealthLedger.Located(document.path("ecosystem").asString(""),
                    document.path("coordinate").asString(""), health, scannedAt));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Skipping an unreadable health rank-index row at " + key, e);
            return Optional.empty();
        }
    }

    /** The flat child key of a record in a generation: the score band (worst first) and a stable content tie-break, so
     *  the ordered child set reads back ascending-overall with a deterministic order within an equal-score band. */
    private static String entryKey(String generationPrefix, HealthLedger.Located located) {
        double overall = located.health().overall();
        int scaled = (int) Math.round(Math.max(0.0, Math.min(10.0, overall)) * 1000.0);
        String band = String.format(Locale.ROOT, "%05d", scaled);
        return generationPrefix + "/" + band + "-" + tieBreak(located.ecosystem(), located.coordinate());
    }

    /** A fixed-width, URL-safe, deterministic per-coordinate tie-break within an equal-score band - the SHA-256 of the
     *  ecosystem and coordinate, so two coordinates never collide and the same coordinate always lands in the same slot. */
    private static String tieBreak(String ecosystem, String coordinate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ecosystem.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(coordinate.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required digest", e);
        }
    }

    private static byte[] serialize(HealthLedger.Located located) {
        Health health = located.health();
        ObjectNode document = JSON.createObjectNode();
        document.put("ecosystem", located.ecosystem());
        document.put("coordinate", located.coordinate());
        if (health.sourceRepository() != null) {
            document.put("sourceRepository", health.sourceRepository());
        }
        document.put("overall", health.overall());
        document.put("maintenance", health.maintenance());
        document.put("review", health.review());
        document.put("provenance", health.provenance());
        document.put("scannedAt", located.scannedAt().toString());
        return JSON.writeValueAsBytes(document);
    }
}
