package build.jenesis.repository.store;

import module java.base;

/**
 * Uploaded documents kept whole and content-addressed, each beside a small roll-up a listing reads instead of the
 * document: the bytes at {@code blobs/<sha256>}, the roll-up at {@code index/<sha256>.json}, and a {@link RecentIndex}
 * row under {@code recent} naming the document, so a surface pages them newest first in one bounded read.
 *
 * <p><b>The document is the authority.</b> A roll-up and a row are derived from it and can be derived again, so
 * every write lands the document first and every removal deletes it first. A crash part-way through either leaves
 * the same shape - a document with no roll-up, or a roll-up with no document - and {@link #reconcile} settles both:
 * the first is re-derived, the second reaped. Deleting the roll-up first instead would leave a document behind it
 * that the next reconcile re-derives, bringing a removed document back.
 *
 * <p><b>Bounded however much is stored.</b> {@link #recent} is the read a request makes. Everything that must see
 * every document - {@link #reconcile}, {@link #summaries}, {@link #documents} - drains a level a page at a time
 * through {@link Names} and holds one page, never the level.
 *
 * <p>What a roll-up holds, and how it is read back from a document, is the caller's: see {@link Summaries}.
 */
public final class SummarisedDocuments<S> {

    private static final String BLOBS = "blobs";
    private static final String INDEX = "index";
    private static final String RECENT = "recent";
    private static final String SUFFIX = ".json";

    /** How a caller's roll-up is written, read and derived. */
    public interface Summaries<S> {

        /** The roll-up's stored bytes. Deterministic in the document, so two writers of one document agree. */
        byte[] write(S summary);

        /** The roll-up read back; a corrupt one throws a {@link RuntimeException} and is skipped by every read. */
        S read(byte[] content);

        /** The instant the roll-up is ordered by, newest first; {@link Instant#EPOCH} for one with no position in
         *  time, which sorts it last. */
        Instant orderedAt(S summary);

        /** The roll-up derived from a stored document, or empty for one that no longer parses. */
        Optional<S> derive(String id, byte[] document);
    }

    /** A bounded page of roll-ups, newest first, and the cursor the next page resumes after - {@code null} when the
     *  index is exhausted. */
    public record Window<S>(List<S> summaries, String next) {
    }

    private final ArtifactStore store;

    private final Summaries<S> summaries;

    public SummarisedDocuments(ArtifactStore store, Summaries<S> summaries) {
        this.store = Objects.requireNonNull(store, "store");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
    }

    /** Store one document's bytes, returning its id: the SHA-256 of the bytes, so a retried delivery lands on the
     *  same document. */
    public String put(byte[] document) throws IOException {
        return store.writeBlob(new ByteArrayInputStream(document));
    }

    /**
     * Record a stored document's roll-up and its newest-first row, returning whether the roll-up is new - so a caller
     * keeping a running total moves it once however often one document is delivered. The roll-up is written by
     * compare-and-set; a lost race wrote the same bytes.
     */
    public boolean record(String id, S summary) throws IOException {
        boolean fresh = !store.exists(rollUp(id));
        byte[] content = summaries.write(summary);
        Retries.tryUpdate(store, rollUp(id), _ -> content);
        recent().record(summaries.orderedAt(summary), id, id.getBytes(StandardCharsets.UTF_8));
        return fresh;
    }

    /**
     * At most {@code limit} roll-ups after {@code after}, newest first - the read a request makes. A row whose roll-up
     * is gone or corrupt is skipped rather than failing the page; the reconcile settles it.
     */
    public Window<S> recent(String after, int limit) throws IOException {
        RecentIndex.Page page = recent().page(after, limit);
        List<S> window = new ArrayList<>(page.rows().size());
        for (RecentIndex.Row row : page.rows()) {
            // The row names the document and its roll-up is read from index/, so a roll-up has one copy and the
            // newest-first index cannot drift from it.
            summary(new String(row.content(), StandardCharsets.UTF_8)).ifPresent(window::add);
        }
        return new Window<>(List.copyOf(window), page.next());
    }

