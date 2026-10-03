package build.jenesis.repository.outbox;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.Names;

/**
 * A durable, retrying delivery queue over the store: an active namespace the drain scans and a parked namespace for
 * terminal failures, every transition a compare-and-set on the store's version token.
 *
 * <p>The protocol reads four things from an entry - its identity, whether it is parked, when it parked, and how to
 * unpark it - and {@link Entry} names exactly those, so a user's record carries whatever cargo it likes and a caller
 * reading a {@link Window} or a {@link Queued} gets its own record back.
 *
 * <p>A terminally failed entry is <em>moved</em> to {@code parked} rather than flagged in place, so the drain stops
 * re-reading it while it stays retrievable for an operator's retry and visible on a status surface.
 *
 * <h2>The token discipline</h2>
 *
 * <p>The drain reads every entry with its token ({@link #queued}) and commits each result - progress
 * ({@link #update(Entry, Object)}), a park ({@link #park(Entry, Object)}), a drop ({@link #removeDelivered}) - only if
 * that token still stands. A {@code false} means a rival replaced the entry mid-pass (a re-publish, an operator's
 * unpark), so the drain drops its result and the rival's entry is picked up whole next pass. A park writes its parked
 * copy before removing the active one, and undoes it if the conditional removal loses, so an entry is never lost
 * between namespaces and a rival is never shadowed by a park of the copy it superseded.
 *
 * @param <E> the user's entry type, which carries the cargo and the delivery bookkeeping
 */
public class Outbox<E extends Outbox.Entry<E>> {

    /**
     * What the protocol needs from an entry, and all it reads.
     *
     * @param <E> the implementing record itself, so {@link #unparked} hands back the user's own type
     */
    public interface Entry<E extends Entry<E>> {

        /** The entry's identity, stable across its life; what a caller unparks or removes it by. */
        String id();

        /** Whether the entry has failed terminally and sits in the parked backlog. */
        boolean parked();

        /** When the entry parked, in epoch millis, or {@code 0} if never - what the backlog's retention judges age by.
         *  The park instant rather than the entry's own, since an entry can retry for weeks before it parks. */
        long parkedAtMillis();

        /** This entry unparked for another try: attempts and backoff cleared, the park lifted, delivery progress kept,
         *  so a retry re-sends only to whoever never took it. */
        E unparked();
    }

    /**
     * How a user's entry is stored: its bytes both ways, and the object name its id lives under.
     *
     * @param <E> the user's entry type
     */
    public interface Codec<E> {

        byte[] serialise(E entry);

        E parse(byte[] content);

        /** The store object name for an id: the id itself when it is already a safe name, a digest when it is a served
         *  path. Both namespaces key by this, so their pages share one order and merge into a true window. */
        default String name(String id) {
            return id;
        }
    }

    /** An active entry together with the compare-and-set token it was read at - what the drain commits against. */
    public record Queued<E>(E entry, Object token) {
    }

    /** A bounded window of entries in name order across both namespaces, whether more exist, and the cursor to pass as
     *  the next {@code after} - empty when the window is. */
    public record Window<E>(List<E> entries, boolean more, String next) {
    }

    private final ArtifactStore store;
    private final String active;
    private final String parked;
    private final Codec<E> codec;

