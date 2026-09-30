package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.store.LineDocument;

/**
 * The head of the search index: the current snapshot generation a reader loads, the index format version (bumped on
 * an incompatible index-layout change so a stale reader discards rather than mis-reads), the document count, the
 * SHA-256 checksum of the generation it names, and when the index was last rebuilt from truth. Committed by the pass
 * through the store's compare-and-set, so a reader's refresh compares the generation - unchanged means it keeps the
 * loaded index untouched, changed means it opens the new one and swaps. {@code reconciled} is what makes the pass's own
 * reconcile due by time: every full rebuild writes it, and an incremental cutover carries it over, so an idle pass
 * decides from the manifest it had to read anyway and writes nothing. A small line-oriented document.
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

    /** Parse a stored manifest document; a blank, corrupt or unrecognised body yields a format-{@code 0} sentinel a
     *  reader treats as no usable index. A malformed value (a garbled generation, a {@code generation} line with no
     *  number) is tolerated rather than thrown - the read path answers by name on an unusable manifest, and the
     *  {@code Lease}-guarded pass parses the same object before rebuilding, so a {@code NumberFormatException} here
     *  would otherwise make every pass throw and the index never self-heal. */
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
