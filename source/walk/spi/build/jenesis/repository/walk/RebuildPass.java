package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

import module java.base;

/**
 * The shared rebuild pass: one walk over the pointer roots feeding <em>every</em> {@link WalkConsumer} - so N
 * metadata rebuilders never mean N tree walks. This is the walk half of the two-route derived-metadata contract
 * made runnable: a scheduled surface resolves the walk, gathers {@link WalkConsumer#discovered()} and calls
 * {@link #run} on a cadence; steady-state freshness stays with the publication events
 * ({@code PublicationObserver.onPublished} / {@code onDeleted}), and this pass is the first-activation back-fill,
 * the periodic refresh and the self-heal - a consumer enabled late rebuilds its whole view from it.
 *
 * <p><b>What a consumer is handed.</b> Every leaf under the walked roots that is a serving pointer - a small object
 * naming a SHA-256, in either of the two dialects a stored pointer body uses (the bare lower-case hex the free
 * {@code publish/} and {@code blobs/} pointers carry, or the algorithm-qualified {@code sha256:<hex>} an OCI tag
 * pointer carries, both read through {@link ServableNames#hash(byte[])}, the one seam that owns that dialect) - is
 * delivered as one {@link WalkConsumer#onRetained} call with the descriptor
 * richness this neutral site has: under the core's own {@code publish/} namespace the descriptor's path is the
 * serving request path (exactly what {@code onPublished} / {@code onDeleted} carry); under any other pointer root
 * (a format's own blobs-namespace keys) the path is the raw store key, whose layout only the owning format knows -
 * a coordinate-needing consumer describes it through its format. The blob hash is always set; the size is the
 * stored blob's, or {@code -1} for a pointer whose blob is missing - delivered, not skipped, so a reconcile
 * consumer sees exactly the torn state it exists to repair. A leaf that names no hash (a sidecar row, a marker, an
 * index) is never delivered.
 *
 * <p><b>The withheld screen.</b> Under the free {@code publish/} namespace the pass yields exactly what a {@code GET}
 * would, applying the same withheld screen {@code PublishedAssets} does through {@code Publication.located}: the
 * quarantine review subtree ({@code publish/quarantine/...}) is stored but never served, so it is never delivered
 * (no phantom index entry for a held pointer); and a path a screen retracts after the fact (a
 * {@code PublishInterceptor.withheld} verdict against an artifact that has served for months) is skipped, so a
 * rebuild never reinstates a retracted-after-advisory artifact into a consumer's index. A torn pointer whose blob is
 * merely gone is <em>not</em> withheld - it is still delivered as the torn state a reconcile consumer repairs, so
 * only a path whose blob is present yet unlocatable is screened out. The screen is the {@code publish/} withhold
 * model's; a format's own blobs-namespace root carries no publication pointer and is delivered raw as before.
 *
 * <p><b>Delivery and failure.</b> The walk's contract carries over <em>whole</em>: every retained pointer is delivered
 * exactly once per pass, and at least once for the uncommitted stride tail after a crash-resume - consumers are
 * idempotent - and the walk's flush hook reaches them too, as {@link WalkConsumer#beforeCheckpoint}, fired on every
 * consumer before the cursor covering those deliveries is committed. That forward is what makes a buffering consumer
 * (one durable write per stride rather than per artifact) safe rather than lossy: without it a landed cursor would
 * skip items still sitting in a consumer's buffer when the process died, and nothing would ever replay them.
 * A consumer failure propagates and stops this worker's segment with its claim left to expire; the pass then
 * resumes from the last committed cursor, so a failure delays a rebuild but never silently truncates it - a stuck
 * pass is visible through {@link ArtifactWalk#pass} / {@link ArtifactWalk#segments}, never a quietly-incomplete
 * view served as whole. {@link WalkConsumer#onPassStarted} fires on this worker before its first delivery (and
 * before {@code onPassCompleted} on an empty store - a rebuild from an empty truth is still a rebuild);
 * {@link WalkConsumer#onPassCompleted} fires when this worker observed the pass complete. The hooks are per-worker:
 * with one scheduled worker driving the pass - the default - a snapshot rebuilder sees the whole pass between its
 * hooks, while a deployment that fans {@code run} across threads or nodes keeps every streaming consumer correct
 * but must not drive a snapshot rebuilder this way (its accumulation would span workers) - the degrade-and-say-so
 * each such consumer records.
 */
