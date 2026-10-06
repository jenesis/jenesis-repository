package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code advised} section codec of the consolidated metadata document: the name an advisory database knows a
 * cached copy's version by, where it is not the copy's own coordinate - a Debian binary package under its source
 * package, at the source's version. Recorded when the copy is screened at its fill, from what its inspector read, so
 * every later screen of the copy - the scan pass, a rescan - asks the feeds the same question the fill did. Absent for
 * a version the databases know by its own coordinate. The {@code data} payload is
 * {@code {"ecosystem","coordinate","version"}}.
 */
public final class AdvisedSection {

    /** The section tag. */
    public static final String TAG = "advised";

    /**
     * The root of the reverse index from a name to the copies asked under it: one row per copy, at
     * {@code advised/<ecosystem>/<coordinate>/<sha-256 of the copy>}, the ecosystem without a release qualifier - a
     * feed's change log names {@code Alpine}, a copy is asked under {@code Alpine:v3.19} - and the row the copy's
     * ecosystem, coordinate and version as JSON. Written where the name is not the copy's own, and removed where the
     * copy stops being held - an eviction, a reclaim, the reconcile finding its pointers gone - as the copy's
     * {@code cached} row is ({@link #forget}); a row whose copy no longer records the name, or is gone, is passed
     * over by the reader.
     */
    public static final String INDEX = "advised";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AdvisedSection() {
    }

    /** The index level of the copies asked under {@code coordinate} of {@code ecosystem}. */
    static String indexRoot(String ecosystem, String coordinate) {
        int release = ecosystem.indexOf(':');
        String base = release < 0 ? ecosystem : ecosystem.substring(0, release);
        return INDEX + "/" + URLEncoder.encode(base, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8);
    }

    /** The index row of the copy {@code coordinate} at {@code version} of {@code ecosystem}, asked under
     *  {@code advised}. */
    static String indexKey(AdvisorySource.Query advised, String ecosystem, String coordinate, String version) {
        return indexRoot(advised.ecosystem(), advised.coordinate()) + "/" + HexFormat.of().formatHex(sha256(
                (ecosystem + "\n" + coordinate + "\n" + version).getBytes(StandardCharsets.UTF_8)));
    }

    /** Remove the index row of the copy {@code coordinate} at {@code version} of {@code ecosystem} recorded as asked
     *  under {@code advised}, if any: the copy stops being held, and nothing else would ever name the row again. */
    static void forget(ArtifactStore store, Optional<AdvisorySource.Query> advised, String ecosystem,
                       String coordinate, String version) throws IOException {
        if (advised.isEmpty() || advised.get().equals(new AdvisorySource.Query(ecosystem, coordinate, version))) {
            return;
        }
        String key = indexKey(advised.get(), ecosystem, coordinate, version);
        if (store.exists(key)) {
            store.delete(key);
        }
    }

    /** The row naming a copy. */
    static byte[] indexRow(String ecosystem, String coordinate, String version) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("ecosystem", ecosystem)
                .put("coordinate", coordinate).put("version", version));
    }

    /** The copy a row names, or empty for a row that does not parse. */
    static Optional<StoreRepositoryInventory.Coordinate> copy(byte[] row) {
        try {
            JsonNode node = JSON.readTree(row);
            String ecosystem = node.path("ecosystem").asString("");
            String coordinate = node.path("coordinate").asString("");
            String version = node.path("version").asString("");
            return ecosystem.isEmpty() || coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new StoreRepositoryInventory.Coordinate(ecosystem, coordinate, version));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    /** What a section records, or empty for a version with none. */
    public static Optional<AdvisorySource.Query> advised(Optional<Section> section) {
        return section.flatMap(Section::payload).flatMap(data -> {
            String ecosystem = data.path("ecosystem").asString("");
            String coordinate = data.path("coordinate").asString("");
            String version = data.path("version").asString("");
            return ecosystem.isEmpty() || coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new AdvisorySource.Query(ecosystem, coordinate, version));
        });
    }

    /** Record {@code advised} as the version's, as of {@code at}. */
    public static SectionMutation record(AdvisorySource.Query advised, Instant at) {
        return _ -> {
            ObjectNode data = JSON.createObjectNode().put("ecosystem", advised.ecosystem())
                    .put("coordinate", advised.coordinate()).put("version", advised.version());
            return Section.derived(TAG, SCHEMA, at, Signal.NEUTRAL, data);
        };
    }
}
