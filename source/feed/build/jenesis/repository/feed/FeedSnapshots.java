package build.jenesis.repository.feed;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;
import module org.slf4j;

/**
 * The durable half of a mirrored feed: one catalogue snapshot and the staleness stamp that says when it was fetched,
 * held in an already tenant-scoped {@link ArtifactStore} under one namespace this object owns entirely.
 *
 * <p><strong>The snapshot and its staleness stamp are one object.</strong> A {@link Stamp} carries the fetch instant
 * <em>inside</em> the {@link Snapshot} reference, so "a snapshot with no fetch instant" and "a fetch instant with no
 * snapshot" are unrepresentable, and the single compare-and-set write that publishes a snapshot is the same write
 * that stamps it. A reader can therefore never see a fresh catalogue behind a stale timestamp, or a timestamp
 * promising data that is not there.
 *
 * <p><strong>Pointer-last, so a partial refresh is never visible.</strong> The snapshot body is written first, at a
 * key derived from its own SHA-256, and only then does the pointer move by compare-and-set. A crash between the two
 * leaves an unreferenced body (garbage the next successful commit prunes) and the <em>previous</em> snapshot still
 * serving - never a pointer naming bytes that are not there. This uses the store's own primitives; it is not a
 * second publication pipeline, and a feed snapshot deliberately does not travel the artifact publish path: it is
 * derived external data, not a served artifact, so no publication interceptor or observer fires for it.
 *
 * <p><strong>The prior-good snapshot is retained.</strong> {@link #defer} - what a failed refresh calls - moves only
 * {@code nextRefreshAt}. The snapshot reference and its fetch instant are copied through untouched, so a failing feed
 * keeps serving its last complete catalogue while a reader sees, exactly, how old it is (&sect;10).
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> Instances are immutable and safe to share. Concurrent refreshers are arbitrated by the
 *     pointer's compare-and-set: exactly one commit wins a generation, and a loser adopts the winner's stamp rather
 *     than overwriting it, so two schedulers racing converge instead of alternating.</li>
 * <li><b>Idempotency / replay.</b> A body key is the SHA-256 of its content, so re-committing an unchanged catalogue
 *     re-writes nothing and only advances the stamp. A replayed {@link #commit} after a crash between body and
 *     pointer simply finds the body already present and commits the pointer.</li>
 * <li><b>Absence sentinel.</b> A namespace that was never refreshed answers {@link Optional#empty()} from
 *     {@link #current()}; a stamp that exists but has never completed a fetch carries an empty
 *     {@link Stamp#snapshot()}. Neither is ever {@code null}, and neither is ever an empty catalogue presented as
 *     an authoritative one.</li>
 * <li><b>Tenant scoping.</b> The store handed in is already scoped to its tenant. This class never calls
 *     {@link ArtifactStore#scope}, never discovers a store, and writes only under its own namespace, which is
 *     validated as a traversal-free key prefix at construction.</li>
 * <li><b>Error visibility.</b> Every write failure propagates. The single exception is {@link #prune} of superseded
 *     bodies, which is best-effort and logged: an orphan body wastes space, it can never change what is served, and
 *     failing a completed commit over a failed cleanup would be strictly worse.</li>
 * <li><b>Read purity (&sect;8/&sect;10).</b> {@link #current()}, {@link #open} and {@link #due} render stored state
 *     only. Nothing here reaches the network - this class holds no transport - so a read path built on it stands
 *     when the vendor is down.</li>
 * <li><b>Staleness (&sect;9/&sect;10).</b> {@link Snapshot#fetchedAt()} is the instant the committed catalogue was
 *     drawn, and {@link Stamp#nextRefreshAt()} when it should be drawn again. Both survive a restart, so staleness is
 *     a durable property of the data rather than of the process that happens to be running.</li>
 * <li><b>Durability / delivery (&sect;13).</b> The commit point is the pointer's compare-and-set. Before it, nothing
 *     is visible; after it, the whole snapshot is. The crash windows are: before the body write (nothing changed),
 *     between body and pointer (an unreferenced body, healed by the next prune), and after the pointer (done). The
 *     durable source of truth is the pointer object itself.</li>
 * </ol>
 */
