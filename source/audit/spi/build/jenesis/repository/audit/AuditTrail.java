package build.jenesis.repository.audit;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;

/**
 * A durable, queryable audit trail of security-relevant changes: who ({@code actor}, a credential hash or a named
 * source), what ({@code action}) and on what ({@code target}), per tenant. Recording is best-effort - a failed write
 * never fails the operation it audits - and a disabled trail records nothing while still answering queries over earlier
 * records. Persistence is an {@link AuditTrailProvider} module's, discovered with {@link ServiceLoader}; with none the
 * {@link #none() none} trail stands in, so a deployment that must keep no audit data removes the module and can prove
 * nothing records.
 */
public interface AuditTrail {

    /** One audit event: when it happened, who acted, the action and the target it acted on. */
    record Event(Instant at, String actor, String action, String target) {
    }

    /** A bounded slice of a tenant's trail: the page's events (newest first) and whether older events remain, so a
     *  render pages rather than pulling the whole trail. */
    record Page(List<Event> events, boolean more, String next) {

        public Page {
            events = List.copyOf(events);
        }

        public Page(List<Event> events, boolean more) {
            this(events, more, null);
        }
    }

    /** The furthest an offset page reaches: an offset past it is clamped, so no offset read buffers more than this many
     *  events. Deeper reads follow a page's {@code next} cursor. */
    int MAX_OFFSET = 10_000;

    /** One page by cursor: the events after {@code after} (the previous page's {@code next}; null or blank for the
     *  newest page), at most {@code limit}, newest first. A cursor read costs the page alone however deep it reaches.
     *  The inherited form resolves the cursor over the materialised answer; a store-backed trail resumes its walk at
     *  the cursor. */
    default Page query(String tenant, Instant from, Instant to, String action, String after, int limit)
            throws IOException {
        int offset = after == null || after.isBlank() ? 0 : parseOffset(after);
        Page page = query(tenant, from, to, action, offset, limit);
        return new Page(page.events(), page.more(), page.more() ? Integer.toString(offset + page.events().size())
                : null);
    }

    private static int parseOffset(String cursor) {
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("not a cursor this trail issued: " + cursor);
        }
    }

    /** Whether recording is switched on; {@link #record} is a no-op when it is not. */
    boolean enabled();

    /** Record an event for {@code tenant}; best-effort (a failed write is dropped) and a no-op when disabled. */
    void record(String tenant, String actor, String action, String target);

    /** A tenant's events, newest first, optionally bounded by {@code from}/{@code to} (inclusive) and one
     *  {@code action}; any filter may be {@code null}. The whole unrotated trail is materialised, so a render pages
     *  through {@link #query(String, Instant, Instant, String, int, int)} and an export streams through
     *  {@link #stream}. */
    List<Event> query(String tenant, Instant from, Instant to, String action) throws IOException;

    /** A sink {@link #stream} pushes each event to; it may throw {@link IOException}, since a CSV export writes each
     *  row to the response as it arrives. */
    @FunctionalInterface
    interface Sink {
        void accept(Event event) throws IOException;
    }

    /**
     * Stream a tenant's events, newest first, filtered as {@link #query(String, Instant, Instant, String)}, to
     * {@code sink} one at a time, so a CSV export never holds the trail in heap.
     *
     * <p><strong>A trail streams its own storage.</strong> The inherited default materialises the whole answer through
     * {@link #streamByQuery}, so past the ceiling {@link InheritedBound} states it throws, naming the inheriting class
     * and the remedy. The store-backed trail overrides it to hold one day's events at a time; an in-memory trail calls
     * {@link #streamByQuery} by name.
     *
     * @throws IllegalStateException when the inherited fallback matches more events than {@link InheritedBound} permits
     *     an inherited default to materialise
     */
    default void stream(String tenant, Instant from, Instant to, String action, Sink sink) throws IOException {
        streamByQuery(this, tenant, from, to, action, sink);
    }

    /**
     * A bounded page of a tenant's events, newest first: at most {@code limit} from {@code offset}, filtered as
     * {@link #query(String, Instant, Instant, String)}.
     *
     * <p>The inherited default slices the materialised trail through {@link #pageByQuery} and throws past
     * {@link InheritedBound}'s ceiling, as {@link #stream} does. The store-backed trail reads only the page's objects -
     * the epoch-millis in each name selects the page before any body is read - and never sorts the whole trail.
     *
     * @throws IllegalStateException when the inherited fallback matches more events than {@link InheritedBound} permits
     *     an inherited default to materialise
     */
    default Page query(String tenant, Instant from, Instant to, String action, int offset, int limit)
            throws IOException {
        return pageByQuery(this, tenant, from, to, action, offset, limit);
    }

    /**
     * Emit {@code trail}'s whole {@link #query(String, Instant, Instant, String)} answer to {@code sink} event by event
     * - the named form of {@link #stream}'s fallback, for an in-memory trail. Bounded by {@link InheritedBound}.
     *
     * @throws IllegalStateException when the filter matches more events than {@link InheritedBound} permits an
     *     inherited default to materialise
     */
    static void streamByQuery(AuditTrail trail, String tenant, Instant from, Instant to, String action, Sink sink)
            throws IOException {
        for (Event event : InheritedBound.bounded(trail, "stream(String, Instant, Instant, String, Sink)",
                "query(String, Instant, Instant, String)", trail.query(tenant, from, to, action))) {
            sink.accept(event);
        }
    }

    /**
     * Page {@code trail} by slicing its materialised {@link #query(String, Instant, Instant, String)} answer - the
     * named form of the paged query's fallback, bounded as {@link #streamByQuery} is.
     *
     * @throws IllegalStateException when the filter matches more events than {@link InheritedBound} permits an
     *     inherited default to materialise
     */
    static Page pageByQuery(AuditTrail trail, String tenant, Instant from, Instant to, String action, int offset,
                            int limit) throws IOException {
        List<Event> all = InheritedBound.bounded(trail, "query(String, Instant, Instant, String, int, int)",
                "query(String, Instant, Instant, String)", trail.query(tenant, from, to, action));
        int start = Math.min(Math.clamp(offset, 0, MAX_OFFSET), all.size());
        int end = Math.min(all.size(), start + Math.max(0, limit));
        return new Page(all.subList(start, end), end < all.size());
    }

    /** The trail standing in when no audit module is installed: records nothing, answers no events. A singleton, so
     *  {@code trail == AuditTrail.none()} tells "no audit module". */
    AuditTrail NONE = new AuditTrail() {

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void record(String tenant, String actor, String action, String target) {
        }

        @Override
        public List<Event> query(String tenant, Instant from, Instant to, String action) {
            return List.of();
        }
    };

    static AuditTrail none() {
        return NONE;
    }
}
