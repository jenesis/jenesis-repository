package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Checksums;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One version of a package as a row names it: the digest a row's key ends in, so a row has one key per version
 * whatever characters its coordinate holds, and the row's body, a JSON object of its {@code ecosystem},
 * {@code coordinate} and {@code version}. A mailbox's requests, the index of the copies asked under another name and
 * the bills a version carries are keyed this way.
 */
public final class VersionRows {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private VersionRows() {
    }

    /** The digest naming {@code version} of {@code coordinate} in {@code ecosystem}. */
    public static String digest(String ecosystem, String coordinate, String version) {
        return Checksums.sha256(ecosystem + "\n" + coordinate + "\n" + version);
    }

    /** The row naming {@code version} of {@code coordinate} in {@code ecosystem}. */
    static byte[] encode(String ecosystem, String coordinate, String version) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("ecosystem", ecosystem)
                .put("coordinate", coordinate).put("version", version));
    }

    /** The version {@code row} names, or empty for a row that does not parse or leaves a part out. */
    static Optional<StoreRepositoryInventory.Coordinate> decode(byte[] row) {
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
