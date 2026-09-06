package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * The shared rebuild pass: one walk over the pointer roots feeding <em>every</em> {@link WalkConsumer} - so N
 * metadata rebuilders never mean N tree walks. This is the walk half of the two-route derived-metadata contract
 * made runnable: a scheduled surface resolves the walk, gathers {@link WalkConsumer#discovered()} and calls
 * {@link #run} on a cadence - the server's {@code RebuildScheduler} in the free edition, the maintenance
 * scheduler's {@code rebuild} task downstream; steady-state freshness stays with the publication events
 * ({@code PublicationObserver.onPublished} / {@code onDeleted}), and this pass is the first-activation back-fill,
 * the periodic refresh and the self-heal - a consumer enabled late rebuilds its whole view from it.
 *
 * <p><b>Which passes ride this walk, and which open their own.</b> "N rebuilders never mean N walks" is a promise
 * about consumers that want <em>this</em> enumeration: every serving pointer, as a path and a hash, with the withheld
 * screen applied. A pass opens a walk of its own exactly when one of three things differs, and says which in its own
 * javadoc: its <em>roots</em> (the collector's sweep and the reverse-dependency index read {@code blobs/}, the
 * reconcile's reverse leg reads the derived roots), its <em>granularity</em> (search and retention stream one row
 * per published version with its coordinate, where this pass would hand them several pointers per version and no
 * coordinate; the size roll-up folds directories post-order), or its <em>completeness rule</em> (the collector's
 * mark and the two reconciles must see every pointer, withheld ones included - a mark that took this pass's screened
 * view would leave a held artifact's blob unmarked and the sweep would reclaim it). What rides here today is the
 * pointer-level consumers - a module view, a format's inventory backfill - and a new consumer that keys on pointers
 * belongs here rather than on a walk of its own.
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
 * would, applying the same withheld screen {@code PublishedAssets} does through {@code ServableNames.state}: the
 * quarantine review subtree ({@code publish/quarantine/...}) is stored but never served, so it is never delivered
 * (no phantom index entry for a held pointer); and a path a screen retracts after the fact (a
 * {@code PublishInterceptor.withheld} verdict against an artifact that has served for months) is skipped, so a
 * rebuild never reinstates a retracted-after-advisory artifact into a consumer's index. A torn pointer whose blob is
 * merely gone is <em>not</em> withheld - it is still delivered as the torn state a reconcile consumer repairs, so
 * only a path whose blob is present yet unlocatable is screened out. The chain's hold and the quarantine subtree
 * are the {@code publish/} withhold model's; the content-addressed {@code withheld/} marker applies under every
 * root, because a hash withheld is withheld wherever a layout names it. A withheld pointer is not dropped but
 * delivered through {@link WalkConsumer#onWithheld} to the consumers that asked to see it (clause 14).
 *
 * <p><b>Delivery and failure.</b> The walk's contract carries over <em>whole</em>: every retained pointer is delivered
 * exactly once per pass, and at least once for the uncommitted stride tail after a crash-resume - consumers are
 * idempotent - and the walk's flush hook reaches them too, as {@link WalkConsumer#beforeCheckpoint}, fired on every
 * consumer before the cursor covering those deliveries is committed. That forward is what makes a buffering consumer
 * (one durable write per stride rather than per artifact) safe rather than lossy: without it a landed cursor would
 * skip items still sitting in a consumer's buffer when the process died, and nothing would ever replay them.
 * A failure of the <em>walk</em> - a store that will not answer the pass's own read or cursor commit - propagates
 * and stops this worker's segment with its claim left to expire; the pass then resumes from the last committed
 * cursor, so such a failure delays a rebuild but never silently truncates it. A failure of one <em>consumer</em>
 * fails that consumer alone: it is recorded under {@link #FAILED_SPACE} with the generation and the key, the
 * consumer receives nothing more in this generation, the others converge, and the next generation redelivers
 * everything to it. Its projection is therefore incomplete for exactly one generation and says so durably - the
 * task that drove the pass reports the consumer as failed - rather than every other consumer's rebuild waiting on
 * the one that broke, which is what a shared cursor used to cost. Either way nothing is served as whole that is
 * not: a stuck pass is visible through {@link ArtifactWalk#pass} / {@link ArtifactWalk#segments}, a failed
 * consumer through {@link #failed}. {@link WalkConsumer#onPassStarted} fires on this worker before its first delivery (and
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

    /** Where a consumer's failure in a generation is recorded under the default scope - see {@link #failedSpace}. */
    public static final String FAILED_SPACE = failedSpace(CONSUMER);

    /** Where a consumer's failure in a generation is recorded under {@code scope}, one small object per consumer
     *  name - read by the task that drove the pass to report it, cleared when a later generation reaches the
     *  consumer again. */
    public static String failedSpace(String scope) {
        return "walks/" + scope + "/failed";
    }

    /** A consumer's failure in a generation: which consumer, in which generation, on which key (or {@code null}
     *  for a pass hook), and what it threw. */
    public record Failed(String consumer, long generation, String key, String failure) {

        byte[] encoded() {
            return (generation + "\n" + (key == null ? "" : key) + "\n" + failure).getBytes(StandardCharsets.UTF_8);
        }

        static Failed decode(String consumer, byte[] body) {
            String[] lines = new String(body, StandardCharsets.UTF_8).split("\n", 3);
            long generation;
            try {
                generation = Long.parseLong(lines[0].trim());
            } catch (NumberFormatException _) {
                generation = -1L;
            }
            return new Failed(consumer, generation, lines.length > 1 && !lines[1].isEmpty() ? lines[1] : null,
                    lines.length > 2 ? lines[2] : "");
        }
    }

    /** Every consumer recorded as failed under the default scope, whatever the generation. */
    public static List<Failed> failed(ArtifactStore store) throws IOException {
        return failed(store, CONSUMER);
    }

    /** Every consumer recorded as failed under {@code scope}, whatever the generation - a listing of one small
     *  space, bounded by the number of consumers, never by the store. */
    public static List<Failed> failed(ArtifactStore store, String scope) throws IOException {
        List<Failed> failed = new ArrayList<>();
        for (String name : store.list(failedSpace(scope))) {
            store.readVersioned(failedSpace(scope) + "/" + name)
                    .ifPresent(body -> failed.add(Failed.decode(name, body.content())));
        }
        return failed;
    }

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
        return run(walk, store, new Publication(store), Roots.pointers(pointerRoots), consumers);
    }

    /**
     * The store roots that make up each {@link WalkConsumer.Family}: the deployment names them, the pass enumerates
     * only the families its consumers listen on. Pointer roots are validated as such (never {@code blobs}, {@code gc}
     * or {@code walks}); the other families' roots may be empty, in which case a consumer listening on that family
     * is handed nothing - which is the shape a deployment without an inventory has.
     */
    public record Roots(List<String> pointers, List<String> inventory, List<String> blobs, List<String> derived) {

        public Roots {
            // Empty pointer roots are legitimate here (a pass whose consumers listen on other families only); what
            // is refused is a root that is not a pointer root, and a pass with no root at all is refused by run.
            pointers = pointers == null || pointers.isEmpty() ? List.of() : RebuildPass.roots(pointers);
            inventory = normalised(inventory);
            blobs = normalised(blobs);
            derived = normalised(derived);
        }

        /** The pointer roots alone - what the free core's own pass walks. */
        public static Roots pointers(List<String> pointerRoots) {
            return new Roots(pointerRoots, List.of(), List.of(), List.of());
        }

        List<String> of(WalkConsumer.Family family) {
            return switch (family) {
                case POINTERS -> pointers;
                case INVENTORY -> inventory;
                case BLOBS -> blobs;
                case DERIVED -> derived;
            };
        }

        private static List<String> normalised(List<String> roots) {
            List<String> sorted = (roots == null ? List.<String>of() : roots).stream().distinct().sorted().toList();
            for (String root : sorted) {
                if (root == null || root.isBlank() || root.equals("gc") || root.equals("walks")) {
                    throw new IllegalArgumentException("not a family root: " + root);
                }
            }
            return sorted;
        }
    }

    /**
     * The explicit seam: join the shared rebuild pass reusing a {@link Publication} already constructed over the same
     * store rather than making a second, so the withheld screen over the {@code publish/} namespace runs the caller's
     * interceptor chain (the core's {@code ServiceLoader}-discovered chain is empty; a test or an embedder
     * injects one here) - the same seam {@code PublishedAssets} exposes for the same reason.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Publication publication,
                                         List<String> pointerRoots, List<WalkConsumer> consumers) throws IOException {
        return run(walk, store, publication, Roots.pointers(pointerRoots), consumers);
    }

    /** As {@link #run(ArtifactWalk, ArtifactStore, Publication, Roots, List)} over a {@link Publication} of the
     *  store's own - the core's empty interceptor chain screens the pointers. */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Roots roots,
                                         List<WalkConsumer> consumers) throws IOException {
        return run(walk, store, new Publication(store), roots, consumers);
    }

    /**
     * Join the shared pass over every family of {@code roots} that one of {@code consumers} listens on - each
     * enumerated once, in one generation, under one cursor set - and hand every member to the consumers listening
     * on its family (clause 13 of the consumer contract), withheld pointers to those that asked (clause 14).
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Publication publication,
                                         Roots roots, List<WalkConsumer> consumers) throws IOException {
        return run(walk, store, publication, roots, consumers, CONSUMER);
    }

    /**
     * As {@link #run(ArtifactWalk, ArtifactStore, Publication, Roots, List)} under the pass scope {@code scope}
     * ({@code walks/<scope>/...}): a deployment that schedules several walks, each with its own consumers, gives
     * each its own scope, so two walks never join one another's generation and deliver to the wrong consumers.
     */
    public static Optional<WalkPass> run(ArtifactWalk walk, ArtifactStore store, Publication publication,
                                         Roots roots, List<WalkConsumer> consumers, String scope) throws IOException {
        if (consumers.isEmpty()) {
            return Optional.empty();
        }
        Map<WalkConsumer.Family, List<WalkConsumer>> listening = new EnumMap<>(WalkConsumer.Family.class);
        for (WalkConsumer consumer : consumers) {
            for (WalkConsumer.Family family : consumer.families()) {
                listening.computeIfAbsent(family, _ -> new ArrayList<>()).add(consumer);
            }
        }
        Map<String, WalkConsumer.Family> familyByRoot = new TreeMap<>();
        for (WalkConsumer.Family family : listening.keySet()) {
            for (String root : roots.of(family)) {
                familyByRoot.put(root, family);
            }
        }
        if (familyByRoot.isEmpty()) {
            throw new IllegalArgumentException("no root to walk: the consumers listen on " + listening.keySet()
                    + " and the deployment names no root for any of them");
        }
        Delivery delivery = new Delivery(walk, store, publication, List.copyOf(consumers), listening, familyByRoot,
                scope);
        WalkPass pass = walk.walk(store, scope, List.copyOf(familyByRoot.keySet()), delivery);
        if (pass.complete()) {
            delivery.started(pass);
            for (WalkConsumer consumer : consumers) {
                // Narrower than the other three deliberately: onPassCompleted declares no IOException, so a
                // runtime failure is the only shape there is to contain here.
                delivery.attributed(consumer, null, delivered -> delivered.onPassCompleted(pass, store));
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
        private final Map<WalkConsumer.Family, List<WalkConsumer>> listening;
        private final Map<String, WalkConsumer.Family> familyByRoot;
        private final String scope;
        /** The consumers that failed in this generation on this worker: delivered nothing more, recorded durably. */
        private final Set<WalkConsumer> dropped = new HashSet<>();
        private long generation = -1L;
        private boolean started;

        private Delivery(ArtifactWalk walk, ArtifactStore store, Publication publication,
                         List<WalkConsumer> consumers, Map<WalkConsumer.Family, List<WalkConsumer>> listening,
                         Map<String, WalkConsumer.Family> familyByRoot, String scope) {
            this.walk = walk;
            this.store = store;
            this.names = new ServableNames(store, publication);
            this.consumers = consumers;
            this.listening = listening;
            this.familyByRoot = familyByRoot;
            this.scope = scope;
        }

        /** The family a walked key belongs to, by the longest root that prefixes it. */
        private WalkConsumer.Family familyOf(String key) {
            WalkConsumer.Family family = null;
            int longest = -1;
            for (Map.Entry<String, WalkConsumer.Family> root : familyByRoot.entrySet()) {
                String prefix = root.getKey();
                if ((key.equals(prefix) || key.startsWith(prefix + "/")) && prefix.length() > longest) {
                    family = root.getValue();
                    longest = prefix.length();
                }
            }
            return family;
        }

        private void started(WalkPass pass) throws IOException {
            if (started) {
                return;
            }
            started = true;
            generation = pass.generation();
            for (WalkConsumer consumer : consumers) {
                // A failure recorded for an earlier generation is over: this generation reaches the consumer whole.
                String marker = failedSpace(scope) + "/" + consumer.name();
                Optional<ArtifactStore.Versioned> recorded = store.readVersioned(marker);
                if (recorded.isPresent()
                        && Failed.decode(consumer.name(), recorded.get().content()).generation() < generation) {
                    store.delete(marker);
                }
                attributed(consumer, null, delivered -> delivered.onPassStarted(pass, store));
            }
        }

        /**
         * Run {@code handoff} for {@code consumer}; if it fails, record whose failure it was, durably, and deliver
         * that consumer nothing more in this generation.
         *
         * <p><b>This contains per consumer, and the cursor is why it may.</b> The cursor is <em>shared</em> - one
         * walk, one committed position, N consumers - so a consumer that missed a delivery can never be handed it
         * again in this generation; the pass used to propagate the failure for that reason, holding the cursor for
         * everyone at the price of every consumer's rebuild waiting on the one that broke. What makes containment
         * honest is that a consumer's generation is a whole or nothing: the failure is written under
         * {@link #FAILED_SPACE} with the generation and the key before the pass moves on, the consumer is dropped
         * for the rest of the generation so its projection is never half of one, the task that drove the pass
         * reports it failed, and the next generation - a full pass - redelivers everything to it. A failure of the
         * walk itself is not a consumer's and still propagates.
         *
         * <p>The record names the consumer and carries the failure's own text; the exception is not rethrown, so
         * the marker is the operator's surface. An {@link Error} is not contained: it is the runtime giving way, not
         * a consumer failing.
         */
        private void attributed(WalkConsumer consumer, String key, Handoff handoff) throws IOException {
            if (dropped.contains(consumer)) {
                return;
            }
            try {
                handoff.to(consumer);
            } catch (IOException | RuntimeException failure) {
                dropped.add(consumer);
                store.write(failedSpace(scope) + "/" + consumer.name(),
                        new ByteArrayInputStream(new Failed(consumer.name(), generation, key, failure.toString())
                                .encoded()));
            }
        }

        /** One consumer hook, so {@link #attributed} covers all four fan-outs rather than one. */
        @FunctionalInterface
        private interface Handoff {
            void to(WalkConsumer consumer) throws IOException;
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
                attributed(consumer, cursor, delivered -> delivered.beforeCheckpoint(cursor));
            }
        }

        @Override
        public void visit(String key) throws IOException {
            visit(key, store.size(key));
        }

        /** The size the listing already carried: a HEAD per object was the walk's largest single cost over an object
         *  store, paid for every key to decide whether it was small enough to be a pointer. */
        @Override
        public void visit(ArtifactStore.Listed entry) throws IOException {
            visit(entry.key(), entry.size().isPresent() ? entry.size().getAsLong() : store.size(entry.key()));
        }

        private void visit(String key, long size) throws IOException {
            WalkConsumer.Family family = familyOf(key);
            if (family == null) {
                return;   // a key under no root of this pass - the walk's own bookkeeping never is, but say so cheaply
            }
            if (family != WalkConsumer.Family.POINTERS) {
                if (!started) {
                    started(walk.pass(store, scope)
                            .orElseThrow(() -> new IOException("no rebuild pass to deliver under")));
                }
                WalkConsumer.Walked entry = new WalkConsumer.Walked(family, key, size, store);
                for (WalkConsumer consumer : listening.get(family)) {
                    attributed(consumer, key, delivered -> delivered.onWalked(entry, store));
                }
                return;
            }
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
            // Under publish/ the whole withhold model applies; under any other root the content-addressed marker
            // alone does - a hash withheld is withheld wherever it is served, whatever the layout that names it.
            boolean held = key.startsWith("publish/") ? withheld(path, named) : Withheld.is(store, named);
            if (!started) {
                started(walk.pass(store, scope)
                        .orElseThrow(() -> new IOException("no rebuild pass to deliver under")));
            }
            ArtifactDescriptor artifact = new ArtifactDescriptor(null, null, null, path, null, false, named,
                    store.size("blobs/" + named));
            for (WalkConsumer consumer : listening.get(WalkConsumer.Family.POINTERS)) {
                if (held) {
                    // Withheld from serving - a GET would 404 it, so a rebuild of a served view must not reinstate
                    // it into an index; a consumer that must be complete over what is stored asked, and gets it.
                    if (consumer.seesWithheld()) {
                        attributed(consumer, key, delivered -> delivered.onWithheld(artifact, store));
                    }
                } else {
                    attributed(consumer, key, delivered -> delivered.onRetained(artifact, store));
                }
            }
        }

        /** Whether a pointer is withheld from serving - the interceptor chain's hold on the path, the content-addressed
         *  marker under {@code withheld/} for its hash, or the quarantine path itself. Two point reads, where the
         *  servability probe this used to go through read the pointer again and stat the blob as well: a rebuild has
         *  already read the pointer and has the hash in hand, and a withheld-and-reclaimed pointer reads WITHHELD by
         *  its marker alone. */
        private boolean withheld(String requestPath, String hash) throws IOException {
            if (requestPath.equals("/quarantine") || requestPath.startsWith("/quarantine/")) {
                return true;
            }
            return names.heldByChain(requestPath) || Withheld.is(store, hash);
        }
    }
}
