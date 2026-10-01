package build.jenesis.repository.staging.store;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.staging.Staging;
import build.jenesis.repository.staging.StagingState;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.store.Names;

/**
 * Store-backed staging and promotion. A deploy lands under a staging id, held so it does not resolve, and the id is
 * later promoted - every held artifact re-published into the release layout, re-pointing the same content-addressed
 * blobs rather than copying - or dropped. State is persisted, so the lifecycle spans requests; all storage goes through
 * the repository's {@link Publication}.
 *
 * <p>The {@code staging-state/<id>} marker carries the state and when it was reached ({@code OPEN <instant>} on the
 * first deploy, {@code PROMOTED <instant>} or {@code DROPPED <instant>} on sealing), so {@link #reap} can age the
 * lifecycle: an abandoned OPEN id past the TTL has its staged artifacts unpublished, and a sealed marker past the TTL
 * is deleted, while a younger marker keeps the sealed transitions rejected. A staged tree with no marker (lost to a
 * partial purge) is stamped on first observation and reaped a TTL later; a marker not shaped {@code <STATE> <instant>}
 * was not written here and is never deleted.
 */
public final class StoreStaging implements Staging {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreStaging.class);

    /** The {@code staging-state/} root. This class is its single composer: every key comes from {@link #stateKey}. */
    static final String ROOT = "staging-state";

    /** The {@code staging-lock/} root: per-id single-writer leases the mutations take, so a {@code stage} landing
     *  mid-{@code promote} never links a pointer into a tree promotion has walked and sealed. This class is its single
     *  composer. */
    public static final String LOCKS = "staging-lock";

    /** How long a lease is held before a crashed holder's lease may be stolen: long enough for a mutation's few pointer
     *  writes; a live {@code promote} renews well inside it, so it bounds only an abandoned holder. */
    static final Duration LOCK_TTL = Duration.ofMinutes(2);

    private final Publication publication;
    private final ArtifactStore store;
    private final StoreRepositoryInventory inventory;
    private final Lease lock;

    public StoreStaging(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** A staging over an explicit {@link Publication}, for a test driving promotion against a screen chain the module
     *  path does not discover. Exported only to the staging tests. */
    public StoreStaging(ArtifactStore store, Publication publication) {
        this.publication = publication;
        this.store = store;
        this.inventory = new StoreRepositoryInventory(store);
        this.lock = new Lease(store, LOCKS, LOCK_TTL);
    }

    @Override
    public StagingState state(String id) throws IOException {
        // The marker is "<STATE> <instant>"; only the first token is read, totally (see stateOf), so one garbled marker
        // cannot fail the stagingList walk that calls state() per id.
        return store.readVersioned(stateKey(id))
                .map(versioned -> stateOf(new String(versioned.content(), StandardCharsets.UTF_8).trim().split("\\s+")))
                .orElse(StagingState.OPEN);
    }

    /** The state a marker's tokens name, reading a corrupt, foreign or missing first token as {@code OPEN}, so a
     *  garbled marker neither fails the listing walk nor refuses a fresh deploy into an id whose marker was torn. */
    private static StagingState stateOf(String[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return StagingState.OPEN;
        }
        try {
            return StagingState.valueOf(tokens[0]);
        } catch (IllegalArgumentException e) {
            return StagingState.OPEN;
        }
    }

    /** How stale an {@code OPEN} stamp may grow before a deploy refreshes it: a long session re-stamps at most hourly,
     *  not per file, and the reap's much longer TTL still measures "untouched", not "old but active". */
    private static final Duration REFRESH = Duration.ofHours(1);

    /** Deploy content into staging id {@code id} at the release path it will occupy; held, not resolvable. The body
     *  streams into the store, never buffered. The first deploy stamps the id's {@code OPEN} marker, and a deploy an
     *  hour or more later refreshes it, so an active staging is never taken for abandoned. */
    @Override
    public void stage(String id, String releasePath, InputStream content) throws IOException {
        // The client-supplied id and path are guarded before either becomes a store key: a non-enforcing deployment
        // leaves the HTTP boundary un-normalised (see assertSafePath).
        assertSafePath(id);
        assertSafePath(releasePath);
        // A cheap early refusal before streaming the body, so a sealed id stores no orphan blob; re-checked under the
        // lock.
        StagingState early = state(id);
        if (early != StagingState.OPEN) {
            throw new IllegalStateException("Cannot stage into a " + early + " repository: " + id);
        }
        // The body streams into the store before the lease is taken: the blob is inert until linked, and hashing it
        // under the short lease could outlive the lease's ttl.
        String blob = publication.storeBlob(content);
        String holder = UUID.randomUUID().toString();
        if (!lock.acquire(id, holder, Instant.now())) {
            // Another mutation holds this id's lease: refuse rather than interleave and orphan a pointer past a seal. A
            // 409 the client retries.
            throw new IllegalStateException("Staging repository " + id + " is being mutated concurrently; retry");
        }
        try {
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(stateKey(id));
            String[] value = marker
                    .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim().split("\\s+"))
                    .orElse(null);
            StagingState state = stateOf(value);   // total, like state()/reap(): a torn marker degrades to OPEN, not a 400
            if (state != StagingState.OPEN) {
                throw new IllegalStateException("Cannot stage into a " + state + " repository: " + id);
            }
            Instant now = Instant.now();
            Instant stamped = value != null && value.length > 1 ? parse(value[1]) : null;
            if (stamped == null || Duration.between(stamped, now).compareTo(REFRESH) >= 0) {
                // A lost race means a concurrent deploy stamped it; either way the marker is fresh.
                store.writeVersioned(stateKey(id),
                        (StagingState.OPEN.name() + " " + now).getBytes(StandardCharsets.UTF_8),
                        marker.map(ArtifactStore.Versioned::token).orElse(null));
            }
            publication.link(staging(id) + releasePath, blob);
        } finally {
            lock.release(id, holder, Instant.now());
        }
    }

    /**
     * The staging ids: the state markers unioned with the live trees under {@code publish/staging}, as {@link #reap}
     * walks them. A tree whose marker a partial purge lost is still a staging an operator must see and be able to
     * review, promote or drop - {@link #state} reads it as {@code OPEN}, {@link #staged} walks the tree, and
     * {@link #promote}/{@link #drop} accept it - with no reap pass needed.
     */
    @Override
    public Window ids(int limit) {
        // One bounded page of state markers and one of live held trees, merged, and whether either had more.
        Set<String> ids = new LinkedHashSet<>();
        List<String> markers = new ArrayList<>();
        store.page(ROOT, "", limit + 1, markers::add);
        List<String> trees = new ArrayList<>();
        store.page("publish/staging", "", limit + 1, trees::add);
        ids.addAll(markers);
        ids.addAll(trees);
        boolean more = markers.size() > limit || trees.size() > limit || ids.size() > limit;
        return new Window(List.copyOf(ids.stream().limit(limit).toList()), more);
    }

    @Override
    public int stagedAtMost(String id, int cap) throws IOException {
        int[] count = {0};
        try {
            collect("publish" + staging(id), _ -> {
                if (++count[0] >= cap) {
                    throw ENOUGH;
                }
            });
        } catch (Enough _) {
            return cap;
        }
        return count[0];
    }

    /** The early exit of a bounded count - control flow, not a failure. */
    private static final class Enough extends IOException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final Enough ENOUGH = new Enough();

    /** The release paths currently staged under {@code id}. */
    @Override
    public List<String> staged(String id) throws IOException {
        List<String> paths = new ArrayList<>();
        collect("publish" + staging(id), paths::add);
        return paths;
    }

    /** Promote every staged artifact into the release layout and seal the id. All-or-nothing and lossless: every staged
     *  path must first resolve to a stored blob and a format that claims it, or the whole promotion is refused with
     *  nothing mutated. A staged pointer is unpublished only after its release has served, so a partial failure leaves
     *  the staged copy intact. A gate quarantine on promotion keeps the artifact recoverable - its staged copy stays
     *  and it sits in the {@code /quarantine} review view - and the id stays {@code OPEN} rather than sealing a release
     *  that lost it. */
    @Override
    public void promote(String id) throws IOException {
        assertSafePath(id);
        String holder = UUID.randomUUID().toString();
        if (!lock.acquire(id, holder, Instant.now())) {
            throw new IllegalStateException("Staging repository " + id + " is being mutated concurrently; retry");
        }
        try {
            if (state(id) != StagingState.OPEN) {
                throw new IllegalStateException("Only an open repository can be promoted, was " + state(id));
            }
            List<String> paths = staged(id);
            // Phase 1 - validate the whole set before mutating anything, so an unclaimed staged file fails the
            // promotion loudly rather than being dropped while the id seals PROMOTED.
            Map<String, String> blobs = new LinkedHashMap<>();
            for (String releasePath : paths) {
                Optional<String> hash = publication.blob(staging(id) + releasePath);
                if (hash.isEmpty()) {
                    throw new IllegalStateException("Cannot promote " + id + ": staged path has no stored blob: "
                            + releasePath);
                }
                if (formatFor(releasePath) == null) {
                    throw new IllegalStateException("Cannot promote " + id
                            + ": no installed format claims staged path: " + releasePath);
                }
                blobs.put(releasePath, hash.get());
            }
            // Phase 2 - publish every path without sealing or dropping any staged pointer, so a failure part way (an
            // I/O error, a quarantine) rolls back every release this pass created. Only once the whole set has released
            // does the commit leg unpublish the staged pointers and seal. A crash in between re-converges on the next
            // promote, since re-linking a content-addressed blob is idempotent.
            List<String> released = new ArrayList<>();
            List<String> withheld = new ArrayList<>();
            try {
                for (String releasePath : paths) {
                    if (!lock.renew(id, holder, Instant.now())) {
                        // The lease lapsed and a rival took it: abandon without rolling back. A rollback is an
                        // unconditional unpublish and could retract a release the rival already committed; our staged
                        // pointers are untouched, so the holder re-converges.
                        throw new LostLeaseException(
                                "Lost the single-writer staging lease mid-promotion of " + id + "; retry");
                    }
                    RepositoryFormat format = formatFor(releasePath);
                    try (InputStream in = store.open("blobs/" + blobs.get(releasePath))) {
                        format.handle(new StagedPublish(releasePath, in), store);
                    }
                    if (publication.located(releasePath).isEmpty()) {
                        // The gate withheld it: recoverable as its staged copy and in the /quarantine view. Not
                        // released, so neither committed nor rolled back.
                        withheld.add(releasePath);
                    } else {
                        released.add(releasePath);
                    }
                }
            } catch (LostLeaseException lost) {
                // The lease was lost mid-pass: abandon without rollback, as above.
                throw lost;
            } catch (IOException | RuntimeException failure) {
                // The rollback is an unconditional unpublish, and the failing step (format.handle) is unbounded and can
                // outlast the lease, during which a rival may have re-published and sealed the same releases.
                // lock.guarded rolls back only while we provably still hold the lease (a renew against our holder
                // token; a store error counts as not owned) and otherwise leaves the rival's releases intact; the
                // original failure surfaces either way.
                if (!lock.guarded(id, holder, Instant.now(), () -> rollback(released))) {
                    LOGGER.warn("lease lost during handle; skipping rollback, rival owns the promotion of " + id
                            + " - surfacing the original failure without retracting the rival's committed releases");
                }
                throw failure;
            }
            if (!withheld.isEmpty()) {
                // Honour the gate atomically: retract the releases, leave the id OPEN with every staged copy for
                // review, and report which paths were held back - fenced through the same lease as the failure
                // rollback.
                lock.guarded(id, holder, Instant.now(), () -> rollback(released));
                throw new IllegalStateException("Promotion of " + id
                        + " withheld by the compliance gate; staged copies retained for review: " + withheld);
            }
            // Commit: record each release, drop the superseded staged pointers and mark PROMOTED. Staged pointers go
            // only here, so any abort above leaves them intact.
            for (String releasePath : released) {
                inventory.record(releasePath, Instant.now());
                publication.unpublish(staging(id) + releasePath);
            }
            setState(id, StagingState.PROMOTED);
            // A promotion is an event; best-effort, and a no-op without an event sink.
            EventSink.emit(store, RepositoryEvent.promotion(id, released.size(), Instant.now()));
        } finally {
            lock.release(id, holder, Instant.now());
        }
    }

    /** The first discovered format that owns this release path, or null when no format claims it. */
    private static RepositoryFormat formatFor(String releasePath) {
        // installed(), so a format configured off promotes nothing.
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            if (format.handles(releasePath)) {
                return format;
            }
        }
        return null;
    }

    /** Drop every staged artifact under {@code id} without releasing it, and seal the id. */
    @Override
    public void drop(String id) throws IOException {
        assertSafePath(id);
        String holder = UUID.randomUUID().toString();
        if (!lock.acquire(id, holder, Instant.now())) {
            throw new IllegalStateException("Staging repository " + id + " is being mutated concurrently; retry");
        }
        try {
            StagingState state = state(id);   // one marker read, one consistent snapshot for the guard and its message
            if (state == StagingState.PROMOTED || state == StagingState.DROPPED) {
                throw new IllegalStateException("A " + state + " repository cannot be dropped: " + id);
            }
            for (String releasePath : staged(id)) {
                publication.unpublish(staging(id) + releasePath);
            }
            setState(id, StagingState.DROPPED);
        } finally {
            lock.release(id, holder, Instant.now());
        }
    }

    /** A minimal in-process {@code PUT} exchange that streams a staged blob into a format's {@code handle}, so
     *  promotion re-publishes exactly as a deploy does. The response is discarded. */
    private static final class StagedPublish implements FormatExchange {

        private final String path;
        private final InputStream body;

        private StagedPublish(String path, InputStream body) {
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return "PUT";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return body;
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) throws IOException {
            return OutputStream.nullOutputStream();
        }
    }

    /** The scheduled reap of the staging key spaces. An id whose marker sat {@code OPEN} (or {@code CLOSED}) past
     *  {@code ttl} is abandoned: its staged artifacts are unpublished (the blobs fall to the next collection) and its
     *  marker deleted. A sealed marker past {@code ttl} has nothing left to guard - the sealed-transition refusal
     *  matters against a client reusing the id moments later, not weeks - so it is deleted. A staged tree with no
     *  marker is stamped at {@code now} and reaped a TTL later, so nothing is reaped under one TTL after first
     *  observation; a marker without a stamp was not written here and is left alone. Returns how many ids were
     *  reaped. */
    public int reap(Instant now, Duration ttl) throws IOException {
        // The ids a page at a time: first every id with a marker, then every staged root without one (which the loop
        // stamps). A farm that opens a staging per build and never closes them would otherwise leave a reap that cannot
        // fit in heap. An id both levels hold is judged once, through its marker.
        int reaped = 0;
        Names ids = reapable();
        for (String next = ids.next(); next != null; next = ids.next()) {   // paged, level by level - the same ids the window lists
            String id = next;                                     // final for the lambdas below
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(stateKey(id));
            if (marker.isEmpty()) {
                // A staged tree with no marker: stamp it, reap once the TTL has passed.
                store.writeVersioned(stateKey(id),
                        (StagingState.OPEN.name() + " " + now).getBytes(StandardCharsets.UTF_8), null);
                continue;
            }
            String[] value = new String(marker.get().content(), StandardCharsets.UTF_8).trim().split("\\s+");
            StagingState state;
            try {
                state = StagingState.valueOf(value[0]);
            } catch (IllegalArgumentException _) {
                continue;                                        // not a state marker we understand - never delete it
            }
            Instant since = value.length > 1 ? parse(value[1]) : null;
            if (since == null) {
                continue;                                        // not "<STATE> <instant>" - never delete it
            }
            if (Duration.between(since, now).compareTo(ttl) < 0) {
                continue;
            }
            // The same lease every request-driven mutation takes, so the reap never interleaves with a slow or resumed
            // mutation on this id. A held lease means a live writer owns it - not abandoned - so skip it this round.
            String reapHolder = UUID.randomUUID().toString();
            if (!lock.acquire(id, reapHolder, now)) {
                continue;
            }
            try {
                // Re-validate under the lease: a mutation that won the lease after the read above may have re-stamped
                // OPEN or sealed a fresh terminal state, and reaping on the stale decision would delete a just-staged
                // pointer or a fresh seal. Only a marker still present, valid and past the TTL proceeds.
                Optional<ArtifactStore.Versioned> current = store.readVersioned(stateKey(id));
                if (current.isEmpty()) {
                    continue;                                    // already deleted by another pass
                }
                String[] latest = new String(current.get().content(), StandardCharsets.UTF_8).trim().split("\\s+");
                try {
                    StagingState.valueOf(latest[0]);
                } catch (IllegalArgumentException _) {
                    continue;                                    // no longer a state we understand - never delete it
                }
                Instant latestSince = latest.length > 1 ? parse(latest[1]) : null;
                if (latestSince == null || Duration.between(latestSince, now).compareTo(ttl) < 0) {
                    continue;                                    // refreshed/re-stamped since - not abandoned this round
                }
                // Unpublish any staged pointer still under this id before removing its marker - the whole tree of an
                // abandoned OPEN/CLOSED id, or the residual a stage racing a seal can leave beside a sealed one
                // (withheld from serving meanwhile). Each mutation goes through lock.guarded on a fresh instant, which
                // renews the lease while we own it and skips once a rival has taken it, so a long unpublish loop can
                // never delete a rival's fresh deploy and marker. On a lost lease the residual waits for a later sweep;
                // the marker goes only if the whole loop stayed owned.
                boolean owned = true;
                for (String releasePath : staged(id)) {
                    if (!lock.guarded(id, reapHolder, Instant.now(),
                            () -> publication.unpublish(staging(id) + releasePath))) {
                        owned = false;                          // a rival stole the lapsed lease - stop, retry later
                        break;
                    }
                }
                if (owned && lock.guarded(id, reapHolder, Instant.now(), () -> store.delete(stateKey(id)))) {
                    reaped++;
                }
            } finally {
                lock.release(id, reapHolder, now);
            }
        }
        // Lapsed single-writer leases a crash left on ids no mutation revisits; kept off the reaped count, which
        // measures abandoned stagings.
        lock.reapExpired(now);
        return reaped;
    }

    /** The staging ids, paged: the markers' level, then the staged roots without a marker yet - what the reap visits
     *  and the listing window starts from. One page of names is held at a time. */
    private Names reapable() {
        return Names.concat(Names.over(store, ROOT),
                Names.over(store, "publish/staging").select(name ->
                        store.readVersioned(stateKey(name)).isEmpty() ? Optional.of(name) : Optional.empty()));
    }

    private static Instant parse(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static String staging(String id) {
        return "/staging/" + id;
    }

    /** Retract every release pointer a failed or withheld promotion created, so an aborted promotion leaves nothing
     *  released and every staged copy intact. Re-linking on a later retry is idempotent. */
    private void rollback(List<String> releasedPaths) throws IOException {
        for (String releasePath : releasedPaths) {
            publication.unpublish(releasePath);
        }
    }

    /** A promotion lost its lease mid-pass. Distinct from a failure so the catch abandons without rolling back, which
     *  would race the new holder. An {@link IllegalStateException}, so a caller's retry-on-ISE handling applies. */
    private static final class LostLeaseException extends IllegalStateException {
        LostLeaseException(String message) {
            super(message);
        }
    }

    /**
     * Reject a staging id or release path that could escape {@code publish/staging/<id>} once it is a store key: a bare
     * {@code ..}, or a percent-encoded or double-encoded variant ({@code %2e%2e}, {@code ..%2f}, {@code %252e}). A
     * non-enforcing deployment never runs the HTTP boundary's normaliser, so the domain guards its own keys; a staged
     * path never legitimately carries a percent-escape, so any {@code %} surviving one decode is refused.
     *
     * <p>Deliberately independent of the boundary's guard: two layers checking the same property must not share one
     * implementation, or a defect in it would be a defect in both.
     */
    private static void assertSafePath(String value) {
        String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        if (value.contains("..") || decoded.contains("..") || decoded.indexOf('%') >= 0
                || decoded.contains("//") || decoded.contains("/./")
                || decoded.endsWith("/.") || decoded.endsWith("/..")) {
            throw new IllegalArgumentException("An unsafe staging path was rejected: " + value);
        }
    }

    private void setState(String id, StagingState state) throws IOException {
        // Compare-and-set with retry: promote and drop have already re-published or unpublished by the time this seals,
        // so losing the marker to a concurrent token change would leave the id OPEN and re-promotable.
        Retries.update(store, stateKey(id), _ -> (state.name() + " " + Instant.now()).getBytes(StandardCharsets.UTF_8));
    }

    /** One staging id's marker key - the only place a key in this space is composed. */
    private static String stateKey(String id) {
        return ROOT + "/" + id;
    }

    /** The bounds a staged tree is descended under. A promotion is all-or-nothing over this set, so a short listing
     *  would drop artifacts from a release claimed whole: the entry cap is only the page {@link #collect} follows to
     *  exhaustion, and the step budget is the binding bound, raising a
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short. Depth stays at
     *  {@link ArtifactStore#MAX_SEGMENTS}, since a staged path's depth is client-controlled. It drains, so it pages at
     *  {@link BoundedChildren#DRAIN_PAGE}. */
    private static final PagedTreeWalk STAGED = PagedTreeWalk.bounded().steps(1_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Stream the release path of every pointer staged under {@code root} through the shared bounded tree walk, so
     *  neither a deep client path nor a wide folder reaches the stack or heap. */
    private void collect(String root, Paths paths) throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = STAGED.walk(store, root, cursor,
                    key -> paths.accept(key.substring(root.length())));
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** One staged release path, root-relative and keeping its leading {@code /}. */
    @FunctionalInterface
    private interface Paths {
        void accept(String releasePath) throws IOException;
    }
}
