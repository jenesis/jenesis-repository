package build.jenesis.repository.index.keys;

/**
 * The one spelling of the published index's storage keys - written by the index task, read by the console.
 *
 * <p>Both the writer and the reader are consumers of these names, and only the writer could see them. That is what
 * made the console's copy possible and what made a divergence silent: a console reading a key nothing writes any
 * more renders "no index published yet" rather than failing, so the wrong answer looks exactly like the empty one.
 */
public final class PublishedIndexKeys {

    /** The root the published-index feature owns, and the prefix its reclamation namespace declares. */
    public static final String PREFIX = "index/publish";

    /** The descriptor a reader loads to learn what the published index holds. */
    public static final String DESCRIPTOR = PREFIX + "/descriptor";

    /** The chunk space the descriptor points into. */
    public static final String CHUNKS = PREFIX + "/chunks";

    /** The retraction flag a release raises. */
    public static final String RETRACT = PREFIX + "/retract";

    private PublishedIndexKeys() {
    }
}
