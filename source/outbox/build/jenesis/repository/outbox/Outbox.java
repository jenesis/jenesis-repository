package build.jenesis.repository.outbox;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.Names;

/**
 * A durable, retrying delivery queue over the store: an active namespace the drain scans and a parked namespace
 * for terminal failures, every transition compare-and-set on the store's version token.
 *
 * <h2>The bound, and what it promises</h2>
 *
 * <p>The protocol reads four things from an entry - its identity, whether it is parked, when it parked, and how to
 * unpark it - and nothing else. {@link Entry} names exactly those, so a user's record carries whatever cargo it
 * likes (a path and a blob hash; an event and its detail) and this class never looks at it. The type parameter is
 * earned by that real dependency and bounded to it; a caller reading a {@link Window} or a {@link Queued} gets the
 * user's own record back, not a wrapper.
 *
 * <h2>What the two namespaces are for</h2>
 *
 * <p>A terminally-failed entry is <em>moved</em> to {@code parked} rather than flagged in place, so the per-pass
 * drain scan stops re-listing and re-reading it while it stays retrievable for an operator's retry and visible on a
 * status surface. Without the move a dead target's entries accumulate in the active queue and every drain re-scans
 * them all against a target that will never take them.
 *
 * <h2>The token discipline</h2>
 *
 * <p>The drain reads every entry with the token it was stored at ({@link #queued}) and commits each result -
 * progress ({@link #update(Entry, Object)}), a park ({@link #park(Entry, Object)}), a drop
 * ({@link #removeDelivered}) - only if that token still stands. A {@code false} means a rival replaced the entry
 * mid-pass: a re-publish that landed fresh bytes, or an operator's unpark that reset the bookkeeping. Writing the
 * drain's stale result over it would lose the rival's delivery, so the drain drops its result instead and the
 * rival's entry is picked up whole next pass. The park is the delicate one: its parked copy is written
 * <em>before</em> the active copy is removed and undone if the conditional removal loses, so an entry is never lost
 * between the two namespaces and a rival is never shadowed by a park of the copy it superseded.
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

        /** When the entry parked, as epoch millis, or {@code 0} when it never has - what the backlog's retention
         *  judges age by, and deliberately the park instant rather than the entry's own, since an entry can retry for
         *  weeks before it parks. */
        long parkedAtMillis();

        /** This entry unparked for another try: attempts and backoff cleared, the park lifted, and whatever delivery
         *  progress the user tracks kept, so a retry re-sends only to whoever never took it. */
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

        /** The store object name for an id. The id itself when it is already a safe name; a digest of it when it is
         *  a served path, which can carry any character. Both namespaces key by this, so a page of each is in the
         *  same order and merging them yields a true window rather than an approximation. */
        default String name(String id) {
            return id;
        }
    }

    /** An active entry together with the compare-and-set token it was read at - what the drain commits against. */
    public record Queued<E>(E entry, Object token) {
    }

    /** A bounded window of entries in name order across both namespaces, whether more exist beyond it, and the
     *  cursor to pass as the next {@code after} - empty when the window itself is. */
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

    /**
     * The first {@code limit} entries after {@code after} in name order, taken across the active queue and the parked
     * backlog together, and whether more exist - never the whole outbox read and sorted to draw one panel.
     *
     * <p>The parked backlog is why this matters rather than being a tidy-up: while a target is failing every publish
     * adds an entry that is never drained, so the repository where an operator most needs the screen is exactly the
     * one where the whole set is unbounded. A crash between a park's two writes can leave a copy in both namespaces;
     * such a name is deduped here with the parked copy winning as the terminal one, which is what a reviewer of a
     * stuck delivery needs to see.
     */
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

    /**
     * Every entry this repository holds - the active queue and the parked backlog - in a stable order by id, for a
     * caller that genuinely must see all of them. A crash-left duplicate is deduped with the parked copy winning.
     *
     * <p><b>Off the request path only.</b> The set is unbounded and grows with every publish for as long as a target
     * is failing; a screen or an API answer takes {@link #entries(String, int)}, and the drain takes {@link #queued}.
     */
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

    /** The active queue, each entry paired with the token it was read at, in id order - what the drain scans, so its
     *  every commit on a scanned entry can detect a rival and refuse to clobber it. */
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

    /** Whether an entry with {@code id} exists in either namespace - two key lookups, never a listing, so a caller
     *  can ask "could this still ship?" without scanning the queue. */
    public boolean names(String id) throws IOException {
        return store.readVersioned(activeKey(id)).isPresent() || store.readVersioned(parkedKey(id)).isPresent();
    }

    /** How many entries sit in the parked backlog - a cheap listing that reads no object. */
    public int parkedCount() {
        return store.list(parked).size();
    }

    /** How many entries sit in the active queue - the same cheap listing, for a diagnostic that wants the depth
     *  without the contents. */
    public int queuedCount() {
        return store.list(active).size();
    }

    /**
     * Apply the parked backlog's retention: drop terminal failures older than {@code maxAge} and, with a positive
     * {@code maxCount}, everything beyond the newest {@code maxCount}.
     *
     * <p>Parking fixes the cost per pass; it does not end an entry's life. A target that is permanently gone parks
     * one entry per publish for ever, which is a queue that only grows, driven by traffic rather than by anything an
     * operator did. How long a dead delivery is worth keeping is a deployment's call, so both dials are honoured
     * together: age as the default bound, a count cap for a hard ceiling regardless of rate.
     *
     * <p>Age is each entry's own recorded park instant, not its id or its creation: an entry can retry for weeks
     * before it parks, and judging by anything earlier reclaimed entries on the very first pass after they parked. An
     * entry that carries no park instant is never aged out - never delete what cannot be judged - but the cap still
     * bounds it, and unstamped entries sort last so the cap evicts them first, the right bias for what nothing can
     * say an age about.
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

    /** Queue (or replace) an entry under its id, compare-and-set so concurrent producers never lose one another; the
     *  latest entry at an id wins, resetting its bookkeeping. */
    public void record(E entry) throws IOException {
        byte[] body = codec.serialise(entry);
        Retries.update(store, activeKey(entry.id()), current -> body);
    }

    /** Commit an updated entry last-writer-wins. The drain uses {@link #update(Entry, Object)} instead so a rival is
     *  never clobbered; this form is for a caller that holds no read token, such as {@link #unpark}. */
    public void update(E entry) throws IOException {
        record(entry);
    }

    /** Commit the drain's updated entry only if the active object still holds {@code token}. A {@code false} means a
     *  rival replaced it mid-pass and the drain's result must be dropped, not written over the rival's. */
    public boolean update(E entry, Object token) throws IOException {
        return store.writeVersioned(activeKey(entry.id()), codec.serialise(entry), token);
    }

    /** {@link #park(Entry, Object)} guarded on the active copy's current token, for a caller that did not read the
     *  entry through {@link #queued}. The drain uses the token it read the entry at, which is what actually closes
     *  the drain-versus-retry race. */
    public boolean park(E entry) throws IOException {
        return park(entry, store.readVersioned(activeKey(entry.id()))
                .map(ArtifactStore.Versioned::token).orElse(null));
    }

    /**
     * Move a terminally-failed entry out of the active queue into the parked backlog, removing the active copy only
     * if it still holds {@code token}.
     *
     * <p>The parked copy is written first and the active one removed second, so a crash between them leaves the
     * entry in both namespaces - deduped on read, re-parked next pass - rather than in neither. Three things can then
     * be true of the active copy, and they are deliberately not two:
     * <ul>
     *   <li>it still holds {@code token} - the copy the drain read: it is removed, and the transition landed;</li>
     *   <li>it is <em>absent</em> - a direct park of an entry that was never queued, or one already removed: there
     *       is nothing to move, and the parked copy stands. Undoing here would make a direct park a no-op, which is
     *       the drift the extraction found: one of the two users had that case right and the other had folded it
     *       into the next one;</li>
     *   <li>it was <em>replaced</em> - a rival wrote a fresh, immediately-eligible copy mid-pass, a re-publish or an
     *       operator's unpark: deleting that would lose its delivery, so the park is undone instead - the parked copy
     *       just written is dropped (unless a racing unpark already took it) and the rival's copy is left the sole
     *       survivor, retried rather than shadowed.</li>
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
     * Unpark the entry with {@code id} so the next drain retries it, keeping its delivery progress.
     *
     * <p>It moves from the parked backlog back into the active queue. When a rival has already landed a fresh
     * <em>active</em> entry at the same id while an older one sat parked, the active entry is the current one and
     * wins: the stale parked twin is dropped rather than moved over it, which would overwrite the newer delivery with
     * the superseded one. A parked entry still sitting in the active queue - a leftover from before parked entries
     * were moved out of the scan - is reset in place, so an upgrade self-heals.
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

    /** Drop an entry last-writer-wins from whichever namespace holds it. The drain uses {@link #removeDelivered} for
     *  an entry it just delivered, so it never drops a rival that replaced it mid-pass. */
    public void remove(String id) throws IOException {
        for (String key : List.of(activeKey(id), parkedKey(id))) {
            if (store.readVersioned(key).isPresent()) {
                store.delete(key);
            }
        }
    }

    /** Drop the active entry only if it still holds {@code token}. The store has no compare-and-set delete, so this
     *  re-reads and matches the token before deleting; the residual window is the store's own, far narrower than
     *  deleting whatever now sits at the key. Removes only the active object - it is what {@link #park} uses after
     *  writing the parked copy. */
    public boolean remove(String id, Object token) throws IOException {
        String key = activeKey(id);
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty() || !Objects.equals(stored.get().token(), token)) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Drop a fully-delivered active entry on {@code token}, and when that lands also any parked twin at the same
     *  id - an earlier copy that terminally failed - since the delivered one supersedes it. Without the second drop
     *  the twin outlives the delivery and a later retry resurrects the superseded copy over the current one. */
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
