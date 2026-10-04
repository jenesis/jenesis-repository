package build.jenesis.repository.feed;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;

/**
 * The durable half of a mirrored feed: one catalogue snapshot and the stamp of when it was fetched, in an already
 * tenant-scoped {@link ArtifactStore} under a namespace this object owns.
 *
 * <p><strong>The snapshot and its stamp are one object.</strong> A {@link Refresh} carries the fetch instant inside the
 * {@link Snapshot}, and the one compare-and-set that publishes a snapshot stamps it, so a reader never sees a fresh
 * catalogue behind a stale timestamp or the reverse.
 *
 * <p><strong>Pointer-last.</strong> The body is written first, at a key derived from its SHA-256, then the pointer
 * moves by compare-and-set. A crash between leaves an unreferenced body and the previous snapshot serving. A snapshot
 * is derived external data, not a served artifact, so no publication interceptor or observer fires for it.
 *
 * <p><strong>The prior-good snapshot is retained.</strong> {@link #defer}, which a failed refresh calls, moves only
 * {@code nextRefreshAt}, so a failing feed keeps serving its last complete catalogue with its true age.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Immutable and safe to share. Concurrent refreshers are arbitrated by the pointer's
 *       compare-and-set: one commit wins a generation, and a loser adopts the winner's stamp.</li>
 *   <li><b>Idempotency / replay.</b> A body key is its content's SHA-256, so an unchanged catalogue writes nothing and
 *       only advances the stamp; a replayed {@link #commit} finds the body present and commits the pointer.</li>
 *   <li><b>Absence sentinel.</b> A namespace never refreshed answers {@link Optional#empty()} from {@link #current()};
 *       a stamp that never completed a fetch carries an empty {@link Refresh#snapshot()}. Neither is {@code null} or an
 *       empty catalogue presented as authoritative.</li>
 *   <li><b>Tenant scoping.</b> The store is already scoped. This class never calls {@link ArtifactStore#scope} or
 *       discovers a store, and writes only under its namespace, validated as a traversal-free prefix.</li>
 *   <li><b>Error visibility.</b> Every write failure propagates, except {@link #prune} of superseded bodies, which is
 *       best-effort and logged: an orphan body can never change what is served.</li>
 *   <li><b>Read purity.</b> {@link #current()}, {@link #open} and {@link #due} render stored state; this class holds no
 *       transport.</li>
 *   <li><b>Staleness.</b> {@link Snapshot#fetchedAt()} is when the catalogue was drawn and
 *       {@link Refresh#nextRefreshAt()} when it should be drawn again; both survive a restart.</li>
 *   <li><b>Durability / delivery.</b> The commit point is the pointer's compare-and-set. Crash windows: before the body
 *       write (nothing changed), between body and pointer (an unreferenced body, pruned by a later commit), after the
 *       pointer (done). The pointer is the source of truth.</li>
 * </ol>
 */
