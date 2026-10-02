package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.Trees;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;

/**
 * The reference {@link ArtifactWalk} over the store's own key layout: a depth-first descent visiting siblings in
 * lexicographic order, consuming the store only through {@link ArtifactStore#page}, so a flat namespace of millions is
 * paged rather than buffered and a deep resume is a seek on a backend that pages natively. A key is a leaf where an
 * object is stored ({@link ArtifactStore#exists}); a name with children is a container. The store's layouts never make
 * one key both.
 *
 * <p>The descent's total order is <em>path order</em>: {@code '/'} compares below every other character, so a subtree
 * ({@code app/...}) sits wholly before a longer sibling it prefixes ({@code app.txt}) - plain string order would
 * interleave them and a cursor at {@code app/nested} would skip {@code app.txt}. Every cursor and range comparison goes
 * through {@link #order}.
 *
 * <p>Pass state is durable in the walked store alone. The manifest ({@code walks/<consumer>/manifest}) holds the
 * generation, roots and static segment plan; create-if-absent is the coordinator election, so no leader persists. Each
 * segment ({@code walks/<consumer>/segments/<nn>}) is one compare-and-set object embedding its claim (state, holder,
 * expiry, cursor): a claim is a CAS over pending or expired (never a live holder's), every checkpoint renews the lease
 * in the same write, and a lost commit means the claim was reclaimed, so the worker stops. A taken-over segment resumes
 * from its last committed cursor, so node death costs at most one checkpoint stride.
 *
 * <p>The plan is static per pass: each root's children are paged up to a planning cap and packed into contiguous ranges
 * toward {@code jenrepo.walk.segments}; an over-cap root whose sampled children are all long lowercase hex
 * ({@code blobs/}) is cut by leading hex byte without listing, and any other over-cap root stays one segment. Mid-pass
 * splitting would need a two-object CAS the store does not have.
 *
 * <p>What a walk sees is recorded node-wide ({@code WalkRecord}) and reported by {@code ArtifactWalkObservability},
 * since a walk is resolved wherever one is asked for and an instance's figures would restart with each resolve.
 */
public final class StoreArtifactWalk implements ArtifactWalk {

    private final int checkpoint;
    private final int segments;
    private final Duration ttl;
    private final Clock clock;
    /** This instance's identity in segment claims; each {@link #walk} call suffixes a worker counter. */
    private final String node = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong workers = new AtomicLong();

    public StoreArtifactWalk(int checkpoint, int segments, Duration ttl, Clock clock) {
        if (checkpoint < 1 || segments < 1 || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("checkpoint and segments must be positive and ttl non-zero");
        }
        this.checkpoint = checkpoint;
        this.segments = segments;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public WalkPass walk(ArtifactStore store, String consumer, List<String> roots, KeyVisitor visitor)
            throws IOException {
        String scope = ArtifactStore.segment(consumer);
        String holder = node + "/" + workers.incrementAndGet();
        Manifest manifest = manifest(store, scope, roots);
        // Recorded from the moment the pass is joined, so a running pass is seen RUNNING rather than only once
        // finished.
        WalkRecord.observed(pass(store, scope, manifest));
        while (true) {
            Claimed claimed = claim(store, scope, manifest, holder);
            if (claimed == null) {
                WalkPass pass = finish(store, scope, manifest);
                WalkRecord.observed(pass);
                return pass;
            }
            new Worker(store, scope, manifest, claimed, holder, visitor).run();
        }
    }

    @Override
    public Optional<WalkPass> pass(ArtifactStore store, String consumer) throws IOException {
        String scope = ArtifactStore.segment(consumer);
        Manifest manifest = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
        return manifest == null ? Optional.empty() : Optional.of(pass(store, scope, manifest));
    }

    /** The manifest carries the generation, so no segment need be read to learn it. */
    @Override
    public Optional<Long> generation(ArtifactStore store, String consumer) throws IOException {
        Manifest manifest = parseManifest(
                store.readVersioned(manifestKey(ArtifactStore.segment(consumer))).orElse(null));
        return manifest == null ? Optional.empty() : Optional.of(manifest.generation());
    }

    @Override
    public List<WalkSegment> segments(ArtifactStore store, String consumer) throws IOException {
        String scope = ArtifactStore.segment(consumer);
        Manifest manifest = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
        if (manifest == null) {
            return List.of();
        }
        List<WalkSegment> result = new ArrayList<>();
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Range range = manifest.ranges().get(index);
            Segment segment = parseSegment(store.readVersioned(segmentKey(scope, index)).orElse(null));
            if (segment == null || segment.generation() != manifest.generation()) {
                // Never started this pass, or a leftover of an earlier one: pending from the plan.
                result.add(new WalkSegment(manifest.generation(), index, range.root(), range.from(), range.to(),
                        WalkSegment.State.PENDING, null, null, null));
            } else {
                result.add(new WalkSegment(segment.generation(), index, range.root(), range.from(), range.to(),
                        segment.state(), segment.holder(), segment.expiry(), segment.cursor()));
            }
        }
        return result;
    }

