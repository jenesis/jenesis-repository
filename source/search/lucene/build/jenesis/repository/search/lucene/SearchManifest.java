package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.store.LineDocument;

/**
 * The head of the search index: the generation a reader loads, the format version (a stale reader discards rather than
 * mis-reads), the document count, the generation's SHA-256, and when the index was last rebuilt from truth. Committed
 * by compare-and-set; a reader compares the generation to decide whether to swap. Every full rebuild writes
 * {@code reconciled} and an incremental cutover carries it, so an idle pass decides from the manifest it read anyway. A
 * line-oriented document.
 */
public record SearchManifest(int generation, int format, long documents, String checksum, Instant reconciled) {

    private static final String HEADER = "jenesis-search";

    /** This manifest as the stored, line-oriented document. */
    public byte[] serialize() {
        return LineDocument.of(HEADER, 1)
                .field("generation", generation)
                .field("format", format)
                .field("documents", documents)
                .field("checksum", checksum)
                .field("reconciled", reconciled.toEpochMilli())
                .bytes();
    }

    /** Parse a stored manifest; a blank, corrupt or unrecognised body, or a malformed value, yields a format-{@code 0}
     *  sentinel read as no usable index, so the pass rebuilds rather than throwing on every run. */
    public static SearchManifest parse(byte[] bytes) {
        Optional<LineDocument> document = LineDocument.parse(bytes, HEADER);
        if (document.isEmpty()) {
            return unusable();
        }
        LineDocument read = document.get();
        try {
            return new SearchManifest(Integer.parseInt(read.field("generation").orElse("0").trim()),
                    Integer.parseInt(read.field("format").orElse("0").trim()),
                    Long.parseLong(read.field("documents").orElse("0").trim()),
                    read.field("checksum").orElse("").trim(),
                    Instant.ofEpochMilli(Long.parseLong(read.field("reconciled").orElse("0").trim())));
        } catch (NumberFormatException _) {
            return unusable();
        }
    }

    private static SearchManifest unusable() {
        return new SearchManifest(0, 0, 0, "", Instant.EPOCH);
    }
}