    /**
     * @param store  the store, already scoped to the repository whose queue this is
     * @param active the prefix the active queue lives under
     * @param parked the prefix the parked backlog lives under
     * @param codec  how an entry is stored
     */
    protected Outbox(ArtifactStore store, String active, String parked, Codec<E> codec) {
        this.store = Objects.requireNonNull(store, "store");
        this.active = Objects.requireNonNull(active, "active");
        this.parked = Objects.requireNonNull(parked, "parked");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** The first {@code limit} entries after {@code after} in name order, across the active queue and the parked
     *  backlog, and whether more exist. While a target is failing every publish adds a parked entry, so the repository
     *  where the screen matters most is the one whose whole set is unbounded. A crash between a park's two writes can
     *  leave a copy in both namespaces; it is deduped here with the parked copy winning. */
    public Window<E> entries(String after, int limit) throws IOException {
        String start = after == null ? "" : after;
        Map<String, E> byName = new TreeMap<>();
        boolean more = false;
        for (String root : List.of(active, parked)) {
            List<String> names = new ArrayList<>();
            store.page(root, start, ArtifactStore.oneMoreThan(limit), names::add);
            more |= names.size() > limit;
            for (String name : names.subList(0, Math.min(names.size(), limit))) {
                Optional<ArtifactStore.Versioned> stored = store.readVersioned(root + "/" + name);
                if (stored.isPresent()) {
                    byName.put(name, codec.parse(stored.get().content()));   // the parked copy, read second, wins
                }
            }
        }
        List<Map.Entry<String, E>> merged = new ArrayList<>(byName.entrySet());
        more |= merged.size() > limit;
        if (merged.size() > limit) {
            merged = merged.subList(0, limit);
        }
        return new Window<>(merged.stream().map(Map.Entry::getValue).toList(), more,
                merged.isEmpty() ? "" : merged.getLast().getKey());
    }

    /** Every entry - active and parked - in a stable order by id, a crash-left duplicate deduped with the parked copy
     *  winning. <b>Off the request path only</b>: the set grows with every publish while a target fails, so a screen
     *  takes {@link #entries(String, int)} and the drain {@link #queued}. */
    public List<E> entries() throws IOException {
        Map<String, E> byId = new LinkedHashMap<>();
        for (Queued<E> queued : queued()) {
            byId.put(queued.entry().id(), queued.entry());
        }
        Names names = Names.over(store, parked);
        for (String name = names.next(); name != null; name = names.next()) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(parked + "/" + name);
            if (stored.isPresent()) {
                E entry = codec.parse(stored.get().content());
                byId.put(entry.id(), entry);                                // the parked copy wins
            }
        }
        List<E> entries = new ArrayList<>(byId.values());
        entries.sort(Comparator.comparing(Entry::id));
        return entries;
    }

    /** The active queue alone, in id order - the parked backlog is out of the scan by construction. */
    public List<E> active() throws IOException {
        return queued().stream().map(Queued::entry).toList();
    }