    // ---- the pass manifest

    /** The static plan and claim state of one pass, parsed from the store object that is its only copy. */
    private record Manifest(long generation, Instant started, List<String> roots, List<Range> ranges,
                            boolean complete) {
    }

    /** A half-open key slice {@code [from, to)} under one root; {@code null} bounds run to the root's edges. */
    private record Range(String root, String from, String to) {
    }

    /** Read the current manifest, starting a fresh pass - create-if-absent or complete-then-increment, both CAS, the
     *  coordinator election - when none runs. A lost race re-reads and joins the winner's pass. */
    private Manifest manifest(ArtifactStore store, String scope, List<String> roots) throws IOException {
        String key = manifestKey(scope);
        while (true) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Manifest manifest = parseManifest(current.orElse(null));
            if (manifest != null && !manifest.complete()) {
                return manifest;
            }
            // A corrupt manifest parses null but holds the CAS slot: base the new generation on the clock so stale
            // segments, whose generation is unknowable, can never pass as current.
            long generation = manifest != null ? manifest.generation() + 1
                    : current.isPresent() ? Math.max(1, clock.millis()) : 1;
            List<String> ordered = roots.stream().distinct().sorted().toList();
            Manifest fresh = new Manifest(generation, clock.instant(), ordered, plan(store, ordered), false);
            if (store.writeVersioned(key, Documents.bytes(serialize(fresh)),
                    current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return fresh;
            }
        }
    }

    /** Cut each root into contiguous key ranges toward the segment target, split evenly across roots. */
    private List<Range> plan(ArtifactStore store, List<String> roots) {
        int target = Math.max(1, segments / Math.max(1, roots.size()));
        int cap = Math.max(64, 4 * target);
        List<Range> ranges = new ArrayList<>();
        for (String root : roots) {
            List<String> children = new ArrayList<>();
            store.page(root, "", cap, children::add);
            if (children.size() >= cap) {
                if (children.stream().allMatch(StoreArtifactWalk::hex)) {
                    // The flat content-addressed namespace: cut by leading hex byte, uniform by construction, without
                    // listing it.
                    List<String> cuts = new ArrayList<>();
                    for (int value = 0; value < 256; value++) {
                        cuts.add(root + "/" + String.format(Locale.ROOT, "%02x", value));
                    }
                    pack(root, cuts, target, ranges);
                } else {
                    // Over-cap fan-out with no uniform naming to cut by: one segment, since a static plan cannot
                    // balance what it cannot enumerate cheaply.
                    ranges.add(new Range(root, null, null));
                }
            } else if (children.isEmpty() || children.size() >= target) {
                pack(root, keys(root, children), target, ranges);
            } else {
                // Too few children to meet the target: cut at grandchild boundaries.
                List<String> cuts = new ArrayList<>();
                for (String child : children) {
                    List<String> grand = new ArrayList<>();
                    store.page(root + "/" + child, "", cap, grand::add);
                    if (grand.isEmpty()) {
                        cuts.add(root + "/" + child);
                    } else {
                        cuts.addAll(keys(root + "/" + child, grand));
                    }
                }
                pack(root, cuts, target, ranges);
            }
        }
        return List.copyOf(ranges);
    }

    private static List<String> keys(String prefix, List<String> names) {
        return names.stream().map(name -> prefix + "/" + name).toList();
    }

    /** Pack sorted cut candidates into at most {@code target} contiguous ranges under {@code root}. */
    private static void pack(String root, List<String> cuts, int target, List<Range> ranges) {
        int count = cuts.isEmpty() ? 1 : Math.min(target, cuts.size());
        String from = null;
        for (int index = 1; index < count; index++) {
            String to = cuts.get(index * cuts.size() / count);
            ranges.add(new Range(root, from, to));
            from = to;
        }
        ranges.add(new Range(root, from, null));
    }

