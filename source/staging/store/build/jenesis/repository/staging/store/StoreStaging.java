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
 * Store-backed staging and promotion. A deploy lands under a staging id - held in the store under a staging view,
 * so it does not resolve - and the id is later promoted (every held artifact re-published in full into the release
 * layout, so it gains its module view) or dropped. State is persisted, so the lifecycle survives across requests,
 * unlike the in-memory {@link build.jenesis.repository.staging.StagingRepository} model that pins down the same
 * transitions in a unit test. All storage
 * goes through the repository's {@link Publication}; promotion never copies bytes, it re-points the same
 * content-addressed blobs.
 *
 * <p>The persisted {@code staging-state/<id>} marker carries the state <em>and when it was reached</em>
 * ({@code OPEN <instant>} on the first deploy, {@code PROMOTED <instant>} / {@code DROPPED <instant>} on sealing),
 * so the {@link #reap} sweep the scheduled cleanup pass drives can age the lifecycle: an abandoned-OPEN id past the
 * TTL has its staged artifacts unpublished (their blobs then fall to the blob GC) and a sealed marker past the TTL
 * is deleted - neither leaks forever, while a marker younger than the TTL keeps the sealed transitions rejected
 * exactly as before. A staged tree without any marker - one whose marker a partial purge of the staging-state space
 * lost - is stamped on first observation and reaped a TTL later, so it converges too; a marker that is not
 * {@code <STATE> <instant>} was not written here, and the reap never deletes it.
 */
public final class StoreStaging implements Staging {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreStaging.class);

    /** The {@code staging-state/} space's root, and this class is its single composer: every marker key is
     *  built by {@link #stateKey} and the manifest declares this constant rather than re-spelling the literal. */
    static final String ROOT = "staging-state";

    /** The {@code staging-lock/} space's root: the per-id single-writer leases the request-driven mutations take, so
     *  a {@code stage} landing mid-{@code promote} never links a pointer into a tree promotion has already walked and
     *  sealed. This class is the space's single composer, and the manifest declares this constant. */
    public static final String LOCKS = "staging-lock";

    /** How long a taken lease stays held before a crashed holder's lease is stealable - long enough to cover a
     *  staging mutation's few pointer writes, short enough that a crash does not wedge the id for long. A live
     *  {@code promote} renews well inside this window, so the ttl bounds only an abandoned holder, never an active
     *  one. */
    static final Duration LOCK_TTL = Duration.ofMinutes(2);

    private final Publication publication;
    private final ArtifactStore store;
    private final StoreRepositoryInventory inventory;
    private final Lease lock;

    public StoreStaging(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** A staging over an explicit {@link Publication} - the seam a test uses to drive promotion against a screen chain
     *  (a compliance-gate quarantine's read side) the module path does not discover, exactly as {@link Publication}'s
     *  own injected-interceptor constructors are the seam for an embedded screen. Exported only to the staging tests. */
    public StoreStaging(ArtifactStore store, Publication publication) {
        this.publication = publication;
        this.store = store;
        this.inventory = new StoreRepositoryInventory(store);
        this.lock = new Lease(store, LOCKS, LOCK_TTL);
    }

    /** The ids of all staging repositories the store holds: the recorded-state markers unioned with the live held
     *  trees under {@code publish/staging}, exactly as {@link #reap} walks them. A staged tree whose marker is absent
     *  - a marker lost to a partial purge of the {@code staging-state} key-space - is still a real staging repository the console must show and the operator
     *  must be able to review, promote or drop; listing the markers alone served a silently-incomplete view, the tree
     *  invisible and unreleasable until the reap eventually stamped-then-GC'd it (and the reap is opt-in, so it may
     *  never run). {@link #state} defaults a marker-less id to {@code OPEN}, {@link #staged} walks the live tree and
     *  {@link #promote}/{@link #drop} accept {@code OPEN}, so a surfaced marker-less tree is immediately reviewable
     *  and releasable with no reap pass required - self-healing by construction over a store enabled-over pre-existing
     *  staged content. */
    @Override
    public StagingState state(String id) throws IOException {
        // The marker is "<STATE> <instant>". Only the first token is the state, read totally (see stateOf): a
        // corrupt or foreign marker whose first token is not a StagingState
        // reads as OPEN rather than throwing, so one garbled marker cannot 500 the whole `stagingList` walk (which
        // calls state() per id) - the same tolerance the reap's valueOf carries, mirroring the missing-marker default.
        return store.readVersioned(stateKey(id))
                .map(versioned -> stateOf(new String(versioned.content(), StandardCharsets.UTF_8).trim().split("\\s+")))
                .orElse(StagingState.OPEN);
    }

    /** The lifecycle state a marker's whitespace-split tokens name, tolerating a corrupt or foreign first
     *  token (or none) as {@code OPEN} - the same totality {@link #state} and {@link #reap} carry, so one garbled
     *  marker neither 500s the listing walk nor 400s a fresh deploy into an id whose marker was torn. */
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

    /** How much older than the wall clock an {@code OPEN} marker's stamp may grow before a deploy refreshes it: a
     *  long-running staging session re-stamps at most once an hour (not once per file), yet the reap's much longer
     *  TTL still measures "untouched", never "old but active". */
    private static final Duration REFRESH = Duration.ofHours(1);

    /** Deploy content into staging repo {@code id} at the release path it will occupy; held, not resolvable. The
     *  body streams straight into the content-addressed store (hash on write), so a large deploy is never buffered.
     *  The first deploy stamps the id's {@code OPEN} marker (so the staging list shows the open id and the reap
     *  sweep can age an abandoned one) and a deploy an hour or more later refreshes the stamp, so an active
     *  staging is never mistaken for an abandoned one. */
    @Override
    public void stage(String id, String releasePath, InputStream content) throws IOException {
        // Guard the client-supplied id and release path before either becomes a store key - defence in depth beside the
        // HTTP boundary, which a non-enforcing (anonymous) deployment leaves un-normalized (see assertSafePath).
        assertSafePath(id);
        assertSafePath(releasePath);
        // Cheap early reject before streaming the body: a sealed id never accepts a deploy, so refuse without storing
        // an orphan blob. Racy by itself, but the authoritative check is re-run under the lock below.
        StagingState early = state(id);
        if (early != StagingState.OPEN) {
            throw new IllegalStateException("Cannot stage into a " + early + " repository: " + id);
        }
        // Stream the (possibly huge) body into the content-addressed store before taking the guard: the blob is inert
        // until a pointer links it, and hashing it under the short single-writer lease could outlive the lease's ttl.
        String blob = publication.storeBlob(content);
        String holder = UUID.randomUUID().toString();
        if (!lock.acquire(id, holder, Instant.now())) {
            // Another stage/promote/drop holds this id's single-writer lease - refuse rather than interleave and
            // orphan a pointer past a concurrent seal. A 409 the client retries once the mutation in flight completes.
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
                // A lost race means a concurrent deploy stamped it - either way the marker is fresh, so no retry.
                store.writeVersioned(stateKey(id),
                        (StagingState.OPEN.name() + " " + now).getBytes(StandardCharsets.UTF_8),
                        marker.map(ArtifactStore.Versioned::token).orElse(null));
            }
            publication.link(staging(id) + releasePath, blob);
        } finally {
            lock.release(id, holder, Instant.now());
        }
    }

    @Override
    public Window ids(int limit) {
        // One bounded page of state markers and one of live held trees, merged: the ids a screen shows first, and
        // whether either listing had more - never the whole staging history to draw one panel.
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

    /**
     * Promote every staged artifact into the release layout (a full dual-view publish) and seal the id. Promotion is
     * all-or-nothing and lossless: it first validates that every staged path resolves to a stored blob <em>and</em> an
     * installed format that claims it, and refuses the whole promotion (mutating nothing, retaining every staged file)
     * if any path does not - a staged file no format claims is never silently dropped. Only after a path's release
     * publish has actually served is its staged pointer unpublished, so a partial failure leaves the staged copy
     * intact. The compliance gate's disposition is honoured: an artifact the gate withholds on promotion (a quarantine)
     * stays recoverable - its staged copy is kept and it sits in the {@code /quarantine} review view - and the
     * promotion refuses to seal, so the id stays {@code OPEN} for review rather than reporting a completed release that
     * lost the artifact.
     */
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
            // Phase 1 - validate the whole set before mutating anything: every staged path must resolve to both a
            // stored blob and a format that claims it, or the promotion refuses wholesale and nothing is touched. This
            // is what makes it lossless: an unclaimed staged file fails the promotion loudly instead of being
            // unpublished (dropped) while the id is sealed PROMOTED and the client sees success.
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
            // Phase 2 - publish every path into the release layout WITHOUT sealing the id or dropping any staged
            // pointer yet, so the pass is all-or-nothing: a failure partway through the set (an I/O error re-publishing
            // a later path, or a compliance-gate quarantine) rolls back every release this pass created, leaving NO
            // artifact released and every staged copy intact. Only once the whole set has released does the commit leg
            // unpublish the staged pointers and seal PROMOTED. A crash between a release and the commit re-converges on
            // the next lease-guarded promote, since re-linking the same content-addressed blob is idempotent (§4/§5).
            List<String> released = new ArrayList<>();
            List<String> withheld = new ArrayList<>();
            try {
                for (String releasePath : paths) {
                    if (!lock.renew(id, holder, Instant.now())) {
                        // Lost single-writer status - this node's lease lapsed and a rival took it. We no longer hold
                        // the lease, so we must NOT mutate the release layout: abandon WITHOUT rolling back (see the
                        // dedicated catch). Rolling back here would race the new lease holder and, because unpublish is
                        // an unconditional delete, could retract a release the rival has already committed - silently
                        // dropping an artifact from a sealed promotion. Our own staged pointers are untouched (the
                        // commit leg was never reached), so whoever holds the lease re-converges the set idempotently.
                        throw new LostLeaseException(
                                "Lost the single-writer staging lease mid-promotion of " + id + "; retry");
                    }
                    RepositoryFormat format = formatFor(releasePath);
                    try (InputStream in = store.open("blobs/" + blobs.get(releasePath))) {
                        format.handle(new StagedPublish(releasePath, in), store);
                    }
                    if (publication.located(releasePath).isEmpty()) {
                        // The compliance gate withheld it (quarantine): recoverable both as its retained staged copy and
                        // in the /quarantine review view. Not released, so not committed and not rolled back.
                        withheld.add(releasePath);
                    } else {
                        released.add(releasePath);
                    }
                }
            } catch (LostLeaseException lost) {
                // We lost the lease mid-pass - abandon WITHOUT rollback. Retracting here would race the new lease
                // holder and could delete a release it has already committed; our staged pointers are intact, so the
                // lease holder (the rival, or the next lease-guarded retry) re-converges the set idempotently.
                throw lost;
            } catch (IOException | RuntimeException failure) {
                // Lease-fence the rollback through the lease. rollback() is an UNCONDITIONAL
                // unpublish (delete) of every release this pass created, and the failing step - format.handle above -
                // is unbounded and can outlast the lease ttl (a large artifact, a slow gate, a hanging store). So this
                // node's lease may have lapsed mid-handle and a rival may have acquired it, re-published the same
                // content-addressed releases and SEALED the promotion (unpublish-then-mark PROMOTED). Rolling back then
                // would retract releases the rival has already committed - dropping an artifact from a sealed promotion,
                // unrecoverably - the exact hazard the LostLeaseException branch guards against below. lock.guarded
                // re-asserts single-writer ownership (renew against our holder token: true iff we still own the lease)
                // and rolls back ONLY while we still provably hold the lease; otherwise the rival is authoritative, so
                // it skips - leaving the rival's committed releases intact - and we surface the original failure either
                // way. (A store error probing ownership is treated as "not owned" by guarded, the fail-closed choice.)
                if (!lock.guarded(id, holder, Instant.now(), () -> rollback(released))) {
                    LOGGER.warn("lease lost during handle; skipping rollback, rival owns the promotion of " + id
                            + " - surfacing the original failure without retracting the rival's committed releases");
                }
                throw failure;
            }
            if (!withheld.isEmpty()) {
                // Honour the gate atomically: a quarantined artifact is not sealed lost, and no sibling is left released
                // beside it. Retract the releases, leave the id OPEN (every staged copy retained) for review, and report
                // which paths the gate held back. Fenced through the same owner: the loop renewed on each
                // iteration so we normally still hold the lease and roll back, but if it lapsed and a rival took over
                // (and committed the same releases) we must not retract its work - guarded skips and we still surface
                // the withheld outcome.
                lock.guarded(id, holder, Instant.now(), () -> rollback(released));
                throw new IllegalStateException("Promotion of " + id
                        + " withheld by the compliance gate; staged copies retained for review: " + withheld);
            }
            // Commit - the whole set released, so seal it: record each, drop the now-superseded staged pointers, and
            // mark PROMOTED. A staged pointer is unpublished only here, after its release publish has served, so any
            // abort above always leaves the staged copy intact.
            for (String releasePath : released) {
                inventory.record(releasePath, Instant.now());
                publication.unpublish(staging(id) + releasePath);
            }
            setState(id, StagingState.PROMOTED);
            // A promotion releases a staged set in full - an event an external system may want to react to; best-effort
            // and a no-op when no event sink (the webhook module) is installed.
            EventSink.emit(store, RepositoryEvent.promotion(id, released.size(), Instant.now()));
        } finally {
            lock.release(id, holder, Instant.now());
        }
    }

    /** The first discovered format that owns this release path, or null when no format claims it. */
    private static RepositoryFormat formatFor(String releasePath) {
        // installed(), so a staged artifact of a format configured off is not promoted through it either.
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

    /** A minimal in-process {@code PUT} exchange that streams a staged blob into a format's {@code handle}, so promotion
     *  re-publishes through the format exactly as a deploy does. The response is discarded - promotion cares only that
     *  the artifact is stored and cross-published, not about the status a client would see. */
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

    /**
     * The scheduled reap over the staging key spaces - the two ways staging otherwise grows forever. An id whose
     * marker has sat {@code OPEN} (or {@code CLOSED}) past {@code ttl} is an abandoned staging: its staged artifacts
     * are unpublished (the freed blobs fall to the next garbage collection) and its marker deleted. A sealed
     * ({@code PROMOTED} / {@code DROPPED}) marker past {@code ttl} has nothing left to guard - the sealed-transition
     * rejection matters against a client reusing the id moments later, not weeks - so it is deleted. Ageing needs a
     * timestamp, so a staged tree without any marker is <em>stamped</em> at {@code now} and reaped a full TTL later -
     * nothing is ever reaped less than one TTL after it was first observed - and a marker without one was not written
     * here and is left alone. Returns how many ids were
     * reaped (markers removed).
     */
    public int reap(Instant now, Duration ttl) throws IOException {
        // The ids a page at a time, never the whole set: first every id with a state marker, then every staged
        // pointer root that has none (a marker a partial purge lost, which the loop stamps). This used to list both levels
        // whole into one set before judging the first id, so a farm that opens a staging per build and never
        // closes them left a reap that could not run in the heap it was given - the staging-reap canary measured
        // the pass failing at a million open stagings under 512 MiB. An id both levels hold is judged once, through
        // its marker; one the marker level reaped is gone from the pointer level by the time that level is paged.
        int reaped = 0;
        Names ids = reapable();
        for (String next = ids.next(); next != null; next = ids.next()) {   // paged, level by level - the same ids the window lists
            String id = next;                                     // final for the lambdas below
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(stateKey(id));
            if (marker.isEmpty()) {
                // A staged tree with no marker (one a partial purge lost): stamp it, reap once the TTL has passed.
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
            // Take the SAME single-writer lock every request-driven mutation (open/close/promote/drop) holds before
            // touching this id's staged pointers and marker, so the reap cannot interleave with a slow or resumed
            // mutation on the same id (unpublishing a pointer a live promote is mid-commit on, or deleting a marker
            // under an active writer). If the lock is currently held, a live writer still owns the id - it is not
            // abandoned - so skip it this round and let a later sweep collect it once the writer is gone.
            String reapHolder = UUID.randomUUID().toString();
            if (!lock.acquire(id, reapHolder, now)) {
                continue;
            }
            try {
                // Re-validate the reap decision UNDER the lock. The marker/state/TTL above were read BEFORE the lock, so
                // a stage/promote/drop that won the lock in the window may have refreshed the marker (a resumed OPEN
                // re-stamped to now) or sealed a fresh terminal state (PROMOTED/DROPPED stamped now). Reaping on the
                // stale decision would delete a just-staged pointer and its live marker (data loss) or a freshly-sealed
                // marker (losing the seal, making the id re-stageable). Only proceed if the marker is STILL present, a
                // valid state, and STILL past the TTL - otherwise a writer touched it since, so skip and let a later
                // sweep revisit. Re-reading under the lock is authoritative: writers take this same lock before mutating.
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
                // Unpublish any staged pointer still under this id before removing its marker - for an abandoned OPEN /
                // CLOSED id that is its whole staged tree, and for a sealed PROMOTED / DROPPED id it is the residual a
                // stage that raced a seal (or a crash mid-mutation that outlived the single-writer lease) can leave:
                // the seal never revisits the id, so without this the residual pointer leaks forever. Withheld from
                // serving by StagingWithholdInterceptor meanwhile, so it is invisible until the reap collects it here.
                // Fence every lease-path mutation: reap holds the lease, but the mutations below
                // unpublish each staged pointer and delete the state marker. The original ran them UNCONDITIONALLY and
                // never renewed, so a reap of a large abandoned tree whose unpublish loop outran the 2-minute TTL would
                // let a rival stage() steal the lapsed lease and re-stamp a fresh OPEN marker + link a new pointer - and
                // this reap, unaware it lost the lease, would then drop the rival's fresh pointer and delete its
                // just-written marker (silent loss of an accepted deploy). Route each mutation through lock.guarded on a
                // FRESH instant - the promote-rollback fence: guarded re-asserts single-writer ownership via a
                // renew CAS against our holder token (true iff we still own the lease, which also EXTENDS it), so a long
                // loop keeps the lease fresh and the moment a rival takes it a guarded action is skipped. On a lost
                // lease we stop and leave the residual to a later sweep rather than wipe a live writer's staging; a store
                // error probing ownership is "not owned" (fail-closed). The marker delete lands only when the whole
                // unpublish loop stayed owned.
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
        // The single-writer locks the mutations take and release in a finally: a crash mid-mutation can outlive the
        // lease and orphan a staging-lock/<id> on a terminal id no later mutation ever revisits - the residual-cleanup
        // this module's lock contract names as its backstop. Reap the lapsed ones here, kept off the reaped-marker
        // count so the sweep's gauge still measures abandoned stagings, not lock hygiene.
        lock.reapExpired(now);
        return reaped;
    }

    /** The staging ids, paged: the state markers' level, then the staged roots that have no marker yet - what the
     *  reap visits and what the listing window takes its first entries from. One page of names is held at a time.
     *  The whole set used to be listed into a {@code LinkedHashSet} for both, which the staging-reap canary measured
     *  as an OutOfMemoryError at a million open stagings under 512 MiB. */
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

    /** Retract every release pointer a failed or withheld promotion pass created this run, so an aborted promotion
     *  leaves NO artifact released while every staged copy stays intact (its staged pointer is unpublished only on the
     *  commit leg, which an abort never reaches). Re-linking the same content-addressed blob on a later retry is
     *  idempotent, so a crash between a release and this rollback still converges on the next lease-guarded promote. */
    private void rollback(List<String> releasedPaths) throws IOException {
        for (String releasePath : releasedPaths) {
            publication.unpublish(releasePath);
        }
    }

    /** Signals that a promotion lost its single-writer lease mid-pass. Distinguished from a genuine failure so the
     *  catch abandons WITHOUT rolling back: once the lease is gone this node has no authority to mutate the release
     *  layout, and an unconditional rollback would race - and could delete a release already committed by - the new
     *  lease holder. An {@link IllegalStateException} so a caller's existing retry-on-ISE handling is unchanged. */
    private static final class LostLeaseException extends IllegalStateException {
        LostLeaseException(String message) {
            super(message);
        }
    }

    /** Reject a staging id or release path that could escape its intended {@code publish/staging/<id>} prefix once it
     *  is formed into a store key: a bare {@code ..}, or a percent-encoded / double-encoded variant ({@code %2e%2e},
     *  {@code %2E%2E}, {@code ..%2f}, {@code %252e}) that a naive {@code contains("..")} misses. The HTTP boundary
     *  decodes and normalizes too, but a non-enforcing (anonymous) deployment never runs the security-layer path
     *  normalizer, so the domain guards its own keys as well (defence in depth, §9). A staged coordinate is a plain
     *  artifact path that never legitimately carries a percent-escape, so any percent sign surviving one decode is
     *  refused outright - which also stops a double-encoding that would decode to {@code ..} a second time downstream.
     *
     *  <p>This deliberately does not share an implementation with the guard at the HTTP boundary, and a sweep for
     *  duplicated helpers should leave it alone. Two layers checking the same property is the point: sharing one
     *  implementation would make them one check wearing two hats, and a defect in it would be a defect in both. The
     *  duplication is the independence. */
    private static void assertSafePath(String value) {
        String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        if (value.contains("..") || decoded.contains("..") || decoded.indexOf('%') >= 0
                || decoded.contains("//") || decoded.contains("/./")
                || decoded.endsWith("/.") || decoded.endsWith("/..")) {
            throw new IllegalArgumentException("An unsafe staging path was rejected: " + value);
        }
    }

    private void setState(String id, StagingState state) throws IOException {
        // Compare-and-set with retry, not a fire-and-forget write: promote()/drop() have already irreversibly
        // re-published and unpublished the staged artifacts by the time this seals the id, so silently dropping the
        // PROMOTED/DROPPED marker on a lost CAS (a concurrent stage-refresh, reap stamp or second promote/drop bumps
        // the token) would leave the id OPEN and re-promotable. Re-read and retry so the seal is never lost.
        Retries.update(store, stateKey(id), _ -> (state.name() + " " + Instant.now()).getBytes(StandardCharsets.UTF_8));
    }

    /** One staging id's marker key - the only place a key in this space is composed. */
    private static String stateKey(String id) {
        return ROOT + "/" + id;
    }

    /** The bounds a staged tree is descended under. A promotion is all-or-nothing over exactly this set, so a listing
     *  cut short would silently drop staged artifacts from the release it claims to have promoted whole - the entry cap
     *  is therefore only the per-call continuation {@link #collect} follows to exhaustion, and the binding bound is the
     *  step budget (one {@link ArtifactStore#exists} probe per opened node), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short. Depth stays at the
     *  primitive's {@link ArtifactStore#MAX_SEGMENTS} default: a staged release path's depth is client-controlled (the
     *  controller only rejects {@code ..}, not depth), so one deeper than the store's own write ceiling now fails by
     *  name instead of being descended.
     *  It drains, so it pages at {@link BoundedChildren#DRAIN_PAGE}: on the filesystem store every page rescans
     *  its directory, and for a walk that follows its continuation to exhaustion the page width is the number
     *  of rescans. */
    private static final PagedTreeWalk STAGED = PagedTreeWalk.bounded().steps(1_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Stream the release path of every pointer staged under {@code root} (the root prefix stripped) through the
     *  shared bounded tree walk - iterative and paged, so neither a client-controlled path depth nor a wide
     *  staged folder reaches the call stack or one heap-resident level listing. */
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