    /** One document's roll-up, or empty when none is stored under {@code id} or it is corrupt. */
    public Optional<S> summary(String id) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(rollUp(id));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(summaries.read(stored.get().content()));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    /** Every readable roll-up, one at a time in the store's order - for a pass that folds over them all. */
    public void summaries(Visitor<S> visitor) throws IOException {
        Names ids = ids();
        for (String id = ids.next(); id != null; id = ids.next()) {
            Optional<S> summary = summary(id);
            if (summary.isPresent()) {
                visitor.visit(summary.get());
            }
        }
    }

    /** Receives each roll-up {@link #summaries} reads. */
    @FunctionalInterface
    public interface Visitor<S> {

        void visit(S summary) throws IOException;
    }

    /** The ids of every stored document, in the store's order, a page at a time. */
    public Names documents() {
        return Names.over(store, BLOBS);
    }

    /** One stored document's bytes, or empty when none is stored under {@code id}. Documents are small and were
     *  bounded on the way in, so one is read whole, and the open is the existence check. */
    public Optional<byte[]> document(String id) throws IOException {
        try (InputStream in = store.open(blob(id))) {
            return Optional.of(in.readAllBytes());
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    /** Stream one stored document back as it was delivered, returning false when none is stored under {@code id}. */
    public boolean read(String id, OutputStream out) throws IOException {
        if (!store.exists(blob(id))) {
            return false;
        }
        store.read(blob(id), out);
        return true;
    }

    /**
     * Remove one document, its newest-first row and its roll-up, in that order, returning false when nothing was
     * stored under {@code id}. The row's key encodes the instant the roll-up carries, so the roll-up is read before
     * either goes.
     */
    public boolean delete(String id) throws IOException {
        boolean document = store.exists(blob(id));
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(rollUp(id));
        if (!document && stored.isEmpty()) {
            return false;
        }
        if (document) {
            store.delete(blob(id));
        }
        Optional<S> summary = summary(id);
        if (summary.isPresent()) {
            recent().forget(summaries.orderedAt(summary.get()), id);
        }
        if (stored.isPresent()) {
            store.delete(rollUp(id));
        }
        return true;
    }

    /**
     * Settle the roll-ups against the documents, returning how many were repaired: a document with no roll-up, or
     * with a corrupt one, has it derived; a roll-up whose document is gone is reaped with its row; and every roll-up
     * gets its newest-first row, so none is missing from {@link #recent}.
     *
     * <p>The two levels are paged in the same order, since both are keyed by the document id, and walked side by
     * side as a merge, so nothing is held beyond a page of each. A settled store reads each roll-up and row once and
     * writes nothing. A document that no longer parses is left as it is.
     */
    public int reconcile() throws IOException {
        int repaired = 0;
        Names rows = ids();
        Names blobs = documents();
        String row = rows.next();
        String blob = blobs.next();
        while (row != null || blob != null) {
            int order = row == null ? 1 : blob == null ? -1 : row.compareTo(blob);
            if (order < 0) {
                Optional<S> orphan = summary(row);
                if (orphan.isPresent()) {
                    recent().forget(summaries.orderedAt(orphan.get()), row);
                }
                store.delete(rollUp(row));
                repaired++;
                row = rows.next();
                continue;
            }
            Optional<S> summary = order == 0 ? summary(blob) : Optional.empty();
            if (summary.isEmpty()) {
                Optional<byte[]> document = document(blob);
                summary = document.isEmpty() ? Optional.empty() : summaries.derive(blob, document.get());
                if (summary.isPresent()) {
                    byte[] content = summaries.write(summary.get());
                    Retries.tryUpdate(store, rollUp(blob), _ -> content);
                    repaired++;
                }
            }
            if (summary.isPresent()) {
                recent().ensure(summaries.orderedAt(summary.get()), blob, blob.getBytes(StandardCharsets.UTF_8));
            }
            if (order == 0) {
                row = rows.next();
            }
            blob = blobs.next();
        }
        return repaired;
    }

    /** The ids of every roll-up, in the store's order: the index level's names with the suffix stripped. */
    private Names ids() {
        return Names.over(store, INDEX).select(name -> name.endsWith(SUFFIX)
                ? Optional.of(name.substring(0, name.length() - SUFFIX.length())) : Optional.empty());
    }

    private RecentIndex recent() {
        return new RecentIndex(store, RECENT);
    }

    private static String blob(String id) {
        return BLOBS + "/" + id;
    }

    private static String rollUp(String id) {
        return INDEX + "/" + id + SUFFIX;
    }
}
