package build.jenesis.repository.store;

import module java.base;

import static build.jenesis.repository.store.StoredListing.*;
import static build.jenesis.repository.store.ListingLanes.*;
import static build.jenesis.repository.store.ListingMerge.*;

/**
 * The stored framing of a {@link StoredListing} document: a short header - a sequence, the body's length and digests,
 * the entry count - a blank line, and the body.
 */
final class ListingFrame {

    private ListingFrame() {
    }

    static final String MAGIC = "jenesis-listing/1";

    /** The sequence a document written after {@code priorSeq} carries. Monotone per document, and past any
     *  sequence a forgotten predecessor could have reached (a wall-clock floor), so a derived document written
     *  against an earlier sequence never outranks the regenerated one. A first write passes {@code 0}. */
    static long sequence(long priorSeq) {
        return Math.max(priorSeq + 1, System.currentTimeMillis());
    }

    /**
     * The header and body as one array.
     *
     * <p><b>This holds the document twice for the length of the copy</b>, and there is no way around it while
     * {@link ArtifactStore#writeVersioned} takes a {@code byte[]}: a listing needs compare-and-set, and the
     * streaming {@link ArtifactStore#write(String, InputStream)} has none. The copy is therefore the floor rather
     * than an oversight, and it is worth knowing which of the two is the peak - for a repository-wide index the
     * body dominates, so a deployment sizing its heap against the largest listing should budget twice it.
     */
    /** The header bytes a document is stored behind - the half of {@link #frame} a streamed write needs on its own. */
    static byte[] head(Header header) {
        return (MAGIC + "\nseq=" + header.seq() + "\nsize=" + header.size() + "\nmd5=" + header.md5()
                + "\nsha256=" + header.sha256() + "\nentries=" + header.entries()
                + "\n\n").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] frame(Header header, byte[] body) {
        byte[] head = head(header);
        byte[] framed = new byte[head.length + body.length];
        System.arraycopy(head, 0, framed, 0, head.length);
        System.arraycopy(body, 0, framed, head.length, body.length);
        return framed;
    }
    static Document parseFramed(byte[] framed, String key) throws IOException {
        int end = headerEnd(framed, key);
        Header header = parseHeader(new String(framed, 0, end, StandardCharsets.US_ASCII), key);
        // The body alone: a source trailer stored after it is the writer's, never the document a reader is given.
        int body = (int) Math.min(header.size(), framed.length - end - 2L);
        return new Document(header, Arrays.copyOfRange(framed, end + 2, end + 2 + body));
    }

    static int headerEnd(byte[] framed, String key) throws IOException {
        for (int i = 0; i + 1 < framed.length && i < 512; i++) {
            if (framed[i] == '\n' && framed[i + 1] == '\n') {
                return i;
            }
        }
        throw new IOException("not a listing document: " + key);
    }

    static Header readHeader(InputStream in, String key) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0 || head.size() > 512) {
                throw new IOException("not a listing document: " + key);
            }
            if (b == '\n' && previous == '\n') {
                break;
            }
            head.write(b);
            previous = b;
        }
        String text = head.toString(StandardCharsets.US_ASCII);
        return parseHeader(text.endsWith("\n") ? text.substring(0, text.length() - 1) : text, key);
    }

    static Header parseHeader(String head, String key) throws IOException {
        String[] lines = head.split("\n");
        if (lines.length < 4 || !lines[0].equals(MAGIC)) {
            throw new IOException("not a listing document: " + key);
        }
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int equals = lines[i].indexOf('=');
            if (equals <= 0) {
                throw new IOException("malformed listing header line: " + lines[i]);
            }
            fields.put(lines[i].substring(0, equals), lines[i].substring(equals + 1));
        }
        // A document from before the header dropped its SHA-1 carries one more line; it is read and ignored.
        if (!fields.containsKey("seq") || !fields.containsKey("size") || !fields.containsKey("sha256")) {
            throw new IOException("not a listing document: " + key);
        }
        try {
            // An absent entries= is a document written before the count was recorded, and reads as UNKNOWN rather
            // than as zero: "nobody counted" and "counted, and there were none" are the two answers this whole field
            // exists to separate, and defaulting to zero would assert the second from the absence of evidence. The
            // listing-rebuild repair pass regenerates such a document with a count.
            return new Header(Long.parseLong(fields.get("seq")), Long.parseLong(fields.get("size")),
                    fields.getOrDefault("md5", ""), fields.get("sha256"),
                    Long.parseLong(fields.getOrDefault("entries", String.valueOf(Header.UNKNOWN))));
        } catch (NumberFormatException e) {
            throw new IOException("not a listing document: " + key, e);
        }
    }
}
