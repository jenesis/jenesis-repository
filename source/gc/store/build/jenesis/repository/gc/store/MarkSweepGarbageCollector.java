package build.jenesis.repository.gc.store;

import module java.base;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Condemned;
import build.jenesis.repository.store.Names;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;

/**
 * The reference {@link GarbageCollector}, riding the shared artifact walk, so both enumerations are ordered, resumable,
 * segmented and multi-node-safe and no phase holds the whole store in memory. Both are passes of their own rather than
 * the shared rebuild pass: the mark must see every pointer, withheld ones included, and the sweep walks {@code blobs/},
 * which the rebuild never visits. The mark cannot ride the shared walk either: the lease fence over the walk's shard
 * space would delete a live blob, because a superseding pass advances the manifest before it writes its first shard
 * ({@code MarkSweepTest} holds this).
 *
 * <p><b>Mark, sharded.</b> One walk pass ({@code gc-mark}) over the pointer roots reads each small leaf and keeps every
 * hash it names, buffered only up to the checkpoint stride: the buffer is flushed before every cursor commit
 * ({@code KeyVisitor.beforeCheckpoint}), so a resume never skips a pointer whose reference died with a crashed buffer.
 * Flushed references land as immutable append-only batches {@code gc/<pass>/refs/<hh>/<collector>-<n>}, sharded by the
 * hash's leading byte, so concurrent workers and replays only add duplicates. Once the mark completes, its shards cover
 * every live pointer.
 *
 * <p><b>Condemn, then collect in a later pass.</b> A second pass ({@code gc-sweep}) streams {@code blobs/} in hash
 * order - so one {@code <hh>} shard of references is in memory at a time - and judges each blob: a referenced blob
 * loses any stale {@code gc/condemned/<hash>} marker; an unreferenced one is condemned (marker stamped with this pass)
 * the first time and deleted only when its marker carries an earlier pass. The marker is the clock, giving an in-flight
 * publish a full mark of grace, with a wall-clock floor on top ({@code jenrepo.gc.grace}, two hours unless set) so fast
 * generation turnover cannot shorten it. The marker also arbitrates between the delete and a publish relying on the
 * same bytes ({@link Condemned}): the sweep claims it by compare-and-set over the token it judged by and deletes only
 * once the claim lands, while a dedup re-publish spares the blob by compare-and-set on the same marker - whichever
 * lands first decides, and a publish meeting a claim gets a retryable refusal. Blob first, marker last; the convergence
 * leg removes markers whose blob is gone and the reference shards of superseded passes.
 *
 * <p>Only recognised shapes are acted on: a leaf naming no SHA-256 is skipped, and a {@code blobs/} name that is not a
 * hash is never judged. A pointer body is read through {@link ServableNames#hash(byte[])}, which owns both dialects -
 * bare hex, and an OCI tag pointer's {@code sha256:<hex>}. Every other name the collector judges is a key it writes
 * itself, in bare hex.
 *
 * <p><b>What a pointer body cannot say, its format says.</b> A format serving blobs reachable only through a stored
 * document declares them through {@link BlobReferences#references}: the mark asks the format owning a visited key what
 * else it keeps alive and unions the answer in. The collector parses no format's documents; with no lenders the mark is
 * the pointer-body scan alone.
 *
 * <p>What a collect does is recorded node-wide ({@code CollectionRecord}) and reported by
 * {@code GarbageCollectorObservability}. {@code plan} is a dry run and records nothing.
 */
public final class MarkSweepGarbageCollector implements GarbageCollector {

    /** The two walk consumers, whose pass state a console reads back through {@code ArtifactWalk.pass}. */
    static final String MARK = "gc-mark", SWEEP = "gc-sweep";

    private static final String CONDEMNED = Condemned.SPACE;


    /** A pointer names a hash in a few dozen bytes; a larger leaf is other metadata and is never read whole. */
    private static final int LARGEST_POINTER = 1024;

    private final ArtifactWalk walk;

