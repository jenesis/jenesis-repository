package build.jenesis.repository.index;

import module java.base;

/**
 * The head of the published index: the chain of immutable chunks a consumer syncs against, the high-water
 * {@link Cursor} incremental passes advance, the instant of the last rebase, and superseded chunks awaiting deletion. A
 * consumer diffs {@link #chain} against what it holds and fetches only unseen chunk ids. Stored as a small
 * line-oriented document so this module needs no JSON reader; the web adapter serves it as JSON.
 */
public record IndexDescriptor(int generation, Cursor watermark, Instant rebased,
                              List<Chunk> chain, List<Superseded> superseded) {

    private static final String HEADER = "jenesis-index";

    public IndexDescriptor {
        chain = List.copyOf(chain);
        superseded = List.copyOf(superseded);
    }

    /** The resume cursor an incremental pass resumes strictly after: the high-water publish {@code instant} and, at
     *  that instant, the last processed served {@code path}. The instant alone is not enough: two artifacts published
     *  in one millisecond can be split across passes, and the later one is neither past an instant-only watermark nor
     *  safely re-includable without duplicating records already chained. Ordering by ({@code instant}, {@code path})
     *  makes the cursor one monotonic mark, so a pass re-includes only same-instant artifacts whose path sorts after
     *  the last one processed. */
    public record Cursor(Instant instant, String path) implements Comparable<Cursor> {

        /** The cursor a first pass rebases from: the epoch instant and the empty path, below every real record. */
        public static final Cursor START = new Cursor(Instant.EPOCH, "");

        public Cursor {
            Objects.requireNonNull(instant, "instant");
            Objects.requireNonNull(path, "path");
        }

        @Override
        public int compareTo(Cursor other) {
            int byInstant = instant.compareTo(other.instant);
            return byInstant != 0 ? byInstant : path.compareTo(other.path);
        }

        /** Whether an artifact at {@code (published, path)} sorts strictly after this cursor, so an incremental pass
         *  must index it. */
        public boolean precedes(Instant published, String path) {
            return compareTo(new Cursor(published, path)) < 0;
        }
    }

    /** One immutable chunk in the chain: its content-addressed id (also its checksum), sizes, record count and the
     *  publish-instant range its records span. */
    public record Chunk(String id, long uncompressedSize, long compressedSize, int records,
                        Instant minPublished, Instant maxPublished) {
    }

    /** A chunk dropped from a superseded chain, kept until the grace instant so an in-flight consumer finishes. */
    public record Superseded(String id, Instant deleteAfter) {
    }

    /** The empty index a first pass rebases from: generation 0, the {@link Cursor#START} watermark, no chunks. */
    public static IndexDescriptor empty() {
        return new IndexDescriptor(0, Cursor.START, Instant.EPOCH, List.of(), List.of());
    }

    /** This descriptor as the stored, line-oriented document. */
    public byte[] serialize() {
        StringBuilder out = new StringBuilder(256);
        out.append(HEADER).append(" 1\n");
        out.append("generation ").append(generation).append('\n');
        out.append("watermark ").append(watermark.instant());
        if (!watermark.path().isEmpty()) {
            out.append(' ').append(watermark.path());          // omitted at START, so the line stays "watermark <instant>"
        }
        out.append('\n');
        out.append("rebased ").append(rebased).append('\n');
        for (Chunk chunk : chain) {
            out.append("chunk ").append(chunk.id()).append(' ')
                    .append(chunk.uncompressedSize()).append(' ')
                    .append(chunk.compressedSize()).append(' ')
                    .append(chunk.records()).append(' ')
                    .append(chunk.minPublished()).append(' ')
                    .append(chunk.maxPublished()).append('\n');
        }
        for (Superseded gone : superseded) {
            out.append("superseded ").append(gone.id()).append(' ').append(gone.deleteAfter()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parse a <b>stored</b> descriptor document. The parse is total: a blank, torn or garbled body yields
     * {@link #empty}, so a corrupt head never throws out of every pass and the descriptor endpoint, and the next pass
     * rebases a fresh chain over it (the lost chain's chunks become unreferenced; see {@code PublishedIndexTask}).
     *
     * <p><b>This is not what {@code GET /api/index} serves.</b> The wire form is JSON from
     * {@code PublishedIndex.descriptorJson}. Because the parse is total, handing it a served JSON body returns
     * {@link #empty} rather than failing, so a consumer written against the wrong form sees an empty repository that
     * looks healthy.
     */
    public static IndexDescriptor parse(byte[] bytes) {
        try {
            int generation = 0;
            Cursor watermark = Cursor.START;
            Instant rebased = Instant.EPOCH;
            List<Chunk> chain = new ArrayList<>();
            List<Superseded> superseded = new ArrayList<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String trimmed = line.trim();
                String[] token = trimmed.split(" ");
                switch (token[0]) {
                    case HEADER -> { }
                    case "generation" -> generation = Integer.parseInt(token[1]);
                    case "watermark" -> watermark = parseCursor(trimmed);
                    case "rebased" -> rebased = Instant.parse(token[1]);
                    case "chunk" -> chain.add(new Chunk(token[1], Long.parseLong(token[2]), Long.parseLong(token[3]),
                            Integer.parseInt(token[4]), Instant.parse(token[5]), Instant.parse(token[6])));
                    case "superseded" -> superseded.add(new Superseded(token[1], Instant.parse(token[2])));
                    default -> { }
                }
            }
            return new IndexDescriptor(generation, watermark, rebased, chain, superseded);
        } catch (RuntimeException corrupt) {
            return empty();
        }
    }

    /** Parse a {@code watermark <instant> [<path>]} line: the instant is the first token, the path the untouched
     *  remainder, so a path holding a space is kept whole. */
    private static Cursor parseCursor(String line) {
        String rest = line.substring(line.indexOf(' ') + 1).stripLeading();       // "<instant>" or "<instant> <path>"
        int afterInstant = rest.indexOf(' ');
        if (afterInstant < 0) {
            return new Cursor(Instant.parse(rest), "");
        }
        return new Cursor(Instant.parse(rest.substring(0, afterInstant)), rest.substring(afterInstant + 1));
    }
}
