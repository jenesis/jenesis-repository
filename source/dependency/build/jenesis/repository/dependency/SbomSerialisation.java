package build.jenesis.repository.dependency;

/** How an SBOM document is serialised, read off its first token past a UTF-8 byte-order mark and whitespace - the sniff
 *  every SBOM parser dispatches on. */
enum SbomSerialisation {

    /** It opens with <code>&#123;</code> or {@code [}. */
    JSON,

    /** It opens with {@code <}. */
    XML,

    /** It opens with anything else: a line-oriented serialisation, or no SBOM at all. */
    TEXT,

    /** It is empty, or whitespace alone. */
    EMPTY;

    /** The serialisation of {@code document}. */
    static SbomSerialisation of(byte[] document) {
        for (int index = start(document); index < document.length; index++) {
            byte b = document[index];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            }
            if (b == '{' || b == '[') {
                return JSON;
            }
            return b == '<' ? XML : TEXT;
        }
        return EMPTY;
    }

    /** Where {@code document}'s content starts: past a leading UTF-8 byte-order mark. */
    static int start(byte[] document) {
        return document.length >= 3
                && (document[0] & 0xFF) == 0xEF && (document[1] & 0xFF) == 0xBB && (document[2] & 0xFF) == 0xBF
                ? 3 : 0;
    }
}
