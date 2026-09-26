package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The maintained rollup identity of a repository's published inventory: a single small content-addressed digest - the
 * XOR of a per-version member digest over the published coordinate set, each member folding that version's
 * declared-license fingerprint - that the whole-repository SBOM / attribution {@code NOTICE} exports derive their ETag
 * from, so an {@code If-None-Match} revalidation answers {@code 304} with ONE O(1) small-object read instead of the
 * O(#versions) coordinate walk that assembles the document (§4/§7: an ETag / identity must not be an
 * O(#versions) walk).
 *
 * <p>It is maintained incrementally at the mutation points that change the set - a publish folds a new member in
 * ({@link StoreRepositoryInventory#record}), an eviction folds it out ({@link StoreRepositoryInventory#evict}), a
 * declared-license change re-folds the affected member ({@link LicenseInventory#record}) - and recomputed
 * authoritatively by the reconcile sweep ({@link StoreRepositoryInventory#rebuildIdentity}), so it converges even when
 * added late: an absent accumulator is built once, in full, on first read. The XOR fold is order-independent and
 * associative, so a concurrent or replayed mutation converges to the same digest.
 *
 * <p><strong>Every fold is derived from a committed transition, and applied exactly once.</strong> A publish folds
 * its member in after the write that made the version a member has committed; a license record re-folds after the
 * document transition it made has committed, from the section it replaced to the one it wrote, so concurrent records
 * of one version telescope (old→a, a→b) instead of cancelling; an eviction folds out after the member is gone. The
 * folds used to be grouped into the publish's own batch as a single compare-and-set attempt whose outcome nobody
 * read, so under thirty-two concurrent publishers most of them lost the race and were dropped - "left to the
 * reconcile rebuild" - and every conditional read until that daily pass answered <em>not modified</em> after a
 * publish had landed. The identity-drift canary measured exactly that, and this class is where the fix lives: a fold
 * retries through {@link Retries#COMPARE_AND_SET} with backoff, and a fold that still loses drops the rollup rather
 * than leaving it stale, so the next read rebuilds from truth.
 *
 * <h2>A rebuild under concurrent publishes</h2>
 *
 * <p>A rebuild walks the published set while publishes keep landing, and a member published during the walk is
 * either seen by the walk or folded by its publish - never both, never neither. The rebuild stamps the rollup with a
 * <em>boundary instant</em> before it walks; a member whose publish instant is at or before the boundary belongs to
 * the walk (its fold skips), one published after it belongs to its own fold (the walk skips it). While the rebuild is
 * in flight the rollup object carries the folds made since the boundary, the boundary itself and, when it replaced a
 * settled rollup, that rollup as the value readers keep answering from; settling writes the walk's digest XOR the
 * folds since, in one compare-and-set against the stamped object, so a fold that lands between the walk's end and
 * the settle is retried into the settled value rather than lost. The boundary is set {@value #ALLOWANCE_SECONDS}
 * seconds after the rebuild's clock so a publisher on a peer whose clock runs ahead still classifies the same way,
 * and the walk begins {@value #GRACE_SECONDS} seconds after the boundary so a publish stamped before it has landed
 * its member before the walk can pass it. What remains is a publish whose write outlives that grace, an eviction or
 * a license change of a boundary-side member during the walk itself - each a bounded drift the next reconcile heals.
 *
 * <h2>A member dated before the boundary that lands after the walk has passed it</h2>
 *
 * <p>The split above rests on the grace: a publish stamped at or before the boundary has {@value #GRACE_SECONDS}
 * seconds to write its member before the walk could reach it, so the fold may decline and leave it to the walk. The
 * grace bounds the gap between the boundary and the walk's START. It does not bound where in the enumeration the
 * walk has GOT to, and the enumeration is live and in key order - so a member whose row is written while the walk
 * runs, at a key the walk has already passed, was seen by neither half: its own fold declined it and the walk never
 * enumerated it, and the rollup was short by that member until something rebuilt from truth. Reached by the fleet's
 * PeerClock scenario about twice in sixty runs, losing a different member each time.
 *
 * <p>Reproduced 2026-09-16 by HOLDING the walk rather than by racing it, and the difference is the whole reason the
 * reproduction is trustworthy. Sizing a seed so the walk "takes real time" and then sleeping past the grace does not
 * work: a few hundred members enumerate well inside the grace-plus-a-second, so the publish lands after the settle
 * and is folded normally by its own publish. That arrangement was written and believed, and it went red for a
 * reason that is not this defect - the rebuild's RETURN value was one member short while the rollup it left behind
 * was exactly right. The test holds the store's read of the LAST seeded key instead, which is a point at which the
 * walk has provably passed every earlier one, lands the member at a version that sorts first, and carries a vacuity
 * guard that fails if the walk was not actually held.
 *
 * <p>The fold classifies on the member's PUBLISHED INSTANT; the walk's coverage depends on WHEN THE ROW WAS WRITTEN
 * and WHERE ITS KEY SORTS. Those are three different things, and no ordering between them can be recovered after
 * the fact: a member at a key the walk has passed may or may not have had its row there when it passed. The obvious
 * guard - past the grace, fold it yourself rather than declining - trades a lost member for a DOUBLE-folded one,
 * and in an XOR a member folded twice cancels out exactly as one folded never does.
 *
 * <p><strong>So the ambiguity is detected rather than resolved, and the walk is run again.</strong> The stamped
 * object carries a handoff counter beside the boundary, and a fold that declines a member to the walk advances it -
 * in the compare-and-set it was making anyway, so a decline costs a write rather than an object. The rebuild reads
 * the counter after its grace and BEFORE it walks, and {@link #settle} writes only if it is still that value. A
 * decline between those two points is exactly the case neither half provably covers, so the settle is refused and
 * the walk runs again over the same boundary - the second walk REPLACING the first's digest rather than combining
 * with it, so a member both of them saw is still folded once, and one whose row landed before the decline is seen
 * by the second walk for certain (the row committed before the fold, and the fold before the walk began).
 *
 * <p>The counter therefore counts the ambiguous events and nothing else. A decline during the grace is before the
 * counter is read and costs nothing, which is where a well-clocked publisher's declines all land; a decline during
 * the walk means a publish whose instant is at or before a boundary the walk has already outlived by
 * {@value #GRACE_SECONDS} seconds, which is a skewed peer clock or a write that outlived its grace. A storm that
 * keeps producing them exhausts the rebuild's rounds as before, and the walk's own digest is stored as it stands -
 * the bounded drift the next reconcile heals.
 *
 * <p>A second node that finds the rollup stamped waits for the rebuild in flight rather than walking beside it, and
 * takes it over once the stamp is {@value #STALE_MINUTES} minutes old - the shape a crashed rebuild leaves. A rebuild
 * on the node that stamped it is single-flighted in process by {@link StoreRepositoryInventory}.
 */
final class InventoryIdentity {

    private static final System.Logger LOGGER = System.getLogger(InventoryIdentity.class.getName());

    /** The {@code identity/} space's root, and this class is its single composer: the manifest
     *  ({@code InventoryStorageNamespace}) declares this constant instead of re-spelling the literal beside it. */
    static final String ROOT = "identity";

    /** The store key the rollup accumulator lives under - one small fixed-width object per repository scope. */
    static final String KEY = ROOT + "/rollup";

    /** The digest width in bytes (SHA-256). Package-private so {@link StoreRepositoryInventory#rebuildIdentity} sizes
     *  its fold accumulator to the same width without repeating the constant. */
    static final int WIDTH = 32;

    /** The rebuild boundary's width: epoch milliseconds, big-endian. */
    private static final int STAMP = Long.BYTES;

    /** The handoff counter's width: how many folds have declined a member to the rebuild in flight since it stamped
     *  the rollup, which is what tells its walk that it cannot settle over the enumeration it just made. */
    private static final int HANDOFFS = Integer.BYTES;

    /** Where that counter sits: after the folds-since accumulator and the boundary. */
    private static final int HANDOFFS_AT = WIDTH + STAMP;

    /** A rollup with a rebuild in flight and no settled value before it: the folds since the boundary, the boundary,
     *  then the handoff counter. */
    private static final int IN_FLIGHT = HANDOFFS_AT + HANDOFFS;

    /** A rollup with a rebuild in flight over a settled value: the folds since the boundary, the boundary, then the
     *  settled value readers answer from until the rebuild settles. */
    private static final int IN_FLIGHT_OVER_SETTLED = IN_FLIGHT + WIDTH;

    /** How far past its own clock a rebuild sets its boundary, so a publisher on a peer whose clock runs ahead by
     *  less than this still lands on the same side of it. */
    static final long ALLOWANCE_SECONDS = 1;

    /** How long after the boundary the walk begins: a publish stamped at or before the boundary has this long to land
     *  its member, after which the walk is what folds it. */
    static final long GRACE_SECONDS = 2;

    /** A rebuild stamp older than this is a crashed rebuild's; the next one takes it over instead of waiting. */
    static final long STALE_MINUTES = 10;

    /** How often a node waiting for a peer's rebuild re-reads the rollup. */
    private static final Duration POLL = Duration.ofMillis(200);

    private static final byte[] ZERO = new byte[WIDTH];

    private final ArtifactStore store;

    InventoryIdentity(ArtifactStore store) {
        this.store = store;
    }

    /** The current accumulator, or empty when none can be answered for this repository scope yet: nothing has been
     *  built, a rebuild that found nothing settled is in flight, or the object is of no width this class writes (a
     *  corrupt one reads as absent, so the next read rebuilds it). A rebuild in flight over a settled value answers
     *  that value with the folds since its boundary applied - the maintained identity, as before the rebuild began. */
    Optional<byte[]> current() throws IOException {
        Optional<byte[]> content = store.readVersioned(KEY).map(ArtifactStore.Versioned::content);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = content.get();
        if (bytes.length == WIDTH) {
            return content;
        }
        if (bytes.length == IN_FLIGHT_OVER_SETTLED) {
            byte[] visible = Arrays.copyOfRange(bytes, IN_FLIGHT, IN_FLIGHT_OVER_SETTLED);
            for (int index = 0; index < WIDTH; index++) {
                visible[index] ^= bytes[index];
            }
            return Optional.of(visible);
        }
        return Optional.empty();
    }

    /** Fold a member's contribution into the accumulator - a coordinate joined the published set at {@code published}. */
    void foldIn(byte[] member, Instant published) throws IOException {
        combine(ZERO, member, published);
    }

    /** Fold a member's contribution out of the accumulator - a coordinate published at {@code published} left the set. */
    void foldOut(byte[] member, Instant published) throws IOException {
        combine(member, ZERO, published);
    }

    /** Replace a member's {@code previous} contribution with its {@code next} one - the declared-license re-fold of a
     *  member published at {@code published}. */
    void refold(byte[] previous, byte[] next, Instant published) throws IOException {
        combine(previous, next, published);
    }

    /** XOR {@code out} away and {@code in} into the accumulator in one compare-and-set read-modify-write, retried
     *  with backoff. A no-op while no accumulator exists: it is built in full on first read, so folding a lone delta
     *  onto an implicitly-empty accumulator would leave it reflecting only that delta rather than the whole published
     *  set. While a rebuild is in flight the fold goes to the folds-since accumulator the rebuild settles with, unless
     *  the member was published at or before the rebuild's boundary - then the walk is what folds it, and this fold
     *  advances the stamped object's handoff counter instead of its accumulator, so a walk that cannot know whether
     *  it passed the member's key can at least know that a member was handed to it while it ran.
     *
     *  <p>A compare-and-set that keeps losing must NOT fail the publish or eviction it rides: the identity is a
     *  derived, revalidatable cache. It used to leave the fold "to the reconcile rebuild" and return, and until that
     *  pass ran the accumulator that tags the SBOM, the NOTICE and every conditional read was missing this member,
     *  so a client was told "not modified" after a publish had landed. So the rollup is dropped instead: the next read
     *  finds it absent and rebuilds it from truth, single-flighted, which costs that read one walk and costs nobody a
     *  stale answer. A genuine store {@link IOException} still propagates (the store is down; the primary write would
     *  have failed too). */
    private void combine(byte[] out, byte[] in, Instant published) throws IOException {
        boolean landed = Retries.tryUpdate(store, KEY, current -> {
            if (current.isEmpty()) {
                return null;                             // never built yet: the lazy build folds the whole set
            }
            byte[] content = current.get().content();
            if (content.length != WIDTH && !inFlight(content)) {
                return null;                             // no width this class writes: reads as absent, rebuilt next
            }
            if (inFlight(content) && published != null && !published.isAfter(boundaryOf(content))) {
                // Handed to the rebuild's walk, and RECORDED rather than assumed: the never-both-never-neither
                // invariant rests on the walk then folding this member, and whether it does depends on where the
                // member's key sorts against an enumeration already in flight - which nothing on this side can
                // know. Advancing the counter is how the settle learns that it cannot know either, and runs the
                // walk again instead of settling over a member neither half covered. The member digest is what the
                // rollup would differ BY, so logging it is what lets a run that reads a wrong rollup say whether
                // this branch is where it went; DEBUG because it is one line per publish during a rebuild.
                Instant boundary = boundaryOf(content);
                LOGGER.log(System.Logger.Level.DEBUG, () -> "the inventory identity fold hands member "
                        + HexFormat.of().formatHex(in) + " to the rebuild's walk: it was published " + published
                        + ", at or before the boundary " + boundary);
                return handed(content);
            }
            byte[] next = content.clone();               // settled: the accumulator; in flight: the folds since
            for (int index = 0; index < WIDTH; index++) {
                next[index] ^= (byte) (out[index] ^ in[index]);
            }
            return next;
        });
        if (landed) {
            return;
        }
        store.delete(KEY);
        LOGGER.log(System.Logger.Level.WARNING, "the inventory identity fold lost its compare-and-set race "
                + Retries.COMPARE_AND_SET + " times; the rollup is dropped so the next read rebuilds it from truth "
                + "rather than serving a stale identity");
    }

    /** One rebuild's claim on the rollup: the boundary its walk folds up to, and the stamp bytes that identify it. */
    record Rebuild(Instant boundary, byte[] stamp) {
    }

    /**
     * Stamp the rollup with a new rebuild's boundary so the folds that land while it walks go where the settle can find
     * them. Over an absent rollup the stamped object carries nothing readers could answer from; over a settled one it
     * carries that value, which readers keep answering from until the settle. Empty when a rebuild is already in flight
     * with a stamp younger than {@value #STALE_MINUTES} minutes - the caller {@linkplain #await waits} for it - or when
     * the stamping compare-and-set lost every retry to concurrent folds; a stale stamp is taken over.
     */
    Optional<Rebuild> begin(Instant now) throws IOException {
        Instant boundary = now.plusSeconds(ALLOWANCE_SECONDS);
        byte[] stamp = ByteBuffer.allocate(STAMP).putLong(boundary.toEpochMilli()).array();
        Rebuild mine = new Rebuild(boundary, stamp);
        // Empty twice over: the decision keeps the key (a rebuild is in flight; wait for it), or every try lost.
        return Retries.tryDecide(store, KEY, current -> {
            if (current.isEmpty()) {
                return Retries.Verdict.write(concat(ZERO, stamp, null), Optional.of(mine));
            }
            byte[] content = current.get().content();
            if (content.length == WIDTH) {
                return Retries.Verdict.write(concat(ZERO, stamp, content), Optional.of(mine));
            }
            if (inFlight(content)) {
                if (now.isBefore(boundaryOf(content).plus(Duration.ofMinutes(STALE_MINUTES)))) {
                    return Retries.Verdict.keep(Optional.<Rebuild>empty());   // in flight elsewhere: wait for it
                }
                LOGGER.log(System.Logger.Level.WARNING, "taking over an inventory identity rebuild stamped "
                        + boundaryOf(content) + " that never settled");
                return Retries.Verdict.write(concat(ZERO, stamp, visibleOf(content)), Optional.of(mine));
            }
            return Retries.Verdict.write(concat(ZERO, stamp, null), Optional.of(mine));   // no width of ours
        }).map(Retries.Verdict::result).orElse(Optional.empty());
    }

    /** Wait out the grace between the boundary and the walk, so a publish stamped before the boundary has landed. */
    void grace() throws IOException {
        try {
            Thread.sleep(Duration.ofSeconds(GRACE_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted before the inventory identity walk");
        }
    }

    /**
     * The settled rollup once a rebuild in flight elsewhere settles it: re-read every {@link #POLL} until the object is
     * settled (answered), absent (empty: nobody is rebuilding, the caller begins one), or its stamp has gone stale
     * (empty: the caller's {@link #begin} takes it over). Bounded by the stale horizon, so a crashed peer never holds a
     * reader longer than that.
     */
    Optional<byte[]> await(Instant now) throws IOException {
        Instant deadline = now.plus(Duration.ofMinutes(STALE_MINUTES));
        while (true) {
            Optional<byte[]> content = store.readVersioned(KEY).map(ArtifactStore.Versioned::content);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            if (content.get().length == WIDTH) {
                return content;
            }
            if (!inFlight(content.get()) || Clocks.now().isAfter(deadline)
                    || !Clocks.now().isBefore(boundaryOf(content.get()).plus(Duration.ofMinutes(STALE_MINUTES)))) {
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for an inventory identity rebuild");
            }
        }
    }

    /**
     * How many folds have declined a member to {@code rebuild}'s walk since it stamped the rollup, or empty when the
     * object is no longer this rebuild's. The rebuild reads this after its grace and before it walks, and hands the
     * value back to {@link #settle}, which refuses to settle over a walk the counter moved under: a decline that
     * lands while the walk runs is the one case where neither half provably folded the member.
     */
    Optional<Integer> handedOver(Rebuild rebuild) throws IOException {
        return store.readVersioned(KEY).map(ArtifactStore.Versioned::content)
                .filter(content -> mine(content, rebuild))
                .map(InventoryIdentity::handoffsOf);
    }

    /**
     * Settle {@code rebuild}: the walk's digest over every member published at or before its boundary, XOR the folds
     * that landed since, written over the stamped object in one compare-and-set and retried against folds that land
     * meanwhile. Empty when the object is no longer this rebuild's - dropped by an exhausted fold, or stamped over by
     * a peer that judged it stale - when a fold declined a member to the walk while it was running ({@code handed} is
     * what {@link #handedOver} answered before the walk, and a different value now means the walk may have passed
     * that member's key before its row was written), or when the settle lost every retry. The caller walks again, or
     * runs another round.
     */
    Optional<byte[]> settle(Rebuild rebuild, byte[] walked, int handed) throws IOException {
        // Empty twice over: the object is no longer this rebuild's or was handed a member mid-walk (kept as it is),
        // or every try lost.
        return Retries.tryDecide(store, KEY, current -> {
            if (current.isEmpty() || !mine(current.get().content(), rebuild)
                    || handoffsOf(current.get().content()) != handed) {
                return Retries.Verdict.keep(Optional.<byte[]>empty());
            }
            byte[] settled = Arrays.copyOf(current.get().content(), WIDTH);
            for (int index = 0; index < WIDTH; index++) {
                settled[index] ^= walked[index];
            }
            return Retries.Verdict.write(settled, Optional.of(settled));
        }).map(Retries.Verdict::result).orElse(Optional.empty());
    }

    /** Store a freshly recomputed accumulator, overwriting whatever is there - the last resort of a rebuild that lost
     *  every round to concurrent publishes, so the identity is at least the walk's truth rather than absent, with the
     *  folds it may miss healed by the next reconcile. A persistent conflict gives up quietly, as {@link #combine}
     *  does, rather than failing the caller. */
    void set(byte[] accumulator) throws IOException {
        Retries.tryUpdate(store, KEY, _ -> accumulator);
    }

    private static boolean inFlight(byte[] content) {
        return content.length == IN_FLIGHT || content.length == IN_FLIGHT_OVER_SETTLED;
    }

    private static Instant boundaryOf(byte[] content) {
        return Instant.ofEpochMilli(ByteBuffer.wrap(content, WIDTH, STAMP).getLong());
    }

    /** Whether a stored object is {@code rebuild}'s own stamped one: in flight, carrying the boundary it wrote. */
    private static boolean mine(byte[] content, Rebuild rebuild) {
        return inFlight(content) && Arrays.equals(content, WIDTH, HANDOFFS_AT, rebuild.stamp(), 0, STAMP);
    }

    private static int handoffsOf(byte[] content) {
        return ByteBuffer.wrap(content, HANDOFFS_AT, HANDOFFS).getInt();
    }

    /** The same stamped object with its handoff counter advanced - what a fold writes instead of folding, when the
     *  member belongs to the rebuild's walk. */
    private static byte[] handed(byte[] content) {
        byte[] next = content.clone();
        ByteBuffer.wrap(next, HANDOFFS_AT, HANDOFFS).putInt(handoffsOf(content) + 1);
        return next;
    }

    /** What readers answer from an in-flight object: the settled value it replaced with the folds since applied, or
     *  {@code null} when it replaced nothing. */
    private static byte[] visibleOf(byte[] content) {
        if (content.length != IN_FLIGHT_OVER_SETTLED) {
            return null;
        }
        byte[] visible = Arrays.copyOfRange(content, IN_FLIGHT, IN_FLIGHT_OVER_SETTLED);
        for (int index = 0; index < WIDTH; index++) {
            visible[index] ^= content[index];
        }
        return visible;
    }

    private static byte[] concat(byte[] since, byte[] stamp, byte[] settled) {
        ByteBuffer buffer = ByteBuffer.allocate(settled == null ? IN_FLIGHT : IN_FLIGHT_OVER_SETTLED);
        buffer.put(since).put(stamp).putInt(0);          // a freshly stamped rebuild has been handed nothing yet
        if (settled != null) {
            buffer.put(settled);
        }
        return buffer.array();
    }

    /** A version's member digest: the SHA-256 over its neutral coordinate triple and its declared-license
     *  fingerprint, distinguishing an <em>absent</em> license sidecar (never inspected) from a <em>present-but-empty</em>
     *  one (inspected, none declared) so a later inspection re-folds the member. Every mutation point and the rebuild
     *  compute the member through this one definition, over the exact stored sidecar bytes, so an incremental fold and
     *  a full recompute agree. */
    static byte[] member(String ecosystem, String coordinate, String version, Optional<byte[]> licenseSidecar) {
        MessageDigest digest = sha256();
        update(digest, ecosystem);
        update(digest, coordinate);
        update(digest, version);
        if (licenseSidecar.isPresent()) {
            digest.update((byte) 1);
            digest.update(licenseSidecar.get());
        } else {
            digest.update((byte) 0);
        }
        return digest.digest();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);                         // a length-delimiting separator, so the fields never smear
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);          // SHA-256 is a required JDK algorithm
        }
    }
}
