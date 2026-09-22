package build.jenesis.repository.index;

import module java.base;

/**
 * The head of the published index: the current chain of immutable chunks a consumer syncs against, the durable
 * high-water {@link Cursor} incremental passes advance, the instant of the last full-snapshot rebase, and the
 * superseded chunks awaiting garbage collection. A consumer fetches this, diffs its {@link #chain} against what it
 * already holds, and fetches only the unseen chunk ids - regular sync without ever re-walking the repository.
 * Serialised as a small, line-oriented document so it parses without a JSON reader in this framework-free module; the
 * web adapter re-serialises it as JSON for external consumers.
 */
public record IndexDescriptor(int generation, Cursor watermark, Instant rebased,
                              List<Chunk> chain, List<Superseded> superseded) {

    private static final String HEADER = "jenesis-index";

    public IndexDescriptor {
        chain = List.copyOf(chain);
        superseded = List.copyOf(superseded);
    }

    /**
     * The compound resume cursor an incremental pass advances and resumes strictly after: the high-water publish
     * {@code instant} and, at that instant, the last-processed served {@code path}. The instant alone cannot resume a
     * pass safely: two artifacts published in the very same millisecond can be split across passes (a pass ticks
     * between them), and the later-walked one - published exactly <em>at</em> the committed instant - is neither past
     * a bare instant watermark (so an instant-only incremental would skip it forever, recovered only by the periodic
     * rebase) nor safely re-includable (re-walking everything at the instant each pass would duplicate the records
     * already chained). Ordering by ({@code instant}, then {@code path}) makes the cursor a single monotonic mark: an
     * incremental pass re-includes only same-instant artifacts whose path sorts <em>after</em> the last processed one
     * - recovering the split-across-passes artifact without re-appending those already done.
     */
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

        /** Whether an artifact at {@code (published, path)} sorts strictly after this cursor - i.e. an incremental
         *  pass must (re-)index it: published past the instant, or published at the instant with a later path. */
        public boolean precedes(Instant published, String path) {
            return compareTo(new Cursor(published, path)) < 0;
        }
    }

    /** One immutable chunk in the chain: its content-addressed id (also its checksum), byte sizes, record count and
     *  the publish-instant range its records span. */
    public record Chunk(String id, long uncompressedSize, long compressedSize, int records,
                        Instant minPublished, Instant maxPublished) {
    }

    /** A chunk dropped from a superseded chain, retained until the grace instant so an in-flight consumer finishes. */
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
     * Parse a <b>stored</b> descriptor document; a blank, unrecognised, torn or garbled body yields {@link #empty} -
     * the parse is total, so a corrupt head never throws out of every scheduled pass and the descriptor endpoint. It
     * reads as the empty index instead, and the next pass self-heals by rebasing a fresh chain over it (the lost
     * chain's chunk objects become unreferenced garbage; see the orphan note on {@code PublishedIndexTask}).
     *
     * <p><b>This is not what {@code GET /api/index} serves.</b> The wire form is JSON, assembled by
     * {@code PublishedIndex.descriptorJson}; this reads the stored document the passes commit. The two are
     * deliberately different and both are reachable from outside, so it is worth saying which is which: because the
     * parse is total, handing it a served JSON body returns {@link #empty} rather than failing, and a consumer
     * written against the wrong form therefore sees a repository that holds nothing and syncs nothing while looking
     * healthy. The totality above is right for a corrupt stored head and is exactly what hides this mistake, which
     * is why the warning belongs here rather than in the caller. {@code IndexConsumerSyncE2ETest} crosses that seam.
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

    /** Parse a {@code watermark <instant> [<path>]} line into its compound cursor: the instant is the first token
     *  after the key, the path (empty when absent) is the untouched remainder, so a served path bearing a space is
     *  carried verbatim rather than split. */
    private static Cursor parseCursor(String line) {
        String rest = line.substring(line.indexOf(' ') + 1).stripLeading();       // "<instant>" or "<instant> <path>"
        int afterInstant = rest.indexOf(' ');
        if (afterInstant < 0) {
            return new Cursor(Instant.parse(rest), "");
        }
        return new Cursor(Instant.parse(rest.substring(0, afterInstant)), rest.substring(afterInstant + 1));
    }
}