public final class RebuildPass {

    /** The pass-state scope every joiner shares ({@code walks/rebuild/...}) - one pass, however many workers. */
    public static final String CONSUMER = "rebuild";

    /** A pointer names a hash in a few dozen bytes; a larger leaf is other metadata and is never read whole. */
    private static final int LARGEST_POINTER = 1024;

    private RebuildPass() {
    }

    /**
     * Join the shared rebuild pass over {@code pointerRoots} (the free {@code publish} namespace plus every
     * blobs-namespace root the caller's installed formats declare) and stream every retained pointer to every one
     * of {@code consumers}; empty when there is no consumer to feed - nothing is enumerated and no pass state is
     * touched. Returns the pass as this worker last saw it: {@code COMPLETE} when it just finished, {@code ACTIVE}
     * while other holders still own segments - re-invoke on the next cadence, or let another node finish.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, List<String> pointerRoots,
                                         List<WalkConsumer> consumers) throws IOException {
        return run(walk, store, new Publication(store), pointerRoots, consumers);
    }

    /**
     * The explicit seam: join the shared rebuild pass reusing a {@link Publication} already constructed over the same
     * store rather than making a second, so the withheld screen over the {@code publish/} namespace runs the caller's
     * interceptor chain (the core's {@code ServiceLoader}-discovered chain is empty; a test or an embedder
     * injects one here) - the same seam {@code PublishedAssets} exposes for the same reason.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Publication publication,
                                         List<String> pointerRoots, List<WalkConsumer> consumers) throws IOException {
        if (consumers.isEmpty()) {
            return Optional.empty();
        }
        Delivery delivery = new Delivery(walk, store, publication, List.copyOf(consumers));
        WalkPass pass = walk.walk(store, CONSUMER, roots(pointerRoots), delivery);
        if (pass.complete()) {
            delivery.started(pass);
            for (WalkConsumer consumer : consumers) {
                consumer.onPassCompleted(pass);
            }
        }
        return Optional.of(pass);
    }

    /** Validate and normalise the caller's pointer roots: at least one, and never one of the store namespaces the
     *  walk or collector bookkeeping owns - walking {@code blobs} for pointers is a caller bug, not a layout. */
    private static List<String> roots(List<String> pointerRoots) {
        if (pointerRoots == null || pointerRoots.isEmpty()) {
            throw new IllegalArgumentException("a rebuild pass needs at least one pointer root, e.g. publish");
        }
        List<String> roots = pointerRoots.stream().distinct().sorted().toList();
        for (String root : roots) {
            if (root == null || root.isBlank() || root.equals("blobs") || root.equals("gc") || root.equals("walks")) {
                throw new IllegalArgumentException("not a pointer root: " + root);
            }
        }
        return roots;
    }

    /** Whether a normalised pointer body is a lower-case SHA-256 hex - the only leaf shape delivered as an artifact.
     *  Applied to what {@link ServableNames#hash(byte[])} answers, never to the raw body: the raw body carries the
     *  dialect, and this judges the hash it named. */
    private static boolean hash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** The pass's visitor: turn each pointer leaf into one descriptor and fan it out to every consumer, firing
     *  {@code onPassStarted} lazily before the first delivery - read from the live manifest, so the hook carries
     *  the generation actually running rather than a guess made before the walk began. */
    private static final class Delivery implements ArtifactWalk.KeyVisitor {

        private final ArtifactWalk walk;
        private final ArtifactStore store;
        private final ServableNames names;
        private final List<WalkConsumer> consumers;
        private boolean started;

        private Delivery(ArtifactWalk walk, ArtifactStore store, Publication publication,
                         List<WalkConsumer> consumers) {
            this.walk = walk;
            this.store = store;
            this.names = new ServableNames(store, publication);
            this.consumers = consumers;
        }

