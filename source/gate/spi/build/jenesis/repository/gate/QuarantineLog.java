package build.jenesis.repository.gate;


import module java.base;

import build.jenesis.repository.store.Publication;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The record of what the compliance gate held back, over a repository's store. Every artifact the gate quarantines
 * or rejects - whether a first-party upload on the publish path or a third-party artifact on the proxy fetch path -
 * is appended here with its coordinate, the verdict, the reasons behind it, and when it happened, so an operator can
 * see what the gate is catching and why. A rejected artifact stores no bytes of its own, so without this log its
 * refusal would leave no trace at all; a quarantined one is held under the {@code /quarantine} view and this log is
 * the explanation beside it. One small object per event is written under {@code audit/quarantine/}, keyed by time,
 * so concurrent writers never contend on a shared log.
 *
 * <p>The reads sit on request paths, so none scans the whole (unrotated) log. The artifact-detail view wants the
 * <em>latest</em> verdict for a <em>single</em> path, so every record also updates a latest-verdict-by-path index
 * ({@code audit/quarantine-index/<path digest>}) that {@link #latest} reads with one point lookup - the index is derived and
 * best-effort, the append remaining the audit trail. The review queue is <em>not</em> read from this log at all but
 * from the live {@code /quarantine} hold pointers, {@link #reviewQueue} enriching each with {@link #latest} - so a
 * hold whose log row was lost or never landed (a gate enabled over a store that already holds artifacts) still
 * surfaces and stays releasable, the log never being the queue's index. For an audit-style bounded page {@link
 * #events(int)} pages the trail through {@link ArtifactStore#page}, reading only the page's own names and bodies; the
 * unpaged {@link #events()} stays for the audit surfaces that genuinely want the whole trail.
 *
 * <p><b>The object name is the order.</b> Every event object is named
 * {@code <MAX_VALUE - epochMillis, zero-padded to 19>-<path digest>}, so the store's own lexicographic paging
 * enumerates the trail <em>newest first</em> and a bounded read is one forward page with no sort at all. It used to be
 * named by the raw epoch-millis, which meant the only way to serve the newest {@code limit} rows was to materialise
 * and sort the whole name list first - so the <em>paged</em> read cost exactly what the unpaged one does, a page that
 * was only a page in its return type. {@link #prune} pays the same way: it streams the same order rather than listing
 * the trail and the index root entire, twice.
 *
 * <p>The log does not grow without bound: {@link #prune} applies the age/count retention the scheduled cleanup pass
 * drives (like the {@code StoreAuditTrail}'s day retention), and {@link #discarded} removes a path's rows when a
 * reviewer discards its hold - the artifact is gone, so its trail goes with it, while a <em>released</em> path keeps
 * its rows (subject to retention) as the record of what was held and cleared.
 */
public final class QuarantineLog {

    /** The append-only trail's root: one object per gate decision at {@code audit/quarantine/<orderKey>-<digest>}.
     *  This class is the space's single composer - every key under it is built by {@link #eventKey}, and the manifest
     *  ({@code GateStorageNamespace}) declares this constant rather than re-spelling the literal, so a rename moves
     *  the declaration with the keys instead of leaving a manifest entry naming a space nothing writes. */
    public static final String ROOT = "audit/quarantine";

    /** The derived latest-verdict-by-path index's root, a sibling space rather than a child of {@link #ROOT} - a purge
     *  of the trail lists that key's children, which never include this one. Composed only by {@link #indexKey}. */
    public static final String INDEX_ROOT = "audit/quarantine-index";

    /** How many child names one streaming stride holds - the working set of a whole-trail pass, bounded independently
     *  of how long the trail is, so no read or sweep here allocates with the deployment's audit volume. */
    private static final int STRIDE = 256;

    /** How many trail rows {@link #refusals(int)} scans per refusal it is asked for, so a repository whose recent
     *  activity mixes refusals with quarantines still fills the page rather than under-reporting. Bounded either way:
     *  the read pays for the page, never for the trail. */
    private static final int REFUSAL_SCAN_FACTOR = 4;

    /** The reason-token separator in a serialized record, reused rather than recompiled on every {@code parse} (the
     *  quarantine index is re-parsed per QUARANTINE/REJECT record on the publish path and on a detail read). */
    private static final Pattern REASON_SEPARATOR = Pattern.compile(" \\| ");

    private final ArtifactStore store;

    public QuarantineLog(ArtifactStore store) {
        this.store = store;
    }

    /** One gate decision that withheld an artifact: when, the request path and coordinate, the verdict, and why. */
    public record Event(Instant when, String path, String coordinate, Verdict verdict, List<String> reasons) {
    }

    /** One artifact currently held for review: the live hold path, and the gate decision recorded for it when its log
     *  row survives - {@link Optional#empty()} when the row was lost or the log was enabled after the hold, so a
     *  consumer renders a placeholder in its own vocabulary while the hold stays visible and releasable. */
    public record Held(String path, Optional<Event> event) {
    }

    /**
     * The artifacts a repository currently holds for review, keyed off the live {@code /quarantine} hold pointers -
     * the truth that serving reads through - and only <em>enriched</em> from this log: a hold whose log row never
     * landed (the row is the un-contained second write of the gate's {@code committed()} leg, or the log was enabled
     * or pruned after the hold) still appears with no {@link Event}, so the review queue is complete and every hold is
     * releasable rather than held-but-invisible. This is why the review surface reads the queue from here and not from
     * {@link #events(int)}: the log is the audit trail beside the queue, never its index, so a gate switched on over a
     * store that already holds artifacts, or one whose log rows aged out, still surfaces every pending hold. Newest
     * decision first, any log-less holds last (they carry no instant to order by). The queue is bounded by what is
     * actually under review - a released or discarded hold drops its pointer - so it is never a full-log scan.
     */
    /** One page of the review queue, for a screen or an API that pages through a large backlog. */
    public record QueuePage(List<Held> holds, String next) {
    }

    /**
     * One bounded page of the review queue: at most {@code limit} holds in path order, starting strictly after the
     * pointer key {@code after} ({@code null} from the top), each enriched from the log as {@link #reviewQueue()}
     * does, and the key to continue from. Newest decision first within the page. This is the face a screen reads:
     * the whole queue is a backlog to page through, and rendering it whole cost one store read per hold.
     */
    public QueuePage reviewQueue(String after, int limit) throws IOException {
        HeldPointers.Page page = HeldPointers.page(store, after, limit);
        List<Held> holds = new ArrayList<>(page.keys().size());
        for (String key : page.keys()) {
            String path = key.substring(HeldPointers.ROOT.length());
            holds.add(new Held(path, latest(path)));
        }
        holds.sort(Comparator.comparing((Held held) -> held.event().map(Event::when).orElse(Instant.EPOCH)).reversed());
        return new QueuePage(List.copyOf(holds), page.next());
    }

    public List<Held> reviewQueue() throws IOException {
        List<Held> queue = new ArrayList<>();
        for (String path : heldPaths()) {
            queue.add(new Held(path, latest(path)));
        }
        queue.sort(Comparator.comparing((Held held) -> held.event().map(Event::when).orElse(Instant.EPOCH)).reversed());
        return queue;
    }

    /** The request paths a repository currently holds under review: the live {@code /quarantine} pointer tree (stored
     *  at {@code publish/quarantine<path>}), descended through the shared {@link HeldPointers} bounded walk and each
     *  stored pointer stripped back to the served request path the hold retracts. A node's own pointer counts whether
     *  or not it also parents deeper holds, which is why the descent is {@link HeldPointers} rather than a bare tree
     *  walk. This is the truth the review queue is keyed off, so a hold is visible whether or not its log survives. */
    private List<String> heldPaths() throws IOException {
        List<String> paths = new ArrayList<>();
        HeldPointers.descend(store, key -> {
            paths.add(key.substring(HeldPointers.ROOT.length()));
            return true;
        });
        return paths;
    }

    public void record(Instant when, String path, String coordinate, Verdict verdict, List<String> reasons)
            throws IOException {
        String line = serialize(when, path, coordinate, verdict, reasons);
        store.write(eventKey(orderKey(when.toEpochMilli()) + "-" + digest(path)),
                new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
        indexLatest(when, path, line);
        // A withheld artifact (quarantine or reject) is an event an external system may want to react to; the emit is
        // best-effort and a no-op when no event sink (the webhook module) is installed, so it never fails the gate.
        EventSink.emit(store, RepositoryEvent.quarantine(null, coordinate, path, verdict.name(), reasons, when));
    }

    /** Every recorded decision, newest first - the whole trail, for an audit surface that wants it all. A render that
     *  only needs the recent decisions should page through {@link #events(int)} instead. */
    public List<Event> events() throws IOException {
        List<Event> events = new ArrayList<>();
        for (String name : store.list(ROOT)) {
            readEvent(name).ifPresent(events::add);
        }
        events.sort(Comparator.comparing(Event::when).reversed());
        return events;
    }

    /**
     * The most recent {@code limit} decisions, newest first - the review queue's bounded view, so a long unrotated log
     * is not read in full per render. The trail's object names sort newest-first by construction (see the class
     * javadoc), so this is a forward {@link ArtifactStore#page} of at most {@code limit} names with no listing and no
     * sort: the deployment pays for the page, not for the container. A within-millisecond tie is settled by re-sorting
     * the small page on its parsed instant.
     *
     * <p>Paging continues past a name whose body is missing or torn so a short page never reads as an exhausted trail,
     * and stops the moment a page comes back empty.
     */
    public List<Event> events(int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        String after = "";
        while (events.size() < limit) {
            List<String> names = new ArrayList<>(limit);
            store.page(ROOT, after, limit - events.size(), names::add);
            if (names.isEmpty()) {
                break;                                          // the trail is exhausted
            }
            after = names.getLast();
            for (String name : names) {
                readEvent(name).ifPresent(events::add);
            }
        }
        events.sort(Comparator.comparing(Event::when).reversed());
        return events;
    }

    /**
     * The most recent <em>refusals</em> - the {@code REJECT} rows - newest first, bounded by {@code limit}.
     *
     * <p>This is the read the review surfaces render a refused publish from, and it exists because a refusal is the one
     * gate decision with nothing else to see it by. A quarantined artifact is stored and linked under
     * {@code /quarantine}, so it stands in {@link #reviewQueue()} until a reviewer resolves it; a <b>rejected</b> one
     * links no pointer and keeps no bytes, so it is in the queue at no point in its life and the class contract above
     * - "without this log its refusal would leave no trace at all" - is the whole of its record. Reading the queue and
     * calling that the review surface therefore answered {@code {"events":[],"refusals":[]}} to an operator whose
     * deployment had just refused a publish outright: the publisher saw a 422 and nobody else saw anything.
     *
     * <p>Every leg's refusal is included - the publish gate's pre-commit {@code REJECT}, the proxy screen's, and the
     * hardened leg's typed structural ones - because "what has this repository refused" is one question, and the
     * reasons on each row already say which leg answered it. {@code HardeningVerdicts.refusals} stays the narrower
     * read, for the hardened leg's own status panel.
     */
    public List<Event> refusals(int limit) throws IOException {
        if (limit <= 0) {
            return List.of();
        }
        List<Event> refusals = new ArrayList<>();
        for (Event event : events(limit * REFUSAL_SCAN_FACTOR)) {
            if (event.verdict() == Verdict.REJECT) {
                refusals.add(event);
                if (refusals.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(refusals);
    }

    /** The most recent gate decision recorded against a single request path, by a point lookup of the latest-verdict-
     *  by-path index rather than a scan of the whole log - the artifact-detail view's read. Empty when the path was
     *  never held or rejected. The index is derived and best-effort: a decision whose index write was lost is simply
     *  not reflected here until the path is acted on again, the append staying the durable audit trail. */
    public Optional<Event> latest(String path) throws IOException {
        return store.readVersioned(indexKey(path))
                .flatMap(versioned -> parse(new String(versioned.content(), StandardCharsets.UTF_8)));
    }

    /** Update the latest-verdict-by-path index to this decision, keeping the newest by {@code when} under
     *  compare-and-set so a concurrent writer's conflict is a retry, not a lost update, and an out-of-order record
     *  never regresses a newer verdict. Best-effort ({@link Retries#tryUpdate}): the append already durably recorded
     *  the decision, so a lost index update is swallowed rather than failing the gate's choreography - the index is
     *  derived, and {@link #latest} serves the prior verdict until the path is acted on again. */
    private void indexLatest(Instant when, String path, String line) {
        byte[] body = line.getBytes(StandardCharsets.UTF_8);
        try {
            Retries.tryUpdate(store, indexKey(path), current -> {
                Optional<Event> existing = current.flatMap(versioned ->
                        parse(new String(versioned.content(), StandardCharsets.UTF_8)));
                return existing.isPresent() && existing.get().when().isAfter(when) ? null : body;
            });
        } catch (IOException | RuntimeException _) {
            // best-effort, as above
        }
    }

    /**
     * Apply the log's retention: delete event objects older than {@code maxAge} (when non-null) and, with a positive
     * {@code maxCount}, everything beyond the newest {@code maxCount} - both judged by the epoch-millis in each
     * object's name, so nothing is read to decide. Index rows age out with the same {@code maxAge}, except a row
     * whose path is <em>still held</em> (its {@code /quarantine} pointer is live), which is kept whatever its age so
     * the review queue never loses the verdict beside a pending hold. Returns how many objects were deleted;
     * idempotent, so the scheduled pass re-running it converges to the same log.
     */
    public int prune(Instant now, Duration maxAge, int maxCount) throws IOException {
        long cutoff = maxAge == null ? Long.MIN_VALUE : now.minus(maxAge).toEpochMilli();
        int removed = 0;
        // The trail streams newest-first out of the store's own ordering, so the sweep holds one stride of names
        // rather than the whole log - the same fix the paged read gets, kept here because a Lease-guarded sweep
        // over a large trail is exactly where the whole-listing allocation lands hardest.
        int position = 0;                                        // how far into the newest-first trail this row sits
        String after = "";
        for (List<String> names = page(ROOT, after); !names.isEmpty(); names = page(ROOT, after)) {
            after = names.getLast();
            for (String name : names) {
                long stamp = millis(name);
                int index = position++;
                if (stamp == Long.MIN_VALUE) {
                    continue;                                    // not this log's naming - never delete what we cannot judge
                }
                if ((maxCount > 0 && index >= maxCount) || stamp < cutoff) {
                    store.delete(eventKey(name));
                    removed++;
                }
            }
        }
        if (maxAge != null) {
            after = "";
            for (List<String> names = page(INDEX_ROOT, after); !names.isEmpty(); names = page(INDEX_ROOT, after)) {
                after = names.getLast();
                for (String name : names) {
                    String key = INDEX_ROOT + "/" + name;
                    Optional<Event> event = store.readVersioned(key)
                            .flatMap(versioned -> parse(new String(versioned.content(), StandardCharsets.UTF_8)));
                    if (event.isEmpty() || !event.get().when().isBefore(Instant.ofEpochMilli(cutoff))) {
                        continue;
                    }
                    if (store.readVersioned(Publication.quarantineKey(event.get().path())).isPresent()) {
                        continue;                                // still under review - its verdict stays beside the hold
                    }
                    store.delete(key);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** One stride of child names under {@code prefix}, strictly after {@code after}, in the store's own order. */
    private List<String> page(String prefix, String after) {
        List<String> names = new ArrayList<>(STRIDE);
        store.page(prefix, after, STRIDE, names::add);
        return names;
    }

    /**
     * Remove a discarded path's rows: its latest-verdict index entry and every event object recorded against it -
     * the reviewer threw the artifact away, so the explanation beside it goes too (a release keeps its trail).
     * Reads only the few objects whose name carries this path's digest, and streams the names a stride at a time
     * rather than materialising the trail to find them.
     */
    public void discarded(String path) throws IOException {
        String suffix = "-" + digest(path);
        String after = "";
        for (List<String> names = page(ROOT, after); !names.isEmpty(); names = page(ROOT, after)) {
            after = names.getLast();
            for (String name : names) {
                if (!name.endsWith(suffix)) {
                    continue;
                }
                Optional<Event> event = readEvent(name);
                if (event.isPresent() && event.get().path().equals(path)) {
                    store.delete(eventKey(name));
                }
            }
        }
        String key = indexKey(path);
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
    }

    private Optional<Event> readEvent(String name) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        store.read(eventKey(name), buffer);
        return parse(buffer.toString(StandardCharsets.UTF_8));
    }

    private static String serialize(Instant when, String path, String coordinate, Verdict verdict,
                                    List<String> reasons) {
        return String.join("\t", when.toString(), path, coordinate, verdict.name(), String.join(" | ", reasons));
    }

    private static Optional<Event> parse(String line) {
        String[] parts = line.split("\t", 5);
        if (parts.length != 5) {
            return Optional.empty();
        }
        List<String> reasons = parts[4].isEmpty() ? List.of() : List.of(REASON_SEPARATOR.split(parts[4]));
        return Optional.of(new Event(Instant.parse(parts[0]), parts[1], parts[2], Verdict.valueOf(parts[3]), reasons));
    }

    /** A collision-free digest of a request path for the {@code <orderKey>-<digest>} event-object name: the hex SHA-256
     *  of the path, so two distinct paths withheld in the same millisecond never map to the same object and overwrite
     *  each other's audit row (as a 32-bit {@code hashCode} could). Contains no {@code '-'}, so {@link #millis} still
     *  splits the name on its first dash. */
    private static String digest(String path) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    }

    /** The width of the order key: {@link Long#MAX_VALUE} is 19 digits, so every key is exactly this wide and the
     *  names compare lexicographically exactly as the instants compare numerically - the zero padding is what makes
     *  the store's own ordering usable, not a cosmetic. */
    private static final int ORDER_KEY_DIGITS = 19;

    /** The order key of an instant: {@code MAX_VALUE - millis}, zero-padded, so ascending lexicographic name order
     *  <em>is</em> descending time order and {@link ArtifactStore#page} serves the newest rows from the first page
     *. A future instant past {@link Long#MAX_VALUE} millis cannot be represented and does not arise: the
     *  argument comes from a clock. */
    private static String orderKey(long millis) {
        return String.format(Locale.ROOT, "%0" + ORDER_KEY_DIGITS + "d", Long.MAX_VALUE - millis);
    }

    /** The epoch-millis an event object's {@code <orderKey>-<digest>} name encodes, so the sweep judges a row from its
     *  name alone and reads no body. {@link Long#MIN_VALUE} for a name this composer could not have produced - a
     *  foreign object, or a row left by a different naming - which the sweep refuses to judge rather than delete. */
    private static long millis(String name) {
        int dash = name.indexOf('-');
        String key = dash < 0 ? name : name.substring(0, dash);
        if (key.length() != ORDER_KEY_DIGITS) {
            return Long.MIN_VALUE;                              // not this log's naming
        }
        try {
            return Long.MAX_VALUE - Long.parseLong(key);
        } catch (NumberFormatException _) {
            return Long.MIN_VALUE;
        }
    }

    /** One event object's key under {@link #ROOT}: the {@code <orderKey>-<digest>} name this class alone composes, so
     *  the trail's spelling lives in one place and the manifest declares the same constant the writes use. */
    private static String eventKey(String name) {
        return ROOT + "/" + name;
    }

    /**
     * The derived index row's key: <b>the request path's digest</b>, never the path spelled into the key, under
     * {@link #INDEX_ROOT} - the same {@link #digest} the trail's own object names carry, so both of this class's
     * spaces name a path the one way.
     *
     * <p><b>Why a digest and not the path.</b> This composer used to URL-encode the path into one segment,
     * which triples every separator and walks straight past a filesystem store's 255-byte name limit: a real, deep
     * pool path produced a key no filesystem backend could hold, so the space was structurally unwritable for exactly
     * the artifacts most likely to be held. That failure landed on a best-effort write, so nothing broke - the path
     * simply had no latest-verdict row, for ever. Truncating was never an option either (two paths would fuse into one
     * row, which is worse than having none). A digest is fixed-width, so no path can overrun the segment, and the path
     * itself rides the row's body - it always did, as the record's second field - so {@link #prune} and every reader
     * still answer with the path rather than a hash. It is the fix
     * {@link build.jenesis.repository.inventory.HeldSubjects} already applied to its own path face, for the same wall.
     */
    private static String indexKey(String path) {
        return INDEX_ROOT + "/" + digest(path);
    }
}
