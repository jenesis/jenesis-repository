package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The shared path-traversal probe vectors, held as plain data.
 *
 * <p>This list exists because the alternative - each format's fixture writing its own probes - is precisely how a
 * cross-format guard rots: the format whose fixture forgot the {@code %2e%2e} row is the one that decodes, and nobody
 * notices because every fixture is "green over its own vectors". A vector is a <em>relative</em> fragment, so one row
 * is spliced into every format's namespace by that format's one-line {@link FormatFixture#probe} seam and the shapes
 * themselves are written exactly once.
 *
 * <p>Each row also declares what a refusal <em>means</em> for its shape, because the three shapes are refused by three
 * different, deliberately chosen seams:
 * <ul>
 *   <li>{@link Kind#DECODED} - a {@code .} or {@code ..} segment, or a {@code \}, as the format really receives it.
 *       Refused by the format itself with a {@code 404} ({@code RepositoryFormat} contract clause 6): it addresses
 *       nothing, and it must never reach {@link ArtifactStore#key}, which throws.</li>
 *   <li>{@link Kind#ENCODED} - the percent-encoded form of the same traversal. A format never decodes its own path, so
 *       this stays a literal name: it may legitimately be stored under that literal key, and the assertion is only
 *       that it never lands at the <em>decoded</em> target. A format that re-decoded would turn a dispatcher's
 *       already-decoded path into a second traversal, which is the bug this row exists to catch.</li>
 *   <li>{@link Kind#SHAPE_CAP} - past {@link ArtifactStore#MAX_SEGMENTS} or {@link ArtifactStore#MAX_KEY_BYTES}. There
 *       is no traversal here, so the format has nothing to screen and the store's own key cap is the refusal; it
 *       surfaces as an {@link IllegalArgumentException} and nothing is stored. Stated as its own kind rather than
 *       folded into the others, so "the store threw" can never be accepted as a refusal for a shape the format was
 *       supposed to answer {@code 404} to.</li>
 * </ul>
 *
 * <p>{@link #ESCAPE} is the distinctive leaf name every escaping vector aims at, so a check can prove the traversal
 * did not land by looking for that one name anywhere in the store rather than by re-deriving each format's idea of
 * "one level up".
 */
public final class TraversalVectors {

    /** The leaf name every escaping vector aims at - distinctive, so finding it anywhere in the store means a probe
     *  really escaped rather than that some unrelated fixture object happens to share a name. */
    public static final String ESCAPE = "t202a-escaped-here.bin";

    /** How a vector's refusal is judged. */
    public enum Kind {
        /** A {@code .}/{@code ..} segment the format receives decoded: the format answers a non-2xx itself. */
        DECODED,
        /** A percent-encoded traversal: a literal name, so any status is legal - it must simply not decode. */
        ENCODED,
        /** Past the store's key caps: refused by the store's key screen, so a thrown refusal is the expected shape. */
        SHAPE_CAP
    }

    /**
     * One probe vector: a stable {@code id} for failure messages, the {@code relative} fragment a fixture splices into
     * its namespace, and the {@link Kind} that says how its refusal is judged.
     */
    public record Vector(String id, String relative, Kind kind) {

        public Vector {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(relative, "relative");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private static final List<Vector> ALL = List.of(
            new Vector("parent-segment", ".." + "/" + ESCAPE, Kind.DECODED),
            new Vector("nested-parent", "kit/../../" + ESCAPE, Kind.DECODED),
            new Vector("current-segment", "kit/./" + ESCAPE, Kind.DECODED),
            new Vector("trailing-parent", "kit/..", Kind.DECODED),
            new Vector("bare-parent", "..", Kind.DECODED),
            // A doubly-nested escape: two levels above the format prefix, so a screen that only refused a LEADING ".."
            // (or only compared the first segment) still lets this one through.
            new Vector("deep-parent", "kit/nested/../../../" + ESCAPE, Kind.DECODED),
            // The same traversal one alphabet over: '\' is a real path separator on a Windows-hosted filesystem
            // backend and a literal character on the three object stores, so a screen that split on '/' alone read
            // this as one long, harmless leaf name. It is a DECODED row, not an ENCODED one - nothing has to
            // decode anything for it to escape; the platform below simply reads a separator the screen did not.
            new Vector("backslash-parent", "kit\\..\\" + ESCAPE, Kind.DECODED),
            // ... and the same fact without a traversal in it: one key, two placements (a nested object on Windows, a
            // flat one with a backslash in its name everywhere else), which is the divergence the store's key screen
            // exists to keep out rather than a shape any of the fourteen ecosystems publishes.
            new Vector("backslash-separator", "kit\\" + ESCAPE, Kind.DECODED),
            new Vector("encoded-parent", "%2e%2e/" + ESCAPE, Kind.ENCODED),
            new Vector("encoded-separator", "..%2f" + ESCAPE, Kind.ENCODED),
            new Vector("over-deep", String.join("/", Collections.nCopies(ArtifactStore.MAX_SEGMENTS + 4, "kit")),
                    Kind.SHAPE_CAP),
            new Vector("over-long", "kit/" + "a".repeat(ArtifactStore.MAX_KEY_BYTES), Kind.SHAPE_CAP));

    private TraversalVectors() {
    }

    /** Every probe vector, in declaration order. */
    public static List<Vector> all() {
        return ALL;
    }

    /** The vectors of one kind. */
    public static List<Vector> of(Kind kind) {
        return ALL.stream().filter(vector -> vector.kind() == kind).toList();
    }
}
