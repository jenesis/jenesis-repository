package build.jenesis.repository.compliance;

import module java.base;

/**
 * How current the data a {@link SignalSource} answers from is, and whether a consumer may act on it - the one accessor
 * pair the whole signal family answers, so an outage, a stale snapshot and a genuinely clean answer are three
 * different values rather than one.
 *
 * <p>Before this type the family had half of the question in one place and none of it in the others:
 * {@link KnownExploitedSource} carried an {@code available()} boolean and the other four contracts carried nothing at
 * all, so an unscored CVE and an unreachable FIRST.org were the same map, an unrated coordinate and an unreachable
 * deps.dev were the same {@link Optional#empty()}, and no contract could say <em>when</em> anything had last been
 * fetched. {@link SignalSource#freshness()} replaces both gaps with one value, and the two components answer the two
 * questions a consumer actually asks:
 *
 * <ul>
 * <li>{@link #authoritative()} - <b>may I act on this?</b> A real answer stands behind the value; the source is not
 *     serving the fail-soft empty it falls back to when it cannot reach its vendor. This is the component a consumer
 *     that <em>loosens</em> must read (the re-analysis auto-release, an operator's EPSS threshold, a health floor):
 *     a value that reads as clean during an outage is the &sect;9 silent fallback, one layer up.</li>
 * <li>{@link #refreshed()} - <b>how old is it?</b> The instant the data behind the answer was fetched, so &sect;10's
 *     "every derived or externally-sourced view shows its last fetch instant, so an empty panel is never ambiguous
 *     between clean and never-scanned" can be met by rendering it. Absent means nothing was ever fetched - either
 *     because no fetch has succeeded yet ({@link #NEVER}) or because this source does not fetch at all
 *     ({@link #FIXED}).</li>
 * </ul>
 *
 * <p>The two are deliberately orthogonal rather than one three-valued enum, because all four combinations are real
 * and a consumer reads them independently. A source serving a prior-good snapshot while its refresh fails is
 * {@link #at} - authoritative, with an instant that is simply older - and it is the <em>consumer</em> that decides
 * how old is too old, because "too old" is a policy question (a KEV catalogue a day behind still names the same
 * actively-exploited CVEs; an EPSS model a month behind is a different model). A source whose data may no longer be
 * acted on at all, though it has some, is {@link #stale}. Nothing here interprets the age; this type only makes it
 * expressible.
 */
public record Freshness(Optional<Instant> refreshed, boolean authoritative) {

    public Freshness {
        Objects.requireNonNull(refreshed, "refreshed");
    }

    /**
     * Nothing has been fetched: the source is answering the empty fail-soft value it falls back to before its first
     * successful fetch, or after one that never succeeded. The neutral {@code NONE} sentinel of every contract reports
     * this, so "no feed is installed" and "the feed is down" agree on the one thing that matters to a consumer - that
     * the emptiness confirms nothing.
     */
    public static final Freshness NEVER = new Freshness(Optional.empty(), false);

    /**
     * The source answers from data it did not fetch - a fixed map, a set handed to {@code of(...)}, an in-memory
     * mirror in a test. Authoritative (the data is exactly what it was given, and no vendor can be down), with no
     * fetch instant because no fetch happened. A network-backed source must never report this: it is the value that
     * says "there is no vendor behind me", and the signal contract kit's staleness leg fails a feed that claims it
     * after driving a real fetch.
     */
    public static final Freshness FIXED = new Freshness(Optional.empty(), true);

    /** A fetch succeeded at {@code instant} and the answers drawn from it may be acted on. A <em>fail-soft</em> source
     *  serving a prior-good snapshot through a failing refresh keeps reporting this with its original instant: the
     *  data is real, and how old is too old is the consumer's policy rather than this type's. A fail-closed source
     *  never reaches that case, because it raises rather than serving an answer past its own window
     *  ({@code AdvisorySource} clause 4). */
    public static Freshness at(Instant instant) {
        return new Freshness(Optional.of(Objects.requireNonNull(instant, "instant")), true);
    }

    /** Data was fetched at {@code instant} but may no longer be acted on - a snapshot the source itself has ruled
     *  out (a pointer naming bytes that are gone, a generation it could not render), or a source with a key it
     *  currently cannot answer for at all. A loosening consumer holds its position; a tightening one may still use
     *  the values, which is why the instant is kept rather than dropped. */
    public static Freshness stale(Instant instant) {
        return new Freshness(Optional.of(Objects.requireNonNull(instant, "instant")), false);
    }

    /** Whether any fetch instant is known at all - false for {@link #NEVER} and for {@link #FIXED}, which are told
     *  apart by {@link #authoritative()}. */
    public boolean fetched() {
        return refreshed.isPresent();
    }

    /**
     * The conservative fold every contract's {@code combined(...)} merge applies over the sources it unions: the
     * merged view is authoritative only when <em>every</em> member is, and its last-fetch instant is the
     * <em>earliest</em> any member reports, because a union is only as current as its stalest member. Both halves are
     * order-insensitive, so a merged source's freshness never depends on discovery order.
     *
     * <p>An empty collection folds to {@link #NEVER}: nothing was consulted, so nothing is confirmed - the same
     * reading as every contract's neutral element.
     */
    public static Freshness merged(Collection<Freshness> parts) {
        boolean authoritative = !parts.isEmpty();
        Instant earliest = null;
        for (Freshness part : parts) {
            authoritative &= part.authoritative();
            Instant instant = part.refreshed().orElse(null);
            if (instant != null && (earliest == null || instant.isBefore(earliest))) {
                earliest = instant;
            }
        }
        return new Freshness(Optional.ofNullable(earliest), authoritative);
    }
}
