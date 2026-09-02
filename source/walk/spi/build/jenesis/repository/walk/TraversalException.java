package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * The named failure a bounded store traversal raises rather than returning a plausible but incomplete answer - the
 * "bounds fail visibly" half of {@link Traversal}'s outcome model. {@link Traversal.Outcome#TRUNCATED} plus a
 * continuation cursor is the <em>resumable</em> bound (the caller can ask for the next page and eventually see
 * everything); this exception is the bound that has <em>no</em> safe continuation, so degrading it to a short list
 * would silently hide keys:
 *
 * <ul>
 *   <li>{@link Reason#DEPTH} - a subtree deeper than the traversal's depth cap. Skipping past it would drop every key
 *       beneath it, and no cursor in path order can express "resume below a subtree I refused to enter", so the
 *       traversal fails instead. Attacker-shaped key depth is exactly what the cap exists to expose.</li>
 *   <li>{@link Reason#STEPS} - more nodes or page round-trips than the step budget allows. A step budget can be
 *       smaller than the work needed to re-establish a resume position, so a truncation here could hand back a cursor
 *       that makes no forward progress - a livelock dressed up as paging. A visible failure is the honest outcome.</li>
 *   <li>{@link Reason#SEGMENT} - a stored name that is not a traversal-free path segment ({@code .}, {@code ..},
 *       empty, a backslash, a control character). {@link ArtifactStore#key} and {@link ArtifactStore#segment} keep
 *       such a name out of the store on the write path, so meeting one while reading means the key space was written
 *       around those guards; the traversal refuses to compose it into a key rather than walking, disclosing or
 *       serving it.</li>
 * </ul>
 *
 * <p>It extends {@link IOException} so it rides the store-traversal signature every caller already handles, and
 * carries the offending {@link #key()} so an operator sees <em>which</em> key tripped which bound.
 */
public final class TraversalException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Which bound was breached - the enumeration a caller switches on to decide between "refuse the request" and
     *  "serve what is durably stored and flag the anomaly". */
    public enum Reason {
        /** A node deeper below the traversal root than the depth cap allows. */
        DEPTH,
        /** More nodes opened, or page round-trips issued, than the step budget allows. */
        STEPS,
        /** A stored name that is not a traversal-free path segment. */
        SEGMENT
    }

    private final Reason reason;

    private final String key;

    public TraversalException(Reason reason, String key, String detail) {
        super(reason + " bound tripped at '" + key + "': " + detail);
        this.reason = reason;
        this.key = key;
    }

    /** Which bound was breached. */
    public Reason reason() {
        return reason;
    }

    /** The key (or prefix) the traversal was at when the bound was breached. */
    public String key() {
        return key;
    }
}
