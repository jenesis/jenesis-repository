package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The one canonical store-key codec for the consolidated per-coordinate metadata document - the single encoding
 * that ends the three-way drift the metadata audit surfaced (findings/health/licenses/published used
 * {@code segment(eco)/urlenc(coord)/segment(version)}; the AI caches URL-encoded only the coordinate;
 * {@code reachability/} and {@code ReachabilityHold} encoded all three). One document, one codec: keys built by
 * this convention join without a per-subsystem re-encode.
 *
 * <p>The canonical encoding is the findings/health convention - {@code meta/<segment(eco)>/<urlenc(coord)>/<segment(version)>}
 * - so the {@code ecosystem} and {@code version} are each a single traversal-free segment (validated through
 * {@link ArtifactStore#segment}, the guard that stops a {@code ..}-laced caller value aiming at a neighbouring
 * key-space), and the coordinate is URL-encoded into one segment so a coordinate carrying a reserved character
 * ({@code npm} scoped names, a {@code group:artifact} Maven coordinate) never fans out into extra path levels.
 *
 * <p>Two document shapes share the tree: the per-version document keyed by {@link #version} (findings, licenses,
 * publish facts, AI outcomes, reachability, provenance summary), and - reserved here, folded in by a later phase
 * - the per-coordinate document keyed by {@link #coordinate} at the {@link #COORDINATE} segment for the
 * version-independent facts (maintainer health). The {@code @} that opens {@link #COORDINATE} cannot be produced by
 * URL-encoding or by {@link ArtifactStore#segment}-validating a real version, so the two never collide; a version
 * name that itself starts with {@code @} is rejected at {@link #version} rather than allowed to alias the
 * coordinate document.
 */
public final class MetadataKey {

    /** The repository-scope key root the consolidated metadata documents own; declared in the storage manifest by
     *  the persistence module ({@code MetadataStorageNamespace}), so the orphan diagnostic and the operator purge
     *  know the key-space without a hardcoded table. */
    public static final String PREFIX = "meta";

    /** The reserved final segment of the per-coordinate document - {@code meta/<eco>/<enc(coord)>/@coordinate} -
     *  carrying the version-independent facts a later phase folds in. Its leading {@code @} is unreachable by any
     *  valid version segment, so the version and coordinate documents never alias. */
    public static final String COORDINATE = "@coordinate";

    private MetadataKey() {
    }

    /**
     * The stable store key of a coordinate <em>version</em>'s metadata document. The coordinate is URL-encoded into
     * one traversal-free segment and the {@code ecosystem}/{@code version} are each validated through
     * {@link ArtifactStore#segment}; a {@code version} beginning with {@code @} is rejected so it can never alias
     * the reserved per-coordinate document.
     */
    public static String version(String ecosystem, String coordinate, String version) {
        String segment = ArtifactStore.segment(version);
        if (segment.startsWith("@")) {
            throw new IllegalArgumentException("A version segment must not begin with '@' (reserved for the "
                    + "per-coordinate metadata document): " + version);
        }
        return PREFIX + "/" + ArtifactStore.segment(ecosystem) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + segment;
    }

    /**
     * The stable store key of a <em>coordinate</em>'s metadata document - the version-independent facts
     * (maintainer health) a later phase folds in - at the reserved {@link #COORDINATE} segment. Defined by this
     * codec now so the version-doc key can guard against aliasing it; nothing writes it in this foundation.
     */
    public static String coordinate(String ecosystem, String coordinate) {
        return PREFIX + "/" + ArtifactStore.segment(ecosystem) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + COORDINATE;
    }

    /** Decode a coordinate carried in a canonical key segment back to its raw form - the inverse of the URL-encode
     *  {@link #version}/{@link #coordinate} apply - for the reverse lookups the walk and console need. */
    public static String decodeCoordinate(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
