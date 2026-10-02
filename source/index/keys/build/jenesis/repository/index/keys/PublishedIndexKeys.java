package build.jenesis.repository.index.keys;

/**
 * The one spelling of the published index's storage keys - written by the index task, read by the console, which on
 * a key nothing writes would render "no index published yet" rather than fail.
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