public final class FeedSnapshots {

    private static final Logger LOG = LoggerFactory.getLogger(FeedSnapshots.class);

    /** The pointer object's name under the namespace - the one compare-and-set object of a feed. */
    private static final String POINTER = "current";

    /** The container the content-addressed snapshot bodies live in. */
    private static final String BODIES = "snapshots";

    /** How many superseded bodies one prune pass may delete, so cleanup is bounded like everything else here. */
    private static final int PRUNE_LIMIT = 256;

    private final String feed;
    private final ArtifactStore store;
    private final String namespace;
    private final Clock clock;

    private FeedSnapshots(String feed, ArtifactStore store, String namespace, Clock clock) {
        this.feed = feed;
        this.store = store;
        this.namespace = namespace;
        this.clock = clock;
    }

    /**
     * The snapshots of one feed inside one tenant's store.
     *
     * @param store     an <em>already tenant-scoped</em> store. This class never scopes one itself: which tenant a
     *                  feed's data belongs to is the caller's decision, made where the tenant is known.
     * @param namespace the key prefix this feed owns entirely, e.g. {@code signals/kev}. It is validated as a
     *                  traversal-free storable key, so a feed name that arrived from configuration cannot escape it.
     * @param feed      the feed's name, used in diagnostics and recorded in the stamp.
     * @param clock     the clock every stamp is taken from, injected so a test crosses a refresh interval without
     *                  sleeping.
     */
    public static FeedSnapshots in(ArtifactStore store, String namespace, String feed, Clock clock) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(clock, "clock");
        if (feed == null || feed.isBlank()) {
            throw new IllegalArgumentException("A feed snapshot namespace needs the feed's name");
        }
        if (namespace == null || namespace.isBlank() || namespace.startsWith("/") || namespace.endsWith("/")) {
            throw new IllegalArgumentException("Not a feed snapshot namespace: " + namespace);
        }
        for (String segment : namespace.split("/", -1)) {
            ArtifactStore.segment(segment);
        }
        ArtifactStore.key(namespace + '/' + BODIES + '/' + "0".repeat(64));   // the longest key this namespace mints
        return new FeedSnapshots(feed.strip(), store, namespace, clock);
    }

    /** The feed's name. */
    public String feed() {
        return feed;
    }

    /** The key prefix this feed owns. */
    public String namespace() {
        return namespace;
    }

    /**
     * The current stamp, or empty when this feed has never been refreshed in this tenant - a pure store read that
     * performs no external I/O. An unparseable pointer reads as absent (self-healing: the next commit replaces it by
     * compare-and-set on the token it just read).
     */
    public Optional<Stamp> current() throws IOException {
        return store.readVersioned(pointerKey()).flatMap(versioned -> decode(versioned.content()));
    }

    /** Whether a refresh is due at {@code now} - true when nothing was ever fetched. */
    public boolean due(Instant now) throws IOException {
        return current().map(stamp -> !now.isBefore(stamp.nextRefreshAt())).orElse(true);
    }

    /**
     * Open the committed snapshot body for streaming, or empty when the stamp carries none. The body is handed back
     * as a stream, never as a {@code byte[]}: a mirrored catalogue is parsed incrementally (&sect;1). The caller
     * closes it.
     */
    public Optional<InputStream> open(Stamp stamp) throws IOException {
        Objects.requireNonNull(stamp, "stamp");
        if (stamp.snapshot().isEmpty()) {
            return Optional.empty();
        }
        String key = bodyKey(stamp.snapshot().get().digest());
        return store.exists(key) ? Optional.of(store.open(key)) : Optional.empty();
    }

    /**
     * Commit a completed snapshot: write the body, then move the pointer, then prune what neither the new nor the
     * previous stamp references. The returned stamp is the one that is durably current - which, when a concurrent
     * refresher won the compare-and-set with an at-least-as-fresh snapshot, is <em>its</em> stamp rather than this
     * call's (clause 1).
     *
     * @param snapshot the finished, reduced catalogue this feed serves from. It is a bounded document the caller
     *                 already holds; {@link FeedClient} enforces the policy's snapshot cap before calling.
     * @param ttl      how long the snapshot is good for - {@code nextRefreshAt} is {@code now + ttl}.
     */
    public Stamp commit(byte[] snapshot, Duration ttl) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(ttl, "ttl");
        Instant now = clock.instant();
        String digest = digest(snapshot);
        String key = bodyKey(digest);
        if (!store.exists(key)) {
            // Pointer-last: the body is durable before anything references it, so the pointer never names absent
            // bytes. An identical catalogue re-writes nothing - the key IS the content.
            store.write(key, new ByteArrayInputStream(snapshot));
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey());
        Optional<Stamp> prior = pointer.flatMap(versioned -> decode(versioned.content()));
        Stamp fresh = new Stamp(feed,
                Optional.of(new Snapshot(digest, snapshot.length, now)),
                now.plus(ttl),
                prior.map(Stamp::generation).orElse(0L) + 1);
        if (!store.writeVersioned(pointerKey(), encode(fresh), pointer.map(ArtifactStore.Versioned::token)
                .orElse(null))) {
            // A concurrent refresher committed first. Its snapshot is at least as fresh as this one, so converge on
            // it rather than overwriting: the loser's body is unreferenced and the winner's next prune collects it.
            Optional<Stamp> winner = current();
            if (winner.isPresent()) {
                LOG.debug("A concurrent {} refresh committed generation {} first; adopting it",
                        feed, winner.get().generation());
                return winner.get();
            }
            throw new IOException("The " + feed + " feed's snapshot pointer changed under the commit and then"
                    + " disappeared; refusing to guess which snapshot is current");
        }
        prune(digest, prior);
        return fresh;
    }

    /**
     * Record that a refresh did not complete: only {@code nextRefreshAt} moves, to {@code now + after}. The snapshot
     * reference and its fetch instant are copied through untouched, so the prior-good catalogue keeps serving and its
     * staleness keeps being told truthfully. A feed that has never completed a fetch gets a stamp with an empty
     * snapshot - a durable "tried, nothing yet", which is exactly not the same thing as an empty catalogue.
     */
    public Stamp defer(Duration after) throws IOException {
        Objects.requireNonNull(after, "after");
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey());
        Optional<Stamp> prior = pointer.flatMap(versioned -> decode(versioned.content()));
        Stamp deferred = new Stamp(feed,
                prior.flatMap(Stamp::snapshot),
                clock.instant().plus(after),
                prior.map(Stamp::generation).orElse(0L));
        if (!store.writeVersioned(pointerKey(), encode(deferred),
                pointer.map(ArtifactStore.Versioned::token).orElse(null))) {
            // Another refresher wrote in the meantime - its stamp is at least as good as this deferral, which only
            // ever pushes the next attempt out. Never retry over it: a deferral must not undo a commit.
            return current().orElse(deferred);
        }
        return deferred;
    }

    /** Delete every snapshot body except the current and the previous one - bounded, and best-effort by design. */
    private void prune(String current, Optional<Stamp> prior) {
        Set<String> keep = new HashSet<>();
        keep.add(current);
        prior.flatMap(Stamp::snapshot).map(Snapshot::digest).ifPresent(keep::add);
        try {
            List<String> superseded = new ArrayList<>();
            store.page(namespace + '/' + BODIES, "", PRUNE_LIMIT, name -> {
                if (!keep.contains(name)) {
                    superseded.add(name);
                }
            });
            for (String name : superseded) {
                store.delete(bodyKey(name));
            }
        } catch (IOException | RuntimeException e) {
            // An orphan body wastes space and can never change what is served, so a failed cleanup must not fail a
            // commit that already succeeded (clause 5). The next successful commit tries again.
            LOG.warn("Could not prune superseded {} feed snapshots under {}; they will be collected on the next"
                    + " successful refresh", feed, namespace, e);
        }
    }

    private String pointerKey() {
        return namespace + '/' + POINTER;
    }

    private String bodyKey(String digest) {
        return ArtifactStore.key(namespace + '/' + BODIES + '/' + digest);
    }

    private static String digest(byte[] content) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(content)) {
                hex.append(Character.forDigit(value >> 4 & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * The pointer document, in the same small {@code key=value} form the artifact walk persists its own
     * compare-and-set state in - a fixed handful of fields needs no parser and no JSON dependency in the core.
     */
    private static byte[] encode(Stamp stamp) {
        Properties properties = new Properties();
        properties.setProperty("feed", stamp.feed());
        properties.setProperty("generation", Long.toString(stamp.generation()));
        properties.setProperty("nextRefreshAt", stamp.nextRefreshAt().toString());
        stamp.snapshot().ifPresent(snapshot -> {
            properties.setProperty("digest", snapshot.digest());
            properties.setProperty("bytes", Long.toString(snapshot.bytes()));
            properties.setProperty("fetchedAt", snapshot.fetchedAt().toString());
        });
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            properties.store(out, null);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("A feed stamp could not be rendered", e);
        }
    }

    /** Parse a pointer document; empty for an unparseable one, which the next commit replaces by compare-and-set. */
    private static Optional<Stamp> decode(byte[] content) {
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(content));
            String feed = properties.getProperty("feed");
            String nextRefreshAt = properties.getProperty("nextRefreshAt");
            if (feed == null || nextRefreshAt == null) {
                return Optional.empty();
            }
            String digest = properties.getProperty("digest");
            String fetchedAt = properties.getProperty("fetchedAt");
            Optional<Snapshot> snapshot = digest == null || fetchedAt == null
                    ? Optional.empty()
                    : Optional.of(new Snapshot(digest,
                            Long.parseLong(properties.getProperty("bytes", "0")),
                            Instant.parse(fetchedAt)));
            return Optional.of(new Stamp(feed, snapshot, Instant.parse(nextRefreshAt),
                    Long.parseLong(properties.getProperty("generation", "0"))));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * The committed catalogue: which body (by its content digest), how big it is, and - inseparably - when it was
     * drawn. The fetch instant lives here rather than beside the reference precisely so that a snapshot without a
     * staleness stamp cannot be built.
     */
    public record Snapshot(String digest, long bytes, Instant fetchedAt) {

        public Snapshot {
            Objects.requireNonNull(fetchedAt, "fetchedAt");
            if (digest == null || digest.isBlank()) {
                throw new IllegalArgumentException("A snapshot is named by its content digest");
            }
            if (bytes < 0) {
                throw new IllegalArgumentException("Negative snapshot size: " + bytes);
            }
        }
    }

    /**
     * What one feed durably knows in one tenant: the snapshot it serves (empty until a fetch has completed once),
     * when to try again, and how many complete refreshes have landed. One store object, written by one
     * compare-and-set.
     */
    public record Stamp(String feed, Optional<Snapshot> snapshot, Instant nextRefreshAt, long generation) {

        public Stamp {
            Objects.requireNonNull(feed, "feed");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(nextRefreshAt, "nextRefreshAt");
            if (generation < 0) {
                throw new IllegalArgumentException("Negative snapshot generation: " + generation);
            }
            if (snapshot.isEmpty() != (generation == 0)) {
                throw new IllegalArgumentException(
                        "A stamp carries a snapshot exactly when a refresh has completed: generation " + generation
                                + " / snapshot " + snapshot);
            }
        }

        /** Whether a complete refresh has ever landed - what a consumer checks before treating an answer as authoritative. */
        public boolean loaded() {
            return snapshot.isPresent();
        }

        /** How old the committed snapshot is at {@code now}; empty when nothing was ever fetched. */
        public Optional<Duration> age(Instant now) {
            return snapshot.map(Snapshot::fetchedAt).map(fetchedAt -> Duration.between(fetchedAt, now));
        }

        /** When the committed snapshot was drawn; empty when nothing was ever fetched - the staleness line a view shows. */
        public Optional<Instant> fetchedAt() {
            return snapshot.map(Snapshot::fetchedAt);
        }
    }
}
