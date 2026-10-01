package build.jenesis.repository.walk;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * The one depth-first walk of a repository's {@code publish/} pointer tree ({@code publish/<request-path> ->
 * <sha256>}), shared by every reader that enumerates published assets so the walk - its ordering, its quarantine
 * exclusion, its withheld-pointer skipping and its pointer-only metadata read - lives in exactly one place rather
 * than being re-implemented per surface. The server's {@code /api/assets} catalogue layers format/coordinate
 * enrichment on top of it, and the console's NDJSON asset export writes each entry as it is reached; both are the
 * same walk with a different {@link Visitor}.
 *
 * <p>It is a pure metadata walk: each entry's path, size and SHA-256 come straight from the pointer (the pointer's
 * content <em>is</em> the hex digest; the size is a {@code blobs/<hash>} stat), <strong>no artifact blob is ever
 * opened</strong>, in keeping with the read-first bias. A path a {@code PublishInterceptor} withholds (a retracted or
 * quarantined artifact) is skipped through the {@link ServableNames servable-name seam} ({@link ServableNames#state}),
 * so the walk yields exactly what a {@code GET} would, and the top-level {@code /quarantine} review subtree is never
 * descended.
 *
 * <p>The order is a depth-first walk of the pointer tree - name-sorted siblings, each container fully descended
 * before the next sibling - so a caller can page by an opaque cursor (a resumed walk skips every entry that sorts at
 * or before it, {@link #walk(String, int, Visitor) walk}'s {@code after} argument). Because the walk descends
 * {@code data/} before the sibling leaf {@code data.txt}, the cursor order treats the {@code '/'} separator as
 * sorting below every other character ({@link Trees#order}), which is <em>not</em> {@link String#compareTo} order;
 * comparing the two the naive way drops or duplicates a file-vs-directory sibling across a page boundary. A bounded
 * page is a slice of pointer metadata, the only full materialization the streaming principle allows.
 */
public final class PublishedAssets {

    /** The store subtree the walk is rooted at: the formats' published request-path pointer tree. */
    private static final String ROOT = "publish";

    private final ArtifactStore store;
    private final Publication publication;
    private final ServableNames names;

    /** Walk the {@code publish/} tree of a doubly-scoped ({@code root.scope(tenant).scope(repository)}) store. */
    public PublishedAssets(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** The explicit seam: reuse a {@link Publication} already constructed over the same store rather than making a
     *  second (so the withheld-pointer check runs the caller's interceptor chain). */
    public PublishedAssets(ArtifactStore store, Publication publication) {
        this.store = store;
        this.publication = publication;
        this.names = new ServableNames(store, publication);
    }

    /** One walked pointer fact: the serving request path (leading slash), the blob's stored size, and the SHA-256 hex
     *  the pointer names - the format-neutral facts every reader shares, before any layout enrichment. */
    public record Entry(String path, long size, String sha256) {
    }

    /** A sink the walk hands each emitted {@link Entry} to, in emission order. */
    @FunctionalInterface
    public interface Visitor {
        void visit(Entry entry) throws IOException;
    }

    /**
     * Walk the pointer tree depth-first in emission order, handing each served leaf to {@code visitor}. When
     * {@code after} is non-null the walk resumes strictly past it (the relative path - no leading slash - of the last
     * entry a previous slice emitted), so a caller pages without re-emitting. At most {@code cap} entries are emitted;
     * pass {@link Integer#MAX_VALUE} for an unbounded walk (the whole-repository export). A caller that needs to learn
     * whether a further page exists asks for one more than it will keep and checks the count, as {@code AssetCatalog}
     * does.
     */
    public void walk(String after, int cap, Visitor visitor) throws IOException {
        collect(after, cap, new int[]{0}, visitor);
    }

    /**
     * The bounded descent, through the shared {@link PagedTreeWalk}.
     *
     * <p>The {@code quarantine} review subtree is declined through {@link PagedTreeWalk.Prune} rather than filtered
     * out of the emitted leaves - it is stored but never served, so it is not an enumerable asset, and it is
     * <em>never entered</em> rather than entered and dropped.
     */
    private void collect(String after, int cap, int[] emitted, Visitor visitor) throws IOException {
        // Depth is the shared default, the store's own write ceiling: no key deeper than it can be stored, so a
        // chain past it is a store nothing here wrote, and the walk refuses it by name rather than descending it.
        PagedTreeWalk.bounded()
                .entries(cap == Integer.MAX_VALUE ? PagedTreeWalk.ENTRIES : cap)
                .walk(store, ROOT, cursor(after), key -> {
                    if (emitted[0] >= cap) {
                        return;
                    }
                    emit(key.substring(ROOT.length() + 1), after, emitted, visitor);
                }, container -> !ServableNames.reviewSubtree(relative(container)));
    }

    /** The walk's cursor is a full store key; this walk's callers page by the relative request path the last entry
     *  carried, so the two are translated at this one point rather than at every call site. */
    private static String cursor(String after) {
        return after == null || after.isEmpty() ? null : ROOT + "/" + after;
    }

    /** A container's path relative to {@code publish/} - empty for the root itself. */
    private static String relative(String key) {
        return key.length() <= ROOT.length() ? "" : key.substring(ROOT.length() + 1);
    }

    private void emit(String relative, String after, int[] emitted, Visitor visitor) throws IOException {
        if (after != null && Trees.order(relative, after) <= 0) {
            return;
        }
        String requestPath = "/" + relative;
        // The one enumeration screen: a leaf is an enumerable asset only when a GET would serve it (published, blob
        // present, not withheld) - routed through the servable-name seam so this walk and a download can never disagree
        // on what is held. Withheld (a retraction interceptor) or blob-gone leaves are skipped.
        if (names.state(requestPath) != ServableNames.State.SERVABLE) {
            return;
        }
        // Race-tolerant follow-up read: the servable screen above and this pointer read are two separate store round
        // trips, and a concurrent unpublish/evict/DELETE can remove the pointer in the window between them (a single
        // store.exists stat on S3/GCS/Azure). A pointer that vanished after it screened SERVABLE is SKIPPED, not thrown
        // - throwing here would abort the whole enumeration (truncating the /assets NDJSON export, 500-ing
        // /api/assets). This only relaxes a *vanished* pointer: a genuinely withheld path was already screened out
        // above and never reaches here, so no withheld path is disclosed.
        Optional<String> pointer = publication.blob(requestPath);
        if (pointer.isEmpty()) {
            return;
        }
        String hash = pointer.get();
        long size = store.size("blobs/" + hash);
        visitor.visit(new Entry(requestPath, size, hash));
        emitted[0]++;
    }
}