public final class FeedSnapshots {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedSnapshots.class);

    /** The pointer object's name under the namespace: the one compare-and-set object of a feed. */
    private static final String POINTER = "current";

    /** The container the content-addressed snapshot bodies live in. */
    private static final String BODIES = "snapshots";

    /** How many superseded bodies one prune may delete. */
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
     * @param store an already tenant-scoped store; which tenant is the caller's decision
     * @param namespace the key prefix this feed owns, e.g. {@code signals/kev}, validated as traversal-free
     * @param feed the feed's name, for diagnostics and the stamp
     * @param clock the clock every stamp is taken from
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

    /** The current stamp, or empty when never refreshed in this tenant; a pure store read. An unparseable pointer reads
     *  as absent, and the next commit replaces it by compare-and-set. */
    public Optional<Refresh> current() throws IOException {
        return store.readVersioned(pointerKey()).flatMap(versioned -> decode(versioned.content()));
    }

    /** Whether a refresh is due at {@code now} - true when nothing was ever fetched. */
    public boolean due(Instant now) throws IOException {
        return current().map(stamp -> !now.isBefore(stamp.nextRefreshAt())).orElse(true);
    }

    /** Open the committed snapshot body as a stream, or empty when the stamp carries none. The caller closes it. */
    public Optional<InputStream> open(Refresh stamp) throws IOException {
        Objects.requireNonNull(stamp, "stamp");
        if (stamp.snapshot().isEmpty()) {
            return Optional.empty();
        }
        String key = bodyKey(stamp.snapshot().get().digest());
        return store.exists(key) ? Optional.of(store.open(key)) : Optional.empty();
    }

    /**
     * Commit a completed snapshot: write the body, move the pointer, then prune what neither the new nor the previous
     * stamp references. Answers the durably current stamp, which is a concurrent winner's when one committed first
     * (clause 1).
     *
     * @param snapshot the reduced catalogue, already held under {@link FeedClient}'s snapshot cap
     * @param ttl how long the snapshot is good for: {@code nextRefreshAt} is {@code now + ttl}
     */
    public Refresh commit(byte[] snapshot, Duration ttl) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(ttl, "ttl");
        Instant now = clock.instant();
        String digest = Checksums.sha256(snapshot);
        String key = bodyKey(digest);
        if (!store.exists(key)) {
            // The body is durable before anything references it; an identical catalogue writes nothing, the key being
            // its content.
            store.write(key, new ByteArrayInputStream(snapshot));
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey());
        Optional<Refresh> prior = pointer.flatMap(versioned -> decode(versioned.content()));
        Refresh fresh = new Refresh(feed,
                Optional.of(new Snapshot(digest, snapshot.length, now)),
                now.plus(ttl),
                prior.map(Refresh::generation).orElse(0L) + 1);
        if (!store.writeVersioned(pointerKey(), encode(fresh), pointer.map(ArtifactStore.Versioned::token)
                .orElse(null))) {
            // A concurrent refresher committed a snapshot at least as fresh: converge on it rather than overwrite.
            Optional<Refresh> winner = current();
            if (winner.isPresent()) {
                LOGGER.debug("A concurrent {} refresh committed generation {} first; adopting it",
                        feed, winner.get().generation());
                return winner.get();
            }
            throw new IOException("The " + feed + " feed's snapshot pointer changed under the commit and then"
                    + " disappeared; refusing to guess which snapshot is current");
        }
        prune(digest, prior);
        return fresh;
    }

    /** Record a refresh that did not complete: only {@code nextRefreshAt} moves, to {@code now + after}, so the
     *  prior-good catalogue keeps serving with its true age. A feed that never completed a fetch gets a stamp with an
     *  empty snapshot, "tried, nothing yet", which is not an empty catalogue. */
    public Refresh defer(Duration after) throws IOException {
        Objects.requireNonNull(after, "after");
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey());
        Optional<Refresh> prior = pointer.flatMap(versioned -> decode(versioned.content()));
        Refresh deferred = new Refresh(feed,
                prior.flatMap(Refresh::snapshot),
                clock.instant().plus(after),
                prior.map(Refresh::generation).orElse(0L));
        if (!store.writeVersioned(pointerKey(), encode(deferred),
                pointer.map(ArtifactStore.Versioned::token).orElse(null))) {
            // Another refresher wrote meanwhile; a deferral never undoes a commit.
            return current().orElse(deferred);
        }
        return deferred;
    }

    /** Delete every snapshot body except the current and previous ones, bounded and best-effort. */
    private void prune(String current, Optional<Refresh> prior) {
        Set<String> keep = new HashSet<>();
        keep.add(current);
        prior.flatMap(Refresh::snapshot).map(Snapshot::digest).ifPresent(keep::add);
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
            // A failed cleanup must not fail a commit that succeeded (clause 5); the next commit tries again.
            LOGGER.warn("Could not prune superseded {} feed snapshots under {}; they will be collected on the next"
                    + " successful refresh", feed, namespace, e);
        }
    }

    private String pointerKey() {
        return namespace + '/' + POINTER;
    }

    private String bodyKey(String digest) {
        return ArtifactStore.key(namespace + '/' + BODIES + '/' + digest);
    }

    /** The pointer document as {@link Properties}: a fixed handful of fields. */
    private static byte[] encode(Refresh stamp) {
        Properties properties = new Properties();
        properties.setProperty("feed", stamp.feed());
        properties.setProperty("generation", Long.toString(stamp.generation()));
        properties.setProperty("nextRefreshAt", stamp.nextRefreshAt().toString());
        stamp.snapshot().ifPresent(snapshot -> {
            properties.setProperty("digest", snapshot.digest());
            properties.setProperty("bytes", Long.toString(snapshot.bytes()));
            properties.setProperty("fetchedAt", snapshot.fetchedAt().toString());
        });
        try {
            return Documents.bytes(properties);
        } catch (IOException e) {
            throw new IllegalStateException("A feed stamp could not be rendered", e);
        }
    }

    /** Parse a pointer document; empty for an unparseable one, which the next commit replaces by compare-and-set. */
    private static Optional<Refresh> decode(byte[] content) {
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
            return Optional.of(new Refresh(feed, snapshot, Instant.parse(nextRefreshAt),
                    Long.parseLong(properties.getProperty("generation", "0"))));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /** The committed catalogue: its body's digest, its size and when it was drawn, inseparably, so a snapshot without a
     *  staleness stamp cannot be built. */
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

    /** What one feed durably knows in one tenant: the snapshot it serves (empty until a fetch completes), when to try
     *  again, and how many refreshes have landed; one store object, one compare-and-set. */
    public record Refresh(String feed, Optional<Snapshot> snapshot, Instant nextRefreshAt, long generation) {

        public Refresh {
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

        /** Whether a complete refresh has ever landed, which a consumer checks before treating an answer as
         *  authoritative. */
        public boolean loaded() {
            return snapshot.isPresent();
        }

        /** How old the committed snapshot is at {@code now}; empty when nothing was ever fetched. */
        public Optional<Duration> age(Instant now) {
            return snapshot.map(Snapshot::fetchedAt).map(fetchedAt -> Duration.between(fetchedAt, now));
        }

        /** When the committed snapshot was drawn, the staleness a view shows; empty when nothing was fetched. */
        public Optional<Instant> fetchedAt() {
            return snapshot.map(Snapshot::fetchedAt);
        }
    }
}