        private void started(WalkPass pass) throws IOException {
            if (started) {
                return;
            }
            started = true;
            for (WalkConsumer consumer : consumers) {
                consumer.onPassStarted(pass);
            }
        }

        /** The walk is about to commit {@code cursor}: hand every consumer its flush moment first, so a consumer that
         *  buffers derived writes is never resumed past an item whose write is still in its buffer. The walk's own
         *  contract ({@link ArtifactWalk.KeyVisitor#beforeCheckpoint}) is what carries over here - without this
         *  forward a consumer could only ever write through per item, and a buffering one would lose, permanently,
         *  every item covered by a cursor that landed. Suppressed before the first delivery on this worker: nothing
         *  has been handed over, so there is nothing to flush, and {@link WalkConsumer#onPassStarted} always comes
         *  first. */
        @Override
        public void beforeCheckpoint(String cursor) throws IOException {
            if (!started) {
                return;
            }
            for (WalkConsumer consumer : consumers) {
                consumer.beforeCheckpoint(cursor);
            }
        }

        @Override
        public void visit(String key) throws IOException {
            long size = store.size(key);
            if (size < 0 || size > LARGEST_POINTER) {
                return;
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                return; // removed between the walk's listing and this read - nothing is served through it
            }
            // The body's dialect is read through the one seam that owns it, never re-parsed here: a pointer body is
            // either the bare lower-case hex the free publish/ and blobs/ pointers carry or the algorithm-qualified
            // sha256:<hex> of the OCI Distribution tag pointers, and both denote the same blob. Reading it as bare hex
            // instead threw every tag pointer away as "not a serving pointer", so a consumer over an OCI root was
            // handed nothing and then reported itself converged - the silently-incomplete view §5 forbids, and the
            // same normalisation ServableNames.hash was introduced for on the withhold screen.
            String named = ServableNames.hash(pointer.get().content());
            if (!hash(named)) {
                return; // a sidecar row, marker or index - not a serving pointer, never delivered
            }
            String path = key.startsWith("publish/") ? key.substring("publish".length()) : key;
            if (key.startsWith("publish/") && withheld(path)) {
                return; // withheld from serving - a GET would 404 it, so a rebuild must not reinstate it into an index
            }
            if (!started) {
                started(walk.pass(store, CONSUMER)
                        .orElseThrow(() -> new IOException("no rebuild pass to deliver under")));
            }
            ArtifactDescriptor artifact = new ArtifactDescriptor(null, null, null, path, null, false, named,
                    store.size("blobs/" + named));
            for (WalkConsumer consumer : consumers) {
                consumer.onRetained(artifact, store);
            }
        }

        /** Whether the free {@code publish/} namespace withholds this request path from serving - the quarantine read
         *  side {@code PublishedAssets} screens, mirrored here through the one servable-name seam so a rebuild never
         *  reinstates a withheld artifact into a consumer's index. The quarantine review subtree
         *  ({@code publish/quarantine/...}) is stored but never served, exactly as {@code PublishedAssets} never
         *  descends it. Otherwise the discrimination is the seam's first-class {@link ServableNames.State}: a
         *  {@link ServableNames.State#WITHHELD} path (an interceptor retracts it, or a {@code withheld/<hash>} marker) is
         *  skipped; a {@link ServableNames.State#BLOB_GONE} torn pointer is <em>not</em> withheld - it is delivered as
         *  the torn state a reconcile consumer repairs. This replaces the former hand-rolled
         *  {@code located().isEmpty() && blobs exists} test, which mis-classified a withheld-AND-gc-reclaimed pointer as
         *  merely torn (its blob absent flipped the {@code &&} to false) and so delivered it; the seam runs the withhold
         *  probe first, so such a pointer now reads {@code WITHHELD} and is correctly skipped. */
        private boolean withheld(String requestPath) throws IOException {
            if (requestPath.equals("/quarantine") || requestPath.startsWith("/quarantine/")) {
                return true;
            }
            return names.state(requestPath) == ServableNames.State.WITHHELD;
        }
    }
}
