package build.jenesis.repository.compliance;

import module java.base;

/**
 * A {@link SignalSource}'s reading of what it is currently able to answer, derived from the lookups it has actually
 * made - the one derivation the whole signal family answers {@link SignalSource#freshness()} from, rather than twelve
 * slightly different private booleans. A source records {@link #fetched(String) fetched(key)} when a lookup landed and
 * {@link #failed(String) failed(key)} when one produced no answer at all, and hands {@link #freshness()} straight out
 * of its accessor.
 *
 * <p><strong>The reading is keyed, because the lookups are</strong>. It used to be a last-writer flag -
 * a failed lookup cleared it and the very next successful one, <em>of any other key</em>, set it again - so it
 * described whichever lookup happened to finish last rather than what the source can answer. The vendor having
 * answered for B says nothing about the A it could not answer for, so a success on B does not clear A. Two rules, both
 * derived from the lookups themselves:
 * <ul>
 * <li>the instant is the last <em>completed</em> lookup, of any key - so it reports the age of the data rather than
 *     the age of the process, and a source that has answered nothing reports {@link Freshness#NEVER};</li>
 * <li>the reading is not authoritative while any key this source <em>cannot currently answer for</em> - one whose last
 *     lookup produced nothing at all. It is deliberately one value for the whole source even though the lookups are
 *     per key: a threshold is a policy over a <em>pass</em>, and one applied to some keys of a pass and skipped for
 *     others yields a report that cannot say which keys were screened. It clears the only two honest ways: that key
 *     answering again, or its {@link #RETRY retry window} lapsing so nothing is being served on the strength of it.</li>
 * </ul>
 *
 * <p><strong>What "failed" means is the feed's decision, not this class's.</strong> A vendor that answered and simply
 * carries nothing for the queried key <em>succeeded</em> - that is the one observation separating a clean answer from
 * an outage, and recording it as a failure would throw the separation away again. Only a lookup that did not produce
 * an answer (a rejected status, an unreadable body, a bound reached, an unreachable host) is a failure. Nor does a
 * lookup that served an <em>aged</em> answer fail: the answer is real, and its age is what the instant carries.
 *
 * <p><strong>Why a fail-closed feed records its failures too.</strong> A fail-closed feed's outage separation is the
 * raise its contract mandates ({@link AdvisorySource} clause 4), so no caller is left with a degraded value to be
 * misled by, and for it this reading is display-only. It is still recorded, for parity (&sect;13) and because a
 * console reading "authoritative, last fetched three days ago" beside a feed that has failed every lookup since is
 * exactly the ambiguity {@link Freshness} exists to remove. For a fail-<em>soft</em> feed the reading <em>is</em> the
 * separation: an unreachable model and a model with nothing to say produce the same empty answer, and only
 * {@link Freshness#authoritative()} tells them apart - which is why a consumer that <em>loosens</em> on an empty
 * answer (an operator-set EPSS threshold, a health floor) must read it.
 *
 * <p>Mutable by design and safe for the concurrent readers a shared source has. {@link #freshness()} is a render: it
 * moves nothing, asks nobody and answers the same way twice, so it takes no lock and prunes nothing - a read
 * may not mutate what a second read would then see differently. The two recording methods are serialized against each
 * other because the cap below is a check-then-act; each is a handful of map operations and never any I/O, so nothing
 * that reaches a vendor is ever serialized here.
 */
public final class FreshnessTracker {

    /**
     * How long a lookup that produced no answer keeps the reading non-authoritative - and, for a
     * {@link FeedCache#failSoft fail-soft} cache, how long an aged answer keeps being served before the vendor is
     * asked again. Far shorter than any feed's own window, because it is a failure cadence rather than a freshness
     * one: a key nobody asks again may not stand a consumer down for ever.
     */
    public static final Duration RETRY = Duration.ofMinutes(15);

    /** The most keys held as unanswerable at once. Only failing keys are ever in the map, so this is reached by a
     *  source that cannot answer for ten thousand distinct keys inside one retry window - an outage, by then. */
    private static final int CAP = 10_000;

    private final Clock clock;

    /** The last completed lookup, of any key - {@code null} until one lands. Volatile rather than locked: a reader
     *  sees a whole instant or the previous one, which is all {@link #freshness()} needs. */
    private volatile Instant fetched;

    /** The keys this source currently cannot answer for, each with the instant its lookup stops speaking for the
     *  reading. A key enters when its lookup produced nothing at all, leaves when a lookup for <em>that</em> key
     *  lands, and lapses on its own after {@link #RETRY}. Small by construction - only failing keys are in it - and
     *  pruned on the write path, so {@link #freshness()} stays a read. */
    private final Map<String, Long> unanswered = new ConcurrentHashMap<>();

    /** A reading over the deployment {@link SignalContext#clock() clock} - the same seam every feed takes its windows
     *  from, so a suite crosses a retry window without sleeping through it. */
    public FreshnessTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The current reading, for {@link SignalSource#freshness()} to hand out unchanged. */
    public Freshness freshness() {
        Instant last = fetched;
        if (last == null) {
            return Freshness.NEVER;
        }
        return unanswerable() ? Freshness.stale(last) : Freshness.at(last);
    }

    /**
     * A lookup for {@code key} landed, so the answers standing behind it may be acted on as of now and this key is no
     * longer one the source cannot answer for. It is the <em>only</em> success that speaks for this key: a lookup of
     * some other key landing says nothing about it.
     */
    public synchronized void fetched(String key) {
        Objects.requireNonNull(key, "key");
        fetched = clock.instant();
        unanswered.remove(key);
    }

    /**
     * A lookup for {@code key} produced no answer at all, so the source cannot currently answer for it and the reading
     * stops being authoritative until it answers again or {@link #RETRY} lapses. Any earlier fetch instant is kept -
     * it is still the last time this source had real data, which is exactly what an operator needs to see.
     */
    public synchronized void failed(String key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        unanswered.values().removeIf(until -> now >= until);
        if (unanswered.size() >= CAP) {
            // Bounded, and in the safe direction: one live marker already says the reading is not authoritative, so
            // dropping the rest loses nothing a consumer acts on.
            unanswered.clear();
        }
        unanswered.put(key, now + RETRY.toMillis());
    }

    /** Whether any key's lookup is currently standing for "this source could not answer". Scanned rather than
     *  counted, and never pruned here: a read may not mutate what a second read would then see differently. */
    private boolean unanswerable() {
        long now = clock.millis();
        for (long until : unanswered.values()) {
            if (now < until) {
                return true;
            }
        }
        return false;
    }
}
