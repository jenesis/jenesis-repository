package build.jenesis.repository.gate;


import module java.base;

import build.jenesis.repository.store.Publication;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.RecentIndex;

/**
 * The record of what the compliance gate held back, over a repository's store. Every artifact the gate quarantines or
 * rejects, on the publish or the proxy path, is appended with its coordinate, verdict, reasons and time. A rejected
 * artifact stores no bytes, so this log is its only trace; a quarantined one is held under {@code /quarantine} with
 * this log as the explanation. One small object per event under {@link #ROOT}, so concurrent writers never contend.
 *
 * <p>No read scans the whole log. {@link #latest} is one point lookup in a derived, best-effort latest-verdict-by-path
 * index; {@link #reviewQueue} is keyed off the live {@code /quarantine} hold pointers and only enriched from the log,
 * so a hold whose row was lost or never written still surfaces and stays releasable; {@link #events(int)} is one
 * forward page.
 *
 * <p><b>The object name is the order.</b> An event object is named
 * {@code <MAX_VALUE - epochMillis, zero-padded to 19>-<path digest>}, so the store's lexicographic paging enumerates
 * the trail newest first and a bounded read is one forward page with no sort; {@link #prune} streams the same order.
 *
 * <p>{@link #prune} applies the age and count retention the cleanup pass drives, and {@link #discarded} removes a
 * discarded path's rows; a released path keeps its rows, subject to retention, as the record of what was cleared.
 */
public final class QuarantineLog {

    /** The trail's root: one object per gate decision at {@code audit/quarantine/<orderKey>-<digest>}, composed only
     *  by {@link #eventKey}. The storage manifest declares this constant. */
    public static final String ROOT = "audit/quarantine";

    /** The latest-verdict-by-path index's root, a sibling of {@link #ROOT} rather than a child, so listing the trail
     *  never includes it. Composed only by {@link #indexKey}. */
    public static final String INDEX_ROOT = "audit/quarantine-index";

    /** How many child names one streaming stride holds: the working set of a whole-trail pass. */
    private static final int STRIDE = 256;

    /** How many trail rows {@link #refusals(int)} scans per refusal asked for, so a page still fills when refusals mix
     *  with quarantines. */
    private static final int REFUSAL_SCAN_FACTOR = 4;

    /** The reason-token separator in a serialized record. */
    private static final Pattern REASON_SEPARATOR = Pattern.compile(" \\| ");

    private final ArtifactStore store;

    public QuarantineLog(ArtifactStore store) {
        this.store = store;
    }

    /** One gate decision that withheld an artifact: when, the request path and coordinate, the verdict, why, and the
     *  rules that decided it in an operator's words - the deny list, a vulnerability, a licence. */
    public record Event(Instant when, String path, String coordinate, Verdict verdict, List<String> reasons,
                        List<String> rules) {

        public Event {
            reasons = List.copyOf(reasons);
            rules = List.copyOf(rules);
        }
    }

    /** One artifact held for review: the live hold path, and its recorded gate decision, empty when the row was lost
     *  or the log was enabled after the hold. */
    public record Held(String path, Optional<Event> event) {
    }

    /** One page of the review queue and the key to continue from. */
    public record QueuePage(List<Held> holds, String next) {
    }

    /**
     * One bounded page of the review queue: at most {@code limit} holds in path order, strictly after the pointer key
     * {@code after} ({@code null} from the top), each enriched as {@link #reviewQueue()} does, newest decision first
     * within the page. The face a screen reads.
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

    /**
     * The artifacts a repository holds for review, keyed off the live {@code /quarantine} hold pointers and enriched
     * from this log, so a hold whose row never landed still appears, with no {@link Event}. Newest decision first,
     * log-less holds last. Bounded by what is under review, since a released or discarded hold drops its pointer.
     */
    public List<Held> reviewQueue() throws IOException {
        List<Held> queue = new ArrayList<>();
        for (String path : heldPaths()) {
            queue.add(new Held(path, latest(path)));
        }
        queue.sort(Comparator.comparing((Held held) -> held.event().map(Event::when).orElse(Instant.EPOCH)).reversed());
        return queue;
    }

    /** The request paths held under review, from the {@code publish/quarantine<path>} pointers through
     *  {@link HeldPointers}, which counts a pointer that also parents deeper holds. */
    private List<String> heldPaths() throws IOException {
        List<String> paths = new ArrayList<>();
        HeldPointers.descend(store, key -> {
            paths.add(key.substring(HeldPointers.ROOT.length()));
            return true;
        });
        return paths;
    }