    private static boolean hex(String name) {
        if (name.length() < 32) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    // ---- segment claims

    /** A parsed segment-state object; {@code null} fields where it recorded none. */
    private record Segment(long generation, WalkSegment.State state, String holder, Instant expiry, String cursor) {
    }

    /** A won claim: the segment, the cursor to resume from, and the claiming write's CAS token. */
    private record Claimed(int index, Range range, String cursor, Object token) {
    }

    /** Scan the plan in order and CAS-claim the first pending, expired or stale-generation segment; {@code null} when
     *  nothing is claimable. A lost CAS moves on. */
    private Claimed claim(ArtifactStore store, String scope, Manifest manifest, String holder) throws IOException {
        for (int index = 0; index < manifest.ranges().size(); index++) {
            String key = segmentKey(scope, index);
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Segment segment = parseSegment(current.orElse(null));
            if (segment != null && segment.generation() > manifest.generation()) {
                // A newer generation's segment is a live claim of the pass that superseded ours: never stolen, which
                // would reset a live holder's cursor and ping-pong the passes. Our pass is finished; claim() runs dry,
                // walk() returns through finish(), and the next call reads the current manifest.
                continue;
            }
            boolean stale = segment == null || segment.generation() != manifest.generation();
            Instant now = clock.instant();
            if (!stale && (segment.state() == WalkSegment.State.DONE
                    || segment.state() == WalkSegment.State.CLAIMED && segment.expiry() != null
                            && segment.expiry().isAfter(now))) {
                continue;
            }
            String cursor = stale ? null : segment.cursor();
            // A same-generation CLAIMED segment here is an expired holder's (live ones were skipped): a takeover
            // resuming from its cursor, counted in jenrepo.walk.resumes. A pending or stale segment is a fresh claim.
            boolean takeover = !stale && segment.state() == WalkSegment.State.CLAIMED;
            byte[] content = Documents.bytes(serialize(manifest.generation(), index, manifest.ranges().get(index),
                    WalkSegment.State.CLAIMED, holder, now.plus(ttl), cursor));
            if (!store.writeVersioned(key, content, current.map(ArtifactStore.Versioned::token).orElse(null))) {
                continue; // another worker won this segment between the read and the write
            }
            Optional<ArtifactStore.Versioned> won = store.readVersioned(key);
            Segment ours = parseSegment(won.orElse(null));
            if (ours == null || !holder.equals(ours.holder())) {
                continue; // taken over before the token read - treat as a lost race
            }
            if (takeover) {
                WalkRecord.resumed();
            }
            return new Claimed(index, manifest.ranges().get(index), cursor, won.get().token());
        }
        return null;
    }

    /** Count the pass's finished segments and, when all are done, CAS-flip the manifest to complete; finishers may race
     *  and the idempotent flip has one winner. */
    private WalkPass finish(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        if (done(store, scope, manifest) == manifest.ranges().size()) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(manifestKey(scope));
            Manifest latest = parseManifest(current.orElse(null));
            if (latest != null && latest.generation() == manifest.generation() && !latest.complete()) {
                Manifest complete = new Manifest(latest.generation(), latest.started(), latest.roots(),
                        latest.ranges(), true);
                store.writeVersioned(manifestKey(scope), Documents.bytes(serialize(complete)), current.get().token());
            }
            Manifest flipped = parseManifest(store.readVersioned(manifestKey(scope)).orElse(null));
            return pass(store, scope, flipped != null ? flipped : manifest);
        }
        return pass(store, scope, manifest);
    }