    /** A wall-clock floor on the condemn-to-collect grace, on top of the one-pass generation gap: two hours in a
     *  deployment unless {@code jenrepo.gc.grace} says otherwise ({@link GarbageCollector#defaultGrace()}), zero for a
     *  collector built without one. It covers generations advancing faster than the collection interval - several nodes
     *  collecting, a node re-collecting after a lease expires - and can only delay a deletion, never bring one
     *  forward. */
    private final Duration graceFloor;

    /** The installed lending formats, paired with the roots each declared, so a visited key is offered only to the
     *  format owning its root and a pointer-only deployment pays one prefix test per leaf. */
    private final List<Lender> lenders;

    /** One format's lending capability under one declared root, so ownership is a single {@code startsWith}. */
    private record Lender(String root, BlobReferences format) {

        private boolean owns(String key) {
            return key.length() > root.length() && key.startsWith(root) && key.charAt(root.length()) == '/';
        }
    }

    /** This collector's identity inside reference-batch names, so concurrent collectors never contend on a key. */
    private final String collector = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong batches = new AtomicLong();

    /** The clock a pass's running time is measured on. A delete is stamped with the collection's instant advanced by
     *  how long the pass has run, so a long sweep's last delete is stamped when it happened. */
    private final Clock clock;

    public MarkSweepGarbageCollector(ArtifactWalk walk) {
        this(walk, Duration.ZERO);
    }

    public MarkSweepGarbageCollector(ArtifactWalk walk, Duration graceFloor) {
        this(walk, graceFloor, List.of());
    }

    /** {@code lenders} are the installed {@link BlobReferences} formats, resolved by the provider so the collector
     *  carries no discovery; an empty list is the pointer-body-only mark. A lender declaring a root the collector owns
     *  or judges ({@code blobs}, {@code gc}, {@code walks}) is refused here: a format lending references under the
     *  namespace being swept is a wiring bug, and ignoring it would leave its blobs unmarked. */
    public MarkSweepGarbageCollector(ArtifactWalk walk, Duration graceFloor, List<BlobReferences> lenders) {
        this(walk, graceFloor, lenders, Clock.systemUTC());
    }

    /** {@code clock} is what a pass's running time is measured on; a deployment's is the system clock. */
    public MarkSweepGarbageCollector(ArtifactWalk walk, Duration graceFloor, List<BlobReferences> lenders,
                                     Clock clock) {
        this.walk = walk;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.graceFloor = graceFloor == null ? Duration.ZERO : graceFloor;
        List<Lender> owners = new ArrayList<>();
        for (BlobReferences lender : lenders == null ? List.<BlobReferences>of() : lenders) {
            for (String root : lender.blobRoots()) {
                owners.add(new Lender(root(root), lender));
            }
        }
        this.lenders = List.copyOf(owners);
    }