    public void record(Instant when, String path, String coordinate, Verdict verdict, List<String> reasons,
                       List<String> rules) throws IOException {
        String line = serialize(when, path, coordinate, verdict, reasons, rules);
        store.write(eventKey(RecentIndex.orderKey(when.toEpochMilli()) + "-" + digest(path)),
                new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
        indexLatest(when, path, line);
        // Best-effort, and a no-op without an installed event sink.
        EventSink.emit(store, RepositoryEvent.quarantine(null, coordinate, path, verdict.name(), reasons, when));
    }

    /** Every recorded decision, newest first: the whole trail. A render of recent decisions uses
     *  {@link #events(int)}. */
    public List<Event> events() throws IOException {
        List<Event> events = new ArrayList<>();
        for (String name : store.list(ROOT)) {
            readEvent(name).ifPresent(events::add);
        }
        events.sort(Comparator.comparing(Event::when).reversed());
        return events;
    }

    /**
     * The most recent {@code limit} decisions, newest first: forward {@link ArtifactStore#page}s of the newest-first
     * names, re-sorted on the parsed instant to settle within-millisecond ties. Paging continues past a missing or torn
     * body, so a short page never reads as an exhausted trail.
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
     * The most recent refusals, the {@code REJECT} rows, newest first, bounded by {@code limit}. A rejected artifact
     * links no pointer and keeps no bytes, so it never appears in {@link #reviewQueue()} and this is how the review
     * surfaces show it. Every leg's refusal is included - publish, proxy and hardened - and each row's reasons say
     * which leg answered.
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

    /** The most recent decision recorded against {@code path}, one point lookup in the derived index; empty when the
     *  path was never held or rejected, or its index write was lost. */
    public Optional<Event> latest(String path) throws IOException {
        return store.readVersioned(indexKey(path))
                .flatMap(versioned -> parse(new String(versioned.content(), StandardCharsets.UTF_8)));
    }

    /** Updates the index to this decision under compare-and-set, keeping the newest by {@code when}. Best-effort: the
     *  append is the durable record, so a lost index update never fails the gate. */
    private void indexLatest(Instant when, String path, String line) {
        byte[] body = line.getBytes(StandardCharsets.UTF_8);
        try {
            Retries.tryUpdate(store, indexKey(path), current -> {
                Optional<Event> existing = current.flatMap(versioned ->
                        parse(new String(versioned.content(), StandardCharsets.UTF_8)));
                return existing.isPresent() && existing.get().when().isAfter(when) ? null : body;
            });
        } catch (IOException | RuntimeException _) {
            // best-effort
        }
    }

    /**
     * Applies the log's retention: deletes event objects older than {@code maxAge} (when non-null) and, with a positive
     * {@code maxCount}, beyond the newest {@code maxCount}, judged from each name without reading a body. Index rows
     * age out with {@code maxAge} unless the path is still held, so a pending hold keeps its verdict. Returns how many
     * objects were deleted; idempotent.
     */
    public int prune(Instant now, Duration maxAge, int maxCount) throws IOException {
        long cutoff = maxAge == null ? Long.MIN_VALUE : now.minus(maxAge).toEpochMilli();
        int removed = 0;
        // Streams the newest-first trail one stride at a time.
        int position = 0;                                        // how far into the newest-first trail this row sits
        String after = "";
        for (List<String> names = page(ROOT, after); !names.isEmpty(); names = page(ROOT, after)) {
            after = names.getLast();
            for (String name : names) {
                long stamp = RecentIndex.epochMilli(name);
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
     * Removes a discarded path's index entry and event objects. Streams the names a stride at a time and reads only
     * the objects whose name carries this path's digest.
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
                                    List<String> reasons, List<String> rules) {
        return String.join("\t", when.toString(), path, coordinate, verdict.name(), String.join(" | ", reasons),
                String.join(" | ", rules));
    }

    private static Optional<Event> parse(String line) {
        String[] parts = line.split("\t", 6);
        if (parts.length < 5) {
            return Optional.empty();
        }
        return Optional.of(new Event(Instant.parse(parts[0]), parts[1], parts[2], Verdict.valueOf(parts[3]),
                listed(parts[4]), parts.length == 6 ? listed(parts[5]) : List.of()));
    }

    private static List<String> listed(String field) {
        return field.isEmpty() ? List.of() : List.of(REASON_SEPARATOR.split(field));
    }

    /** The hex SHA-256 of a request path, so two paths withheld in the same millisecond never share an event object.
     *  Contains no {@code '-'}, so {@link RecentIndex#epochMilli} splits the name on its first dash. */
    private static String digest(String path) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                 // SHA-256 is a required JDK algorithm
        }
    }


    /** One event object's key under {@link #ROOT}. */
    private static String eventKey(String name) {
        return ROOT + "/" + name;
    }

    /**
     * The index row's key under {@link #INDEX_ROOT}: the path's {@link #digest}, which is fixed-width, so a deep path
     * never overruns a filesystem store's 255-byte name limit. The path itself is the row body's second field.
     */
    private static String indexKey(String path) {
        return INDEX_ROOT + "/" + digest(path);
    }
}