    private int done(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        int done = 0;
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Segment segment = parseSegment(store.readVersioned(segmentKey(scope, index)).orElse(null));
            if (segment != null && segment.generation() == manifest.generation()
                    && segment.state() == WalkSegment.State.DONE) {
                done++;
            }
        }
        return done;
    }

    private WalkPass pass(ArtifactStore store, String scope, Manifest manifest) throws IOException {
        return new WalkPass(manifest.generation(), manifest.started(), manifest.roots(), manifest.ranges().size(),
                done(store, scope, manifest), manifest.complete() ? WalkPass.Status.COMPLETE : WalkPass.Status.ACTIVE);
    }

    // ---- walking one claimed segment

    /** The renewal CAS lost: the claim expired and another worker took the segment - stop, never steal it back. */
    private static final class ClaimLost extends IOException {
        ClaimLost() {
            super("The segment claim expired and was taken over");
        }
    }

    /** One worker executing one claimed segment: the ordered descent, the bounds arithmetic, and the checkpoint commit
     *  that renews the lease. */
    private final class Worker {

        private final ArtifactStore store;
        private final String key;
        private final long generation;
        private final int index;
        private final Range range;
        private final String holder;
        private final String from;
        private final String to;
        private final String resume;
        private final KeyVisitor visitor;
        private Object token;
        private String cursor;
        private long count;

        private Worker(ArtifactStore store, String scope, Manifest manifest, Claimed claimed, String holder,
                       KeyVisitor visitor) {
            this.store = store;
            this.key = segmentKey(scope, claimed.index());
            this.generation = manifest.generation();
            this.index = claimed.index();
            this.range = claimed.range();
            this.holder = holder;
            this.from = claimed.range().from();
            this.to = claimed.range().to();
            this.resume = claimed.cursor();
            this.visitor = visitor;
            this.token = claimed.token();
            this.cursor = claimed.cursor();
        }

        /** Walk the range from its cursor. A lost renewal stops quietly, the new holder finishing the segment; a
         *  visitor failure propagates, the claim left to expire and resume from the last committed cursor. */
        private void run() throws IOException {
            try {
                // The ordered descent is the shared Trees.descend: this walk steers it by range (seek to the start,
                // prune and stop at the bounds, emit and checkpoint each in-range leaf), and Trees.descend runs the
                // iterative, paged, path-ordered traversal, so no key depth can overflow the stack. A walk drains every
                // level it enters, so it pages at the drain width: a filesystem scans a whole directory per page.
                Trees.descend(store, range.root(), ArtifactStore.DRAIN_PAGE, Integer.MAX_VALUE, new Trees.Visitor() {
                    @Override
                    public void visit(String leaf) throws IOException {
                        emit(ArtifactStore.Listed.of(leaf));
                    }

                    @Override
                    public void visit(ArtifactStore.Listed leaf) throws IOException {
                        emit(leaf);        // pass the listing's metadata through to the consumer untouched
                    }

                    @Override
                    public boolean emits(String leaf) {
                        return includes(leaf);
                    }

                    @Override
                    public boolean enters(String prefix) {
                        return intersects(prefix);
                    }

                    @Override
                    public String seek() {
                        return lower();
                    }

                    @Override
                    public String ceiling() {
                        return to;
                    }
                });
                commit(WalkSegment.State.DONE);
            } catch (ClaimLost _) {
                // A lost renewal, or a lost CAS on the terminal DONE commit, means the claim was reclaimed: the new
                // holder finishes, so stop quietly. A segment shorter than one stride tests its lease only at that DONE
                // commit.
            }
        }

        /** Deliver one in-range leaf to the {@link KeyVisitor}, advance the cursor, and commit (renewing the lease)
         *  every {@code checkpoint} keys - the callback {@link Trees#descend} drives per leaf. */
        private void emit(ArtifactStore.Listed entry) throws IOException {
            String key = entry.key();
            visitor.visit(entry);
            cursor = key;
            if (++count % checkpoint == 0) {
                commit(WalkSegment.State.CLAIMED);
            }
        }

        /** Commit cursor and state, renewing the lease in the same write; a lost CAS proves the claim was reclaimed.
         *  The visitor flushes first ({@link KeyVisitor#beforeCheckpoint}), so a committed cursor never runs ahead of a
         *  consumer's buffered write - a failed flush leaves the previous cursor and the re-visit replays it. */
        private void commit(WalkSegment.State state) throws IOException {
            visitor.beforeCheckpoint(cursor);
            byte[] content = Documents.bytes(serialize(generation, index, range, state, holder, clock.instant().plus(ttl),
                    cursor));
            if (!store.writeVersioned(key, content, token)) {
                throw new ClaimLost();
            }
            // Re-read for the next CAS's token and confirm the object is still ours: a takeover between the write and
            // this read must not hand us the new holder's token, or the next commit would steal the segment back.
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Segment ours = parseSegment(current.orElse(null));
            if (ours == null || !holder.equals(ours.holder())) {
                throw new ClaimLost();
            }
            token = current.get().token();
        }

        /** Whether a leaf is in the range and past the resume cursor ({@code from} inclusive, {@code to} and the cursor
         *  exclusive). */
        private boolean includes(String key) {
            return (from == null || order(key, from) >= 0)
                    && (to == null || order(key, to) < 0)
                    && (resume == null || order(key, resume) > 0);
        }

        /** Whether any key under {@code prefix/} can still fall in the range and past the cursor. In path order every
         *  such key sorts at or above {@code prefix + "/"} and below {@code prefix + "0"} ({@code '0'} follows
         *  {@code '/'}), bounding the subtree. */
        private boolean intersects(String prefix) {
            String floor = prefix + "/";
            String ceiling = prefix + "0";
            return (to == null || order(floor, to) < 0)
                    && (from == null || order(ceiling, from) > 0)
                    && (resume == null || order(ceiling, resume) > 0);
        }

        /** The seek target in this segment: the resume cursor when past the range start, else the range start;
         *  {@code null} from the beginning. */
        private String lower() {
            if (resume != null) {
                return from == null || order(resume, from) >= 0 ? resume : from;
            }
            return from;
        }
    }

    /** The walk's total key order - path order, {@link Trees#order the shared descent order} - which the range bounds
     *  compare under, consistent with the visit sequence {@link Trees#descend} produces. */
    static int order(String left, String right) {
        return Trees.order(left, right);
    }

    // ---- store object (de)serialisation

    private static String manifestKey(String scope) {
        return "walks/" + scope + "/manifest";
    }

    private static String segmentKey(String scope, int index) {
        return "walks/" + scope + "/segments/" + String.format(Locale.ROOT, "%03d", index);
    }

    private Properties serialize(Manifest manifest) {
        Properties properties = new Properties();
        properties.setProperty("generation", Long.toString(manifest.generation()));
        properties.setProperty("started", manifest.started().toString());
        properties.setProperty("status", manifest.complete() ? "complete" : "active");
        for (int index = 0; index < manifest.roots().size(); index++) {
            properties.setProperty("root." + index, manifest.roots().get(index));
        }
        properties.setProperty("segments", Integer.toString(manifest.ranges().size()));
        for (int index = 0; index < manifest.ranges().size(); index++) {
            Range range = manifest.ranges().get(index);
            properties.setProperty("segment." + index + ".root", range.root());
            if (range.from() != null) {
                properties.setProperty("segment." + index + ".from", range.from());
            }
            if (range.to() != null) {
                properties.setProperty("segment." + index + ".to", range.to());
            }
        }
        return properties;
    }

    /** Parse a manifest; {@code null} for an absent or unparseable one, which a fresh pass replaces by CAS on its
     *  token, so corruption is never fatal. */
    private static Manifest parseManifest(ArtifactStore.Versioned versioned) {
        if (versioned == null) {
            return null;
        }
        try {
            Properties properties = properties(versioned.content());
            long generation = Long.parseLong(properties.getProperty("generation"));
            Instant started = Instant.parse(properties.getProperty("started"));
            boolean complete = "complete".equals(properties.getProperty("status"));
            List<String> roots = new ArrayList<>();
            for (int index = 0; properties.getProperty("root." + index) != null; index++) {
                roots.add(properties.getProperty("root." + index));
            }
            int count = Integer.parseInt(properties.getProperty("segments"));
            List<Range> ranges = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String root = properties.getProperty("segment." + index + ".root");
                if (root == null) {
                    return null;
                }
                ranges.add(new Range(root,
                        properties.getProperty("segment." + index + ".from"),
                        properties.getProperty("segment." + index + ".to")));
            }
            return new Manifest(generation, started, List.copyOf(roots), List.copyOf(ranges), complete);
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private Properties serialize(long generation, int index, Range range, WalkSegment.State state, String holder,
                                 Instant expiry, String cursor) {
        Properties properties = new Properties();
        properties.setProperty("generation", Long.toString(generation));
        properties.setProperty("index", Integer.toString(index));
        properties.setProperty("root", range.root());
        if (range.from() != null) {
            properties.setProperty("from", range.from());
        }
        if (range.to() != null) {
            properties.setProperty("to", range.to());
        }
        properties.setProperty("state", state.name().toLowerCase(Locale.ROOT));
        properties.setProperty("holder", holder);
        properties.setProperty("expiry", Long.toString(expiry.toEpochMilli()));
        if (cursor != null) {
            properties.setProperty("cursor", cursor);
        }
        return properties;
    }

    /** Parse a segment-state object; {@code null} for an absent or unparseable one (claimable as if pending). */
    private static Segment parseSegment(ArtifactStore.Versioned versioned) {
        if (versioned == null) {
            return null;
        }
        try {
            Properties properties = properties(versioned.content());
            long generation = Long.parseLong(properties.getProperty("generation"));
            WalkSegment.State state = WalkSegment.State.valueOf(
                    properties.getProperty("state").toUpperCase(Locale.ROOT));
            String holder = properties.getProperty("holder");
            String expiry = properties.getProperty("expiry");
            return new Segment(generation, state, holder,
                    expiry == null ? null : Instant.ofEpochMilli(Long.parseLong(expiry)),
                    properties.getProperty("cursor"));
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static Properties properties(byte[] content) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(content));
        return properties;
    }
}
