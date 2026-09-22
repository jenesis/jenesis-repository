package build.jenesis.repository.audit;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;

/**
 * A durable, queryable audit trail of security-relevant changes: who ({@code actor}, a credential hash or a named
 * source), what ({@code action}) and on what ({@code target}), per tenant. Recording is best-effort - a failed
 * write must never fail the operation it audits - and a disabled trail records nothing while still answering
 * queries over what was recorded before. How events are persisted is the implementation's part, supplied by an
 * {@link AuditTrailProvider} module discovered with {@link ServiceLoader}; with none installed the {@link #none()
 * none} trail stands in, so a deployment that must keep no audit data removes the module and can prove nothing
 * records.
 */
public interface AuditTrail {

    /** One audit event: when it happened, who acted, the action and the target it acted on. */
    record Event(Instant at, String actor, String action, String target) {
    }

    /** A bounded slice of a tenant's trail: the events in this page (newest first) and whether older events remain
     *  past it, so a console or API render pages on rather than pulling the whole (unrotated) trail on every read. */
    record Page(List<Event> events, boolean more, String next) {

        public Page {
            events = List.copyOf(events);
        }

        public Page(List<Event> events, boolean more) {
            this(events, more, null);
        }
    }

    /** The furthest an offset page reaches into a trail: an offset past it is clamped, so an offset
     *  can never make a read buffer more than this many events. Deeper reads follow a page's {@code next} cursor. */
    int MAX_OFFSET = 10_000;

    /**
     * One page of the trail by cursor: the events after {@code after} (the {@code next} of the previous page; null or
     * blank for the newest page), at most {@code limit} of them, newest first. A cursor read costs the page alone
     * however deep into the trail it reaches, where an offset read costs the offset as well. The inherited form
     * resolves the cursor over the materialised answer; a store-backed trail resumes its walk at the cursor.
     */
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

    /** A tenant's events, newest first, optionally bounded by {@code from}/{@code to} (inclusive) and a single
     *  {@code action}; any of the filters may be {@code null}. The whole (unrotated) trail is materialised, so a
     *  request-path render that only needs a slice pages through {@link #query(String, Instant, Instant, String, int,
     *  int)} instead, and a whole-trail export streams through {@link #stream}; this stays for callers that genuinely
     *  want the list in hand. */
    List<Event> query(String tenant, Instant from, Instant to, String action) throws IOException;

    /** A sink {@link #stream} pushes each event to; it may throw {@link IOException} because a CSV export writes each
     *  row straight to the (network) response as it arrives. */
    @FunctionalInterface
    interface Sink {
        void accept(Event event) throws IOException;
    }

    /**
     * Stream a tenant's events, newest first, filtered exactly as {@link #query(String, Instant, Instant, String)}, to
     * {@code sink} one at a time - the CSV export writes each row straight to the response, so the whole (unrotated)
     * trail never lands in heap at once.
     *
     * <p><strong>A trail streams its own storage; the inherited body is a small-trail fallback and says so out loud.</strong>
     * The {@code default} delegates to {@link #streamByQuery}, which materialises the whole
     * {@link #query(String, Instant, Instant, String)} answer and emits it row by row - it puts the entire unrotated
     * trail in heap to stream it, the one thing this signature exists to avoid. So it refuses rather than pretending:
     * past the ceiling {@link InheritedBound} states it throws an {@link IllegalStateException} naming the inheriting
     * class and the remedy. The store-backed trail overrides it to hold only one day's events in heap at a time
     * (bounded by that day's volume) - the streaming twin of the paged
     * {@link #query(String, Instant, Instant, String, int, int)}, and what lets a very large trail export within a
     * flat memory envelope; a trail whose events genuinely <em>are</em> in memory calls {@link #streamByQuery} by name.
     *
     * @throws IllegalStateException when the inherited fallback matches more events than {@link InheritedBound}
     *                               permits an inherited default to materialise
     */
    default void stream(String tenant, Instant from, Instant to, String action, Sink sink) throws IOException {
        streamByQuery(this, tenant, from, to, action, sink);
    }

    /**
     * A bounded page of a tenant's events, newest first: at most {@code limit} of them from {@code offset}, filtered
     * exactly as {@link #query(String, Instant, Instant, String)} - so a console or API render serves a slice rather
     * than the whole trail on every request (§7).
     *
     * <p>The {@code default} delegates to {@link #pageByQuery}, which materialises the whole trail and slices it, and
     * carries the same visible ceiling as {@link #stream}: past what {@link InheritedBound} permits it throws rather
     * than turning one console render into an unbounded heap allocation. The store-backed trail overrides it to bound
     * the walk, reading only the page's objects (the epoch-millis in each object's name selects the newest page before
     * any body is read) and never sorting the whole trail - the {@code QuarantineLog.events(int)} idiom applied per
     * day.
     *
     * @throws IllegalStateException when the inherited fallback matches more events than {@link InheritedBound}
     *                               permits an inherited default to materialise
     */
    default Page query(String tenant, Instant from, Instant to, String action, int offset, int limit)
            throws IOException {
        return pageByQuery(this, tenant, from, to, action, offset, limit);
    }

    /**
     * Emit {@code trail}'s whole {@link #query(String, Instant, Instant, String)} answer to {@code sink} event by
     * event - the explicit, named form of the fallback {@link #stream} inherits, for a trail whose events are already
     * in memory (the none trail, an in-process recorder). It is bounded, and the bound throws: see
     * {@link InheritedBound}, which holds the ceiling and the refusal for every SPI that ships this shape.
     *
     * @throws IllegalStateException when the filter matches more events than {@link InheritedBound} permits an
     *                               inherited default to materialise
     */
    static void streamByQuery(AuditTrail trail, String tenant, Instant from, Instant to, String action, Sink sink)
            throws IOException {
        for (Event event : InheritedBound.bounded(trail, "stream(String, Instant, Instant, String, Sink)",
                "query(String, Instant, Instant, String)", trail.query(tenant, from, to, action))) {
            sink.accept(event);
        }
    }

    /**
     * Page {@code trail} by materialising and slicing its whole {@link #query(String, Instant, Instant, String)}
     * answer - the explicit, named form of the fallback {@link #query(String, Instant, Instant, String, int, int)}
     * inherits, for a trail whose events are already in memory. Bounded exactly as {@link #streamByQuery} is.
     *
     * @throws IllegalStateException when the filter matches more events than {@link InheritedBound} permits an
     *                               inherited default to materialise
     */
    static Page pageByQuery(AuditTrail trail, String tenant, Instant from, Instant to, String action, int offset,
                            int limit) throws IOException {
        List<Event> all = InheritedBound.bounded(trail, "query(String, Instant, Instant, String, int, int)",
                "query(String, Instant, Instant, String)", trail.query(tenant, from, to, action));
        int start = Math.min(Math.clamp(offset, 0, MAX_OFFSET), all.size());
        int end = Math.min(all.size(), start + Math.max(0, limit));
        return new Page(all.subList(start, end), end < all.size());
    }

    /** The shared trail standing in when no audit module is installed: records nothing, answers no events. It is a
     *  singleton, so a caller can tell "no audit module" by identity ({@code trail == AuditTrail.none()}). */
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
