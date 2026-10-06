package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versions of a repository whose standing changed since a pass last looked: a finding recorded, superseded or
 * re-scored, a hold placed or ended. One row per version at {@code changed/<sha-256 of the version>}, naming its
 * ecosystem, coordinate and version as JSON, written blind where the change is made - so a version changing twice
 * before a pass drains it is one row - and drained by the pass that tells what relies on a version to look again.
 *
 * <p>A row is a hint, never a record: it is removed before it is acted on ({@link #drain}), so a pass that fails after
 * removing it leaves the change to the full pass that re-derives everything, and a row a pass never drains is removed
 * with its version - an eviction, the reconcile finding its pointers gone ({@link #forget}) - so the space is bounded by
 * the versions the repository holds.
 */
public final class ChangedVersions {

    /** The root of the rows. */
    public static final String ROOT = "changed";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChangedVersions() {
    }

    /** The row of {@code coordinate} at {@code version} of {@code ecosystem}. */
    static String key(String ecosystem, String coordinate, String version) {
        return ROOT + "/" + Checksums.sha256(ecosystem + "\n" + coordinate + "\n" + version);
    }

    /** Record that the standing of {@code coordinate} at {@code version} of {@code ecosystem} changed. */
    public static void mark(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        store.write(key(ecosystem, coordinate, version), new ByteArrayInputStream(JSON.writeValueAsBytes(
                JSON.createObjectNode().put("ecosystem", ecosystem).put("coordinate", coordinate)
                        .put("version", version))));
    }

    /** Remove the row of a version the repository no longer holds, if it has one. */
    static void forget(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        String key = key(ecosystem, coordinate, version);
        if (store.exists(key)) {
            store.delete(key);
        }
    }

    /** What a pass does with one changed version. */
    @FunctionalInterface
    public interface Visitor {

        /** Look again at what relies on the version. */
        void changed(StoreRepositoryInventory.Coordinate version) throws IOException;
    }

    /**
     * Remove up to {@code limit} rows, handing each version to {@code visitor} once its row is gone - so a change made
     * while the visitor runs writes a row of its own, which the next drain finds. A row that does not parse is removed
     * and passed over. Answers how many rows were removed, so a caller can tell a drain that met its limit.
     */
    public static int drain(ArtifactStore store, int limit, Visitor visitor) throws IOException {
        List<String> names = new ArrayList<>();
        store.page(ROOT, "", limit, names::add);
        for (String name : names) {
            String key = ROOT + "/" + name;
            Optional<ArtifactStore.Versioned> row = store.readVersioned(key);
            store.delete(key);
            Optional<StoreRepositoryInventory.Coordinate> version = row.flatMap(read -> parse(read.content()));
            if (version.isPresent()) {
                visitor.changed(version.get());
            }
        }
        return names.size();
    }

    private static Optional<StoreRepositoryInventory.Coordinate> parse(byte[] row) {
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
}