    @Override
    public GcPlan plan(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException {
        switch (pointerRoots) {
            case Known.Unknown<List<String>> unknown -> {
                return GcPlan.refused(unknown); // the dry run of a refusal is a refusal, not an empty plan
            }
            case Known.Present<List<String>> present -> roots(present.value());
            case Known.Absent<List<String>> _ -> throw new IllegalArgumentException(NO_ROOTS);
        }
        Optional<WalkPass> mark = walk.pass(store, MARK);
        long judged = mark.isEmpty() ? 0
                : mark.get().complete() ? mark.get().generation()
                : lastCompletedGeneration(store, mark.get().generation());
        if (judged <= 0) {
            return GcPlan.of(false, 0, 0, 0, List.of()); // no completed mark ever ran - nothing is due yet
        }
        References references = new References(store, judged);
        long[] due = {0};
        List<String> sample = new ArrayList<>();
        each(store, CONDEMNED, name -> {
            if (!hash(name) || !store.exists("blobs/" + name) || references.contains(name)) {
                return; // unrecognised, already-collected residue, or re-referenced
            }
            Marker parsed = store.readVersioned(CONDEMNED + "/" + name)
                    .map(MarkSweepGarbageCollector::parse).orElse(null);
            // Mirror collect()'s deletion test exactly, so the dry run previews what the next collect reclaims:
            // condemned at or before the completed mark (an unreadable or newer marker is not due) and past the
            // wall-clock grace floor.
            if (parsed == null || parsed.pass() > judged
                    || Duration.between(parsed.since(), now).compareTo(graceFloor) < 0) {
                return;
            }
            due[0]++;
            if (sample.size() < GcPlan.SAMPLE) {
                sample.add(name);
            }
        });
        return GcPlan.of(true, 0, 0, due[0], sample);
    }

    /** The generation of the most recent mark whose reference shards still stand - the largest {@code gc/<n>} below the
     *  current one, rather than {@code generation - 1}, because a corrupt-manifest recovery re-bases the generation on
     *  the wall clock and {@link References} would otherwise read shards that never existed and preview every condemned
     *  blob as due. Zero when no earlier pass left shards. */
    private static long lastCompletedGeneration(ArtifactStore store, long below) throws IOException {
        long best = 0;
        for (String child : store.list("gc")) {
            long pass;
            try {
                pass = Long.parseLong(child);
            } catch (NumberFormatException _) {
                continue; // the condemned space and anything unrecognised are not pass generations
            }
            if (pass < below && pass > best) {
                best = pass;
            }
        }
        return best;
    }

    @Override
    public GcPlan collect(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException {
        List<String> named;
        switch (pointerRoots) {
            case Known.Unknown<List<String>> unknown -> {
                // The root set could not be named in full, so some namespace's pointers would be invisible to the mark
                // and their blobs would read as unreferenced. Refuse before the mark: nothing walked, condemned or
                // deleted, and the reason travels back with the plan.
                return GcPlan.refused(unknown);
            }
            case Known.Present<List<String>> present -> named = present.value();
            case Known.Absent<List<String>> _ -> throw new IllegalArgumentException(NO_ROOTS);
        }
        Instant started = clock.instant();
        WalkPass marked = walk.walk(store, MARK, markRoots(named), new Mark(store));
        if (!marked.complete()) {
            // Another node still holds mark segments: judging against an incomplete mark could condemn what it missed.
            // Report the partial pass and leave the judging to the next interval or the node that finishes.
            CollectionRecord.collected(now, 0, -1, false);
            return GcPlan.of(false, 0, 0, 0, List.of());
        }
        Sweep sweep = new Sweep(store, marked.generation(), now, started);
        WalkPass swept = walk.walk(store, SWEEP, List.of("blobs"), sweep);
        if (!swept.complete()) {
            CollectionRecord.collected(now, sweep.collected, sweep.standing, false);
            return GcPlan.of(false, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
        }
        converge(store, marked.generation(), now);
        CollectionRecord.collected(now, sweep.collected, sweep.standing, true);
        return GcPlan.of(true, sweep.condemned, sweep.spared, sweep.collected, sweep.sample);
    }


    /** The bookkeeping convergence after a completed sweep: a marker whose blob is gone is removed once the claim
     *  window has passed, and superseded passes' reference shards are dropped, so {@code gc/} converges and a re-run
     *  over a converged store changes nothing. */
    private void converge(ArtifactStore store, long generation, Instant now) throws IOException {
        // A collection is stamped with the collector's clock, a claim with the wall clock it was written at.
        Instant collectedSettled = now.minus(Condemned.CLAIM_EXPIRY);
        Instant claimSettled = Instant.now().minus(Condemned.CLAIM_EXPIRY);
        each(store, CONDEMNED, name -> {
            if (hash(name) && !store.exists("blobs/" + name)) {
                // A marker whose blob is gone stays while a publish may still meet it - an unexpired claim, or a
                // collection inside the claim window. After that it is residue.
                String key = CONDEMNED + "/" + name;
                Optional<ArtifactStore.Versioned> marker = store.readVersioned(key);
                String body = marker.map(held -> new String(held.content(), StandardCharsets.UTF_8)).orElse("");
                Optional<Instant> collected = Condemned.collectedAt(body);
                Optional<Instant> claimed = Condemned.claimed(body);
                boolean inFlight = collected.map(at -> !at.isBefore(collectedSettled)).orElse(false)
                        || claimed.map(at -> !at.isBefore(claimSettled)).orElse(false);
                if (!inFlight) {
                    deleteIfPresent(store, key);
                }
            }
        });
        for (String child : store.list("gc")) {
            long pass;
            try {
                pass = Long.parseLong(child);
            } catch (NumberFormatException _) {
                continue; // the condemned space and anything unrecognised stay
            }
            if (pass < generation) {
                drop(store, "gc/" + child);
            }
        }
    }

    /** Delete a bookkeeping subtree - a superseded pass's reference batches, never artifacts. */
    private static void drop(ArtifactStore store, String prefix) throws IOException {
        if (store.exists(prefix)) {
            store.delete(prefix);
            return;
        }
        for (String child : store.list(prefix)) {
            drop(store, prefix + "/" + child);
        }
    }

    /** {@code publish} always exists, so an answered-but-empty root set ({@link Known.Absent}, or an empty
     *  {@link Known.Present}) is a caller bug and fails loudly, unlike an unanswerable set, which is refused. */
    private static final String NO_ROOTS = "garbage collection needs at least one pointer root, e.g. publish";

    /** Validate the caller's pointer roots: at least one, and never a namespace the collector owns or judges. */
    private static List<String> roots(List<String> pointerRoots) {
        if (pointerRoots == null || pointerRoots.isEmpty()) {
            throw new IllegalArgumentException(NO_ROOTS);
        }
        return pointerRoots.stream().distinct().sorted().map(MarkSweepGarbageCollector::root).toList();
    }

    /** The roots the mark walks: the caller's plus every root an installed lender declared. A caller that forgot a
     *  lender's root would leave that format installed and never asked, and its blobs reclaimed under it; a union can
     *  only mark more, so it never deletes what the caller's list would spare. Each added root passes the same
     *  screen. */
    private List<String> markRoots(List<String> pointerRoots) {
        Set<String> union = new TreeSet<>(roots(pointerRoots));
        for (Lender lender : lenders) {
            union.add(lender.root());
        }
        return List.copyOf(union);
    }

    /** One root, validated: never a namespace the collector itself owns or judges. */
    private static String root(String root) {
        if (root == null || root.isBlank() || root.equals("blobs") || root.equals("gc") || root.equals("walks")) {
            throw new IllegalArgumentException("not a pointer root: " + root);
        }
        return root;
    }

    /** The mark's visitor: buffer every hash a pointer leaf names, flushed as append-only batches before each walk
     *  checkpoint, so no committed cursor lies about an unflushed reference. */
    private final class Mark implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final Map<String, List<String>> buffer = new HashMap<>(); // leading hash byte -> hashes to flush
        private long generation;

        private Mark(ArtifactStore store) {
            this.store = store;
        }

        @Override
        public void visit(String key) throws IOException {
            visit(ArtifactStore.Listed.of(key));
        }

        @Override
        public void visit(ArtifactStore.Listed entry) throws IOException {
            String key = entry.key();
            // Lent references first, outside the pointer-size gate below: that gate bounds what is read as a pointer
            // body, and a format whose references live in a document knows its own bound (BlobReferences clause 6).
            // Asking after the gate would drop the references of every key that is not pointer-shaped, and an unmarked
            // blob is deleted.
            //
            // A lender's IOException is not contained: a short list is illegal (clause 3), so "cannot enumerate" fails
            // the pass, collect() never reaches the sweep, and nothing is deleted. Catching it would read as
            // "references nothing".
            for (Lender lender : lenders) {
                if (!lender.owns(key)) {
                    continue;
                }
                for (String reference : lender.format().references(key, store)) {
                    // The same bare-hex predicate and sharding as the body read, so a lent hash lands where the sweep
                    // looks.
                    String named = ServableNames.hash(reference);
                    if (hash(named)) {
                        buffer.computeIfAbsent(named.substring(0, 2), _ -> new ArrayList<>()).add(named);
                    }
                }
            }
            // The pointer-size gate, answered from the listing's size where the backend carried one (every shipped
            // backend), rather than a round trip per pointer; a listing without one still asks the store.
            long size = entry.size().orElseGet(() -> {
                try {
                    return store.size(key);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (size < 0 || size > LARGEST_POINTER) {
                return;
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                return; // removed between the walk's listing and this read - nothing references through it
            }
            // The body's dialect is read through the seam that owns it: bare hex for publish/ and blobs/ pointers,
            // sha256:<hex> for OCI tag pointers, both naming the same blob - a tag pointer read as bare hex would leave
            // its blob unreferenced and deleted. The judgement below applies to the bare hash, which is how the sweep
            // names a blob and a shard is keyed. A body in neither dialect names no hash.
            String named = ServableNames.hash(pointer.get().content());
            if (hash(named)) {
                buffer.computeIfAbsent(named.substring(0, 2), _ -> new ArrayList<>()).add(named);
            }
        }

        @Override
        public void beforeCheckpoint(String cursor) throws IOException {
            if (buffer.isEmpty()) {
                return;
            }
            if (generation == 0) {
                // The pass this worker contributes to, read lazily. If the manifest turned over meanwhile, references
                // land in the newer pass's shards - a true observation that can only spare a blob.
                generation = walk.pass(store, MARK).map(WalkPass::generation)
                        .orElseThrow(() -> new IOException("no mark pass to record references under"));
            }
            for (Map.Entry<String, List<String>> shard : buffer.entrySet()) {
                byte[] content = String.join("\n", shard.getValue()).getBytes(StandardCharsets.UTF_8);
                for (int attempt = 0; true; attempt++) {
                    String key = "gc/" + generation + "/refs/" + shard.getKey()
                            + "/" + collector + "-" + batches.incrementAndGet();
                    if (store.writeVersioned(key, content, null)) {
                        break; // create-if-absent under a collector-unique name: a collision is one in a billion
                    }
                    if (attempt == 2) {
                        throw new IOException("could not record a reference batch under " + key);
                    }
                }
            }
            buffer.clear();
        }
    }

    /** The sweep phase's visitor: judge each blob, in hash order, against the completed mark's shards. */
    private final class Sweep implements ArtifactWalk.KeyVisitor {

        private final ArtifactStore store;
        private final long generation;
        private final Instant now;
        /** The {@link #clock}'s reading when the pass began, against which {@link #now} advances. */
        private final Instant started;
        private final References references;
        /** The condemned markers of the shard being swept, so the sparing question is answered in memory. */
        private final Markers markers;
        private long condemned, spared, collected;
        /** Blobs this sweep left condemned - newly condemned plus those within their grace - the set the
         *  {@code jenrepo.gc.condemned} gauge reports. */
        private long standing;
        private final List<String> sample = new ArrayList<>();

        private Sweep(ArtifactStore store, long generation, Instant now, Instant started) {
            this.store = store;
            this.generation = generation;
            this.now = now;
            this.started = started;
            this.references = new References(store, generation);
            this.markers = new Markers(store);
        }

        @Override
        public void visit(String key) throws IOException {
            if (!key.startsWith("blobs/")) {
                return;
            }
            String hash = key.substring("blobs/".length());
            if (!hash(hash)) {
                return; // only content-addressed objects are ever judged, let alone deleted
            }
            String marker = CONDEMNED + "/" + hash;
            if (references.contains(hash)) {
                // Asked in memory first: almost every blob is referenced and unmarked, and a store probe per referenced
                // blob would be most of a collection's reads. The markers stream in the blobs' hash order, one shard
                // resident at a time. A stale snapshot can only skip a delete, leaving a marker for the next pass to
                // clear.
                if (markers.condemned(hash) && deleteIfPresent(store, marker)) {
                    spared++; // referenced again - the dedup re-publish an earlier pass condemned
                }
                return;
            }
            Optional<ArtifactStore.Versioned> current = store.readVersioned(marker);
            Marker parsed = current.map(MarkSweepGarbageCollector::parse).orElse(null);
            if (parsed == null) {
                // Unreferenced and not yet (recognisably) condemned: condemn now, never delete in the pass that first
                // judged it. Create-if-absent (an unreadable marker is repaired on its own token); a lost race is a
                // concurrent sweeper's condemnation, which converges.
                var _ = store.writeVersioned(marker, Condemned.condemnation(generation, now),
                        current.map(ArtifactStore.Versioned::token).orElse(null));
                condemned++;
                standing++; // now condemned, awaiting the confirming pass
            } else if (parsed.pass() < generation && Duration.between(parsed.since(), now).compareTo(graceFloor) >= 0
                    && referencesStillStand()) {
                // Condemned by an earlier pass, still unreferenced, past the grace floor, and our shards still stand.
                // Claim the marker over the token we judged by: a re-publish that re-referenced these bytes wrote the
                // same marker, so a claim that does not land spares the blob, and one that lands refuses every publish
                // of these bytes until they are gone. The completed mark's shard needs no re-read: it has only gained
                // duplicates since.
                Instant claiming = Instant.now();
                if (!Condemned.claim(store, hash, current.get(), claiming)) {
                    spared++;
                    return;
                }
                if (Duration.between(claiming, Instant.now()).compareTo(Condemned.CLAIM_EXPIRY.dividedBy(2)) > 0) {
                    // Paused between claim and delete long enough that a publish may take the claim back: re-judge next
                    // pass.
                    standing++;
                    return;
                }
                // The blob goes and the marker stays, rewritten to say so: a publish whose upload was dropped as a
                // duplicate while the blob stood must meet a marker when it asks to spare the bytes, or it would link a
                // pointer at nothing. The convergence leg removes it once no such publish can be in flight, counted
                // from the delete itself.
                deleteIfPresent(store, key);
                Condemned.collected(store, hash, now.plus(Duration.between(started, clock.instant())));
                collected++;
                if (sample.size() < GcPlan.SAMPLE) {
                    sample.add(hash);
                }
            } else {
                // Still within its grace (a pass at or after ours, or younger than the floor): left for the confirming
                // pass.
                standing++;
            }
        }

        /** A lease fence against deleting after this sweep's reference shards were dropped. The shards live under
         *  {@code gc/<generation>/refs} and {@link #converge} drops only shards of a generation below the current mark,
         *  so ours can only vanish once a mark completes at a greater generation; a paused sweep resuming after that
         *  would read every hash as unreferenced. Re-reading the mark's generation before each delete and refusing once
         *  it has advanced closes that. Conservative: it may defer a safe delete while a newer mark is still in flight,
         *  which the next pass reclaims. */
        private boolean referencesStillStand() throws IOException {
            // An empty answer is an unreadable manifest here (a sweep only follows a completed mark), and absence of
            // proof is not proof, so the blob is spared. Only the generation is read, not the segment states, which
            // would cost dozens of reads per delete.
            return walk.generation(store, MARK).map(current -> current <= generation).orElse(false);
        }
    }

    /** The condemned markers, read as the reference shards are: one leading-byte shard resident at a time, in the
     *  sweep's hash order. They answer whether a referenced blob carries an earlier pass's marker - nearly always no -
     *  without a store read per blob. A wholly condemned store is reachable, so the resident set is one shard, and past
     *  {@link #CAP} it stops holding and the caller probes the store per blob instead. */
    private static final class Markers {

        /** Hashes held for one shard before falling back to probing - above a ten-million-blob store wholly
         *  condemned. */
        private static final int CAP = 50_000;

        private final ArtifactStore store;
        private String shard;
        private Set<String> hashes = Set.of();
        private boolean capped;

        private Markers(ArtifactStore store) {
            this.store = store;
        }

        /** Whether {@code hash} may carry a marker: exact when the shard is held, {@code true} (ask the store) when it
         *  was too large. */
        private boolean condemned(String hash) throws IOException {
            String leading = hash.substring(0, 2);
            if (!leading.equals(shard)) {
                shard = leading;
                capped = false;
                hashes = load(leading);
            }
            return capped || hashes.contains(hash);
        }

        private Set<String> load(String leading) throws IOException {
            Set<String> loaded = new HashSet<>();
            String after = leading;
            while (true) {
                List<String> page = new ArrayList<>();
                store.page(CONDEMNED, after, PAGE, page::add);
                if (page.isEmpty()) {
                    return loaded;
                }
                for (String name : page) {
                    if (!name.startsWith(leading)) {
                        return loaded; // past this shard, and the names are ordered
                    }
                    loaded.add(name);
                    if (loaded.size() > CAP) {
                        capped = true;
                        return Set.of();
                    }
                }
                after = page.getLast();
            }
        }
    }

    /** How many marker names one listing asks for. */
    private static final int PAGE = 1_000;

    /** The completed mark's reference shards, loaded one leading-byte shard at a time in the name order both consumers
     *  stream in - at most 256 sequential reads, never an O(N) set. */
    private static final class References {

        private final ArtifactStore store;
        private final long generation;
        private String shard;
        private Set<String> hashes = Set.of();

        private References(ArtifactStore store, long generation) {
            this.store = store;
            this.generation = generation;
        }

        private boolean contains(String hash) throws IOException {
            String leading = hash.substring(0, 2);
            if (!leading.equals(shard)) {
                shard = leading;
                hashes = load(leading);
            }
            return hashes.contains(hash);
        }

        private Set<String> load(String leading) throws IOException {
            Set<String> loaded = new HashSet<>();
            String prefix = "gc/" + generation + "/refs/" + leading;
            for (String batch : store.list(prefix)) {
                Optional<ArtifactStore.Versioned> content = store.readVersioned(prefix + "/" + batch);
                if (content.isPresent()) {
                    for (String line : new String(content.get().content(), StandardCharsets.UTF_8).split("\n")) {
                        if (!line.isBlank()) {
                            loaded.add(line.trim());
                        }
                    }
                }
            }
            return loaded;
        }
    }

    /** A condemned marker's content: the pass whose judgment condemned the blob (the grace clock) and when - the
     *  {@code since} a console shows and the {@code jenrepo.gc.grace} floor measures. */
    private record Marker(long pass, Instant since) {
    }

    /** Parse a marker; {@code null} for an unreadable one, which is re-stamped rather than trusted. */
    private static Marker parse(ArtifactStore.Versioned versioned) {
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(versioned.content()));
            return new Marker(Long.parseLong(properties.getProperty("pass")),
                    Instant.parse(properties.getProperty("since")));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static boolean deleteIfPresent(ArtifactStore store, String key) throws IOException {
        if (!store.exists(key)) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Whether a value is a bare SHA-256 - the only shape the collector trusts as naming a blob. Not widened to
     *  {@code sha256:<hex>}: it also judges marker names, {@code blobs/} names and raw hashes, all keys the collector
     *  writes in bare hex; the one qualified dialect is normalised at the pointer-body read instead. */
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

    private interface NameAction {
        void accept(String name) throws IOException;
    }

    /** Stream every immediate child name under {@code prefix} through {@code action}, paged at the drain width. */
    private static void each(ArtifactStore store, String prefix, NameAction action) throws IOException {
        Names names = Names.over(store, prefix);
        for (String name = names.next(); name != null; name = names.next()) {
            action.accept(name);
        }
    }
}
