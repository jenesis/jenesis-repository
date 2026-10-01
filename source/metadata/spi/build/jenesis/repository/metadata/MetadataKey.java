package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The store-key codec of the consolidated metadata document:
 * {@code meta/<segment(eco)>/<urlenc(coord)>/<segment(version)>}. The ecosystem and version are each one traversal-free
 * segment (validated by {@link ArtifactStore#segment}, which stops a {@code ..}-laced value aiming at a neighbouring
 * key space), and the coordinate is URL-encoded into one segment so a reserved character (an npm scope, a Maven
 * {@code group:artifact}) never adds path levels. Every subsystem keys its section through this codec, so documents
 * join without re-encoding.
 *
 * <p>Two document shapes share the tree: the per-version document ({@link #version}) and the per-coordinate document
 * for version-independent facts such as maintainer health ({@link #coordinate}, at the {@link #COORDINATE} segment).
 * The leading {@code @} of {@link #COORDINATE} cannot come from URL-encoding or a valid version segment, and a version
 * starting with {@code @} is rejected, so the two never collide.
 */
public final class MetadataKey {

    /** The repository-scope key root the metadata documents own, declared in the storage manifest by
     *  {@code MetadataStorageNamespace}. */
    public static final String PREFIX = "meta";

    /** The reserved final segment of the per-coordinate document, {@code meta/<eco>/<enc(coord)>/@coordinate}; no valid
     *  version segment starts with {@code @}. */
    public static final String COORDINATE = "@coordinate";

    private MetadataKey() {
    }

    /** The store key of a coordinate version's document. A {@code version} beginning with {@code @} is rejected so it
     *  can never alias the per-coordinate document. */
    public static String version(String ecosystem, String coordinate, String version) {
        String segment = ArtifactStore.segment(version);
        if (segment.startsWith("@")) {
            throw new IllegalArgumentException("A version segment must not begin with '@' (reserved for the "
                    + "per-coordinate metadata document): " + version);
        }
        return PREFIX + "/" + ArtifactStore.segment(ecosystem) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + segment;
    }

    /** The store key of a coordinate's document - the version-independent facts such as maintainer health - at the
     *  reserved {@link #COORDINATE} segment. */
    public static String coordinate(String ecosystem, String coordinate) {
        return PREFIX + "/" + ArtifactStore.segment(ecosystem) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + COORDINATE;
    }

    /** Decode a coordinate from its key segment - the inverse of the URL-encoding {@link #version} and
     *  {@link #coordinate} apply. */
    public static String decodeCoordinate(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
