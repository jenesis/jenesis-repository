package build.jenesis.repository.health.store;

import module java.base;
import module tools.jackson.databind;
import java.time.format.DateTimeParseException;
import build.jenesis.repository.bounds.GenerationIndex;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A durable weakest-first rank index over one repository's stored maintainer health, so the console panel serves a
 * bounded, ordered page instead of sorting every scored coordinate in heap per render.
 *
 * <p><strong>Shape.</strong> Each scored coordinate is one flat child {@code healthrank/g<gen>/<pad>-<sha>}, where
 * {@code pad} is the overall score scaled to {@code [00000..10000]} (so lexicographic order is ascending-overall) and
 * {@code sha} is a hash of {@code ecosystem\0coordinate}, a stable tie-break within a score band. The body is the whole
 * record, so a page reconstructs each {@link HealthLedger.Located} without decoding keys, in one
 * {@link ArtifactStore#page ordered, seekable, bounded read}.
 *
 * <p><strong>Generations</strong> are {@link GenerationIndex}'s, shared with the findings-filter and vulnerability-rank
 * indexes; this class is the row body, the key and the read.
 *
 * <p><strong>Freshness.</strong> The marker records the
 * {@link build.jenesis.repository.health.HealthLedger#scanned health stamp} the records carried when built; a rebuild
 * whose stamp matches is a no-op, so a pass rebuilds exactly when the records moved. The read serves the last built
 * generation whatever the stamp, carrying that generation's own build-time freshness so it is never shown fresher than
 * it is. Before the first generation there is nothing to serve, and the read says so
 * ({@link HealthLedger.Ranking.NotBuilt}) rather than ranking on the request thread.
 */
final class HealthRankIndex {

    /** The repository-scope key root the rank index owns. */
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
     * Rebuild the index if the records have moved since the last build, else do nothing. The records stream into a
     * fresh generation that {@link GenerationIndex} then flips to.
     *
     * @param ledger the records to index, streamed through {@link HealthLedger#all(HealthLedger.LedgerVisitor)}
     * @param currentStamp the composite build stamp - the scan freshness ({@code ""} when never scanned) followed by
     *     the {@link HealthLedger#evictions eviction epoch}, so an eviction alone triggers a rebuild; only the leading
     *     token is ever surfaced
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
     * One weakest-first page of the built index, or {@link Optional#empty()} when no generation has been committed -
     * the caller then answers {@link HealthLedger.Ranking.NotBuilt}. Built-ness is the marker's presence: an empty
     * ranking and an unbuilt one are different facts.
     *
     * <p>Eventually consistent: it serves the last built generation and never falls back on a moved stamp. The page
     * carries the index's build-time freshness ({@link HealthLedger.Ranking#scannedAt()}). One ordered, bounded read of
     * the flat child set, then one body read per row.
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
        // A short page is the last; a full page resumes strictly after its last child's name.
        String nextCursor = names.size() < limit ? null : names.getLast();
        return Optional.of(new HealthLedger.Ranking.Ranked(entries, nextCursor, count,
                builtScanStamp(marker.get().stamp())));
    }

    /** The scan freshness the index was built at - the composite stamp's leading token - which a surface renders as the
     *  ranking's as-of instant. Empty for a never-scanned repository or a token that does not parse; the generation
     *  still stands either way. The trailing eviction epoch is never surfaced. */
    private static Optional<Instant> builtScanStamp(String stamp) {
        String scan = stamp.isBlank() ? "" : stamp.split(" ", 2)[0];
        if (scan.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(scan));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    private Optional<HealthLedger.Located> located(String key) {
        if (!store.exists(key)) {
            return Optional.empty();
        }
        // Parsed off the stream: an index row is a small record, read one at a time.
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

    /** The flat child key of a record: the score band (worst first) and a stable tie-break, so the set reads back
     *  ascending-overall in a deterministic order. */
    private static String entryKey(String generationPrefix, HealthLedger.Located located) {
        double overall = located.health().overall();
        int scaled = (int) Math.round(Math.max(0.0, Math.min(10.0, overall)) * 1000.0);
        String band = String.format(Locale.ROOT, "%05d", scaled);
        return generationPrefix + "/" + band + "-" + tieBreak(located.ecosystem(), located.coordinate());
    }

    /** A fixed-width, URL-safe tie-break within a score band: the SHA-256 of the ecosystem and coordinate. */
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