    /** The active queue with each entry's read token, in id order - what the drain scans, so each commit can detect a
     *  rival. */
    public List<Queued<E>> queued() throws IOException {
        List<Queued<E>> queued = new ArrayList<>();
        for (String name : store.list(active)) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(active + "/" + name);
            if (stored.isPresent()) {
                queued.add(new Queued<>(codec.parse(stored.get().content()), stored.get().token()));
            }
        }
        queued.sort(Comparator.comparing(queued1 -> queued1.entry().id()));
        return queued;
    }

    /** Whether an entry with {@code id} exists in either namespace - two key lookups, never a listing. */
    public boolean names(String id) throws IOException {
        return store.readVersioned(activeKey(id)).isPresent() || store.readVersioned(parkedKey(id)).isPresent();
    }

    /** How many entries sit in the parked backlog - a cheap listing that reads no object. */
    public int parkedCount() {
        return store.list(parked).size();
    }

    /** How many entries sit in the active queue - the same cheap listing, for a depth diagnostic. */
    public int queuedCount() {
        return store.list(active).size();
    }

    /**
     * Apply the parked backlog's retention: drop terminal failures older than {@code maxAge} and, with a positive
     * {@code maxCount}, everything beyond the newest {@code maxCount}. A permanently gone target parks one entry per
     * publish for ever, so the backlog needs bounds; both dials are honoured together.
     *
     * <p>Age is each entry's own park instant, since an entry can retry for weeks before it parks. An entry with no
     * park instant is never aged out - nothing is deleted that cannot be judged - but the cap still bounds it, and
     * unstamped entries sort last so the cap evicts them first.
     *
     * @return how many were removed; idempotent, so a repeat pass converges to the same backlog
     */
    public int prunePark(Instant now, Duration maxAge, int maxCount) throws IOException {
        if (maxAge == null && maxCount <= 0) {
            return 0;
        }
        long cutoff = maxAge == null ? Long.MIN_VALUE : now.minus(maxAge).toEpochMilli();
        record Aged(String name, long at) {
        }
        List<Aged> aged = new ArrayList<>();
        Names names = Names.over(store, parked);
        for (String name = names.next(); name != null; name = names.next()) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(parked + "/" + name);
            if (stored.isEmpty()) {
                continue;   // raced with an unpark or a peer's prune; gone either way
            }
            aged.add(new Aged(name, codec.parse(stored.get().content()).parkedAtMillis()));
        }
        aged.sort(Comparator.comparingLong(Aged::at).reversed());
        int removed = 0;
        int position = 0;
        for (Aged entry : aged) {
            int index = position++;
            boolean overCap = maxCount > 0 && index >= maxCount;
            boolean tooOld = entry.at() > 0 && entry.at() < cutoff;
            if (overCap || tooOld) {
                store.delete(parked + "/" + entry.name());
                removed++;
            }
        }
        return removed;
    }

    /** Queue or replace an entry under its id by compare-and-set, so concurrent producers never lose one another; the
     *  latest entry at an id wins, resetting its bookkeeping. */
    public void record(E entry) throws IOException {
        byte[] body = codec.serialise(entry);
        Retries.update(store, activeKey(entry.id()), current -> body);
    }

    /** Commit an updated entry last-writer-wins, for a caller holding no read token, such as {@link #unpark}; the drain
     *  uses {@link #update(Entry, Object)}. */
    public void update(E entry) throws IOException {
        record(entry);
    }

    /** Commit the drain's updated entry only if the active object still holds {@code token}; {@code false} means a
     *  rival replaced it and the drain's result is dropped. */
    public boolean update(E entry, Object token) throws IOException {
        return store.writeVersioned(activeKey(entry.id()), codec.serialise(entry), token);
    }

    /** {@link #park(Entry, Object)} guarded on the active copy's current token, for a caller that did not read the
     *  entry through {@link #queued}; the drain uses the token it read, which closes the drain-versus-retry race. */
    public boolean park(E entry) throws IOException {
        return park(entry, store.readVersioned(activeKey(entry.id()))
                .map(ArtifactStore.Versioned::token).orElse(null));
    }

    /**
     * Move a terminally failed entry from the active queue to the parked backlog, removing the active copy only if it
     * still holds {@code token}. The parked copy is written first, so a crash between the two leaves the entry in both
     * namespaces - deduped on read, re-parked next pass - never in neither. Then the active copy is one of three:
     * <ul>
     *   <li>still at {@code token}: removed, and the transition landed;</li>
     *   <li><em>absent</em> - a direct park of an entry never queued, or already removed: the parked copy stands;</li>
     *   <li><em>replaced</em> by a rival's fresh copy (a re-publish, an unpark): the park is undone - the parked copy
     *       is dropped unless a racing unpark already took it - and the rival's copy survives to be retried.</li>
     * </ul>
     *
     * @return whether the active-to-parked transition landed - a {@code false} is not a transition to meter
     */
    public boolean park(E entry, Object token) throws IOException {
        recordParked(entry);
        String key = activeKey(entry.id());
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        if (current.isEmpty()) {
            return false;                                   // nothing to move; the parked copy stands
        }
        if (Objects.equals(current.get().token(), token)) {
            store.delete(key);                              // still the copy the drain read at token
            return true;
        }
        String parkedCopy = parkedKey(entry.id());
        if (store.readVersioned(parkedCopy).isPresent()) {
            store.delete(parkedCopy);                       // undone: a rival owns the active copy now
        }
        return false;
    }

    /**
     * Unpark the entry with {@code id} so the next drain retries it, keeping its delivery progress. If a rival has
     * already landed a fresh active entry at the id, that one is current and the stale parked twin is dropped rather
     * than moved over it. A parked entry found in the active queue is reset in place.
     *
     * @return {@code false} when nothing parked is queued at {@code id}, so a retry endpoint can report a miss
     */
    public boolean unpark(String id) throws IOException {
        String key = parkedKey(id);
        Optional<ArtifactStore.Versioned> parkedCopy = store.readVersioned(key);
        Optional<ArtifactStore.Versioned> activeCopy = store.readVersioned(activeKey(id));
        if (parkedCopy.isPresent()) {
            if (activeCopy.isPresent()) {
                E current = codec.parse(activeCopy.get().content());
                if (current.parked()) {
                    record(current.unparked());
                }
                store.delete(key);
                return true;
            }
            record(codec.parse(parkedCopy.get().content()).unparked());
            store.delete(key);
            return true;
        }
        if (activeCopy.isEmpty()) {
            return false;
        }
        E current = codec.parse(activeCopy.get().content());
        if (!current.parked()) {
            return false;
        }
        record(current.unparked());
        return true;
    }

    /** Drop an entry last-writer-wins from whichever namespace holds it. The drain uses {@link #removeDelivered}, so it
     *  never drops a rival that replaced an entry mid-pass. */
    public void remove(String id) throws IOException {
        for (String key : List.of(activeKey(id), parkedKey(id))) {
            if (store.readVersioned(key).isPresent()) {
                store.delete(key);
            }
        }
    }

    /** Drop the active entry only if it still holds {@code token}. The store has no conditional delete, so this
     *  re-reads and matches the token first, leaving only the store's own narrow window. Active object only - what
     *  {@link #park} uses after writing the parked copy. */
    public boolean remove(String id, Object token) throws IOException {
        String key = activeKey(id);
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty() || !Objects.equals(stored.get().token(), token)) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Drop a delivered active entry on {@code token} and, when that lands, any parked twin at the same id - a
     *  superseded copy a later retry would otherwise resurrect. */
    public boolean removeDelivered(String id, Object token) throws IOException {
        if (!remove(id, token)) {
            return false;
        }
        String key = parkedKey(id);
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
        return true;
    }

    /** What a drain's {@link #settle} did with an entry. */
    public enum Settled {
        /** Every target took it, and it is gone from the queue. */
        DELIVERED,
        /** It failed terminally and moved to the parked backlog. */
        PARKED,
        /** It stays queued for another pass, its progress recorded. */
        QUEUED,
        /** A rival replaced it mid-pass, so this pass's result was dropped; the rival stays queued and is picked up
         *  whole next pass. */
        SUPERSEDED
    }

    /**
     * Commit one drained entry's outcome against the token it was read at: dropped when {@code complete} (every
     * target took it), moved to the parked backlog when {@code updated} has parked, and otherwise its progress
     * recorded - each compare-and-set, so stale progress never overwrites a rival a re-publish or an operator's unpark
     * wrote meanwhile.
     */
    public Settled settle(Queued<E> item, E updated, boolean complete) throws IOException {
        if (complete) {
            return removeDelivered(item.entry().id(), item.token()) ? Settled.DELIVERED : Settled.SUPERSEDED;
        }
        if (updated.parked()) {
            return park(updated, item.token()) ? Settled.PARKED : Settled.SUPERSEDED;
        }
        if (!updated.equals(item.entry()) && !update(updated, item.token())) {
            return Settled.SUPERSEDED;
        }
        return Settled.QUEUED;
    }

    /**
     * The delivery state after a failed attempt: one attempt more, the backoff doubled from {@code baseMillis} up to
     * {@code capMillis}, and parked at the attempt cap. The park instant is stamped on the transition and then
     * carried, so a rewrite never resets the backlog's retention window.
     */
    public record Failed(int attempts, long nextAttemptMillis, boolean parked, long parkedAtMillis) {

        public static Failed after(int attempts, boolean parked, long parkedAtMillis, long nowMillis, long baseMillis,
                                   long capMillis, int maxAttempts) {
            int next = attempts + 1;
            long backoff = Math.min(capMillis, baseMillis * (1L << Math.min(next - 1, 20)));
            boolean parking = next >= maxAttempts;
            long parkedAt = parking ? (parked && parkedAtMillis > 0 ? parkedAtMillis : nowMillis) : 0L;
            return new Failed(next, nowMillis + backoff, parking, parkedAt);
        }
    }

    private void recordParked(E entry) throws IOException {
        byte[] body = codec.serialise(entry);
        Retries.update(store, parkedKey(entry.id()), current -> body);
    }

    private String activeKey(String id) {
        return active + "/" + codec.name(id);
    }

    private String parkedKey(String id) {
        return parked + "/" + codec.name(id);
    }
}
