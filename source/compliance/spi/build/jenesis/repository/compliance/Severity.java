package build.jenesis.repository.compliance;

import module java.base;

/**
 * A vulnerability severity band, ordered so a policy can express a threshold ("reject HIGH and above"). The score
 * ranges are the standard CVSS v3 bands; {@link #ofScore} maps a base score onto a band, which is how an
 * advisory feed's numeric scores become a policy-comparable level.
 *
 * <p>{@link #UNKNOWN} is the band for "the feed answered and could not tell us", and it sorts <em>above</em>
 * {@link #CRITICAL} so that ordinal floor comparisons fail closed against it. A rollup that wants the strongest
 * band for reporting uses {@link #strongest} rather than {@code compareTo}, which prefers a band that says
 * something.
 */
public enum Severity {

    NONE,

    LOW,

    MEDIUM,

    HIGH,

    CRITICAL,

    /**
     * The feed answered, and could not tell us how severe this is - a vector in a scheme the scorer does not read,
     * a vendor field it does not carry. Distinct from {@link #NONE}, which is the positive claim "nothing severe
     * here", and the distinction is the point: were they one value, an advisory scored only by a vector the scorer
     * does not read would report as {@code NONE} and a {@code reject #severityRank >= 4} floor would admit it.
     * {@code AdvisorySource} clause 4 forbids a source answering clean when it means unknown; this is the vocabulary
     * that lets it comply.
     *
     * <p><b>It sorts above {@link #CRITICAL}, and that is deliberate.</b> Every severity-floor comparison in the
     * product is an ordinal one - {@code severity().compareTo(floor) >= 0} - so a band placed below the floor is
     * admitted by default. Putting the unknown band at the top makes every one of those comparisons fail closed
     * without touching a single call site: a policy that rejects at or above HIGH also rejects what it cannot
     * score, which is the only safe reading of "I do not know".
     *
     * <p>The cost of that placement is that a naive "strongest wins" rollup would let an unknown mask a real
     * CRITICAL. Those sites use {@link #strongest} instead, which prefers a band that says something.
     */
    UNKNOWN;

    /**
     * The bands an operator may pick as a policy floor - every band except {@link #UNKNOWN}.
     *
     * <p>{@code UNKNOWN} is an <em>answer</em>, not a threshold. "Reject at or above unknown" is not a policy
     * anyone means, and a vendor vocabulary has no word for it, so a catalogue that offered it would hand an
     * operator a choice that does nothing. Every floor-shaped enumeration reads this rather than
     * {@link #values()}, which includes the unknown band.
     */
    public static List<Severity> floors() {
        return Stream.of(values()).filter(band -> band != UNKNOWN).toList();
    }

    /**
     * The more severe of two bands for reporting, preferring a band that says something over {@link #UNKNOWN}.
     *
     * <p>Ordinal comparison is right for a policy floor and wrong for a rollup. {@code UNKNOWN} sits above
     * {@code CRITICAL} so a floor fails closed against it - but merging a source that scored an advisory CRITICAL
     * with one that could not score it at all must report CRITICAL, not lose it. "Something could not be read"
     * survives only when nothing else could read it either.
     *
     * <p>{@link #NONE} does not count as reading it. It is the claim that a source scored the advisory and found
     * nothing severe, which is no evidence against a source that could not score it: merging the two answers
     * {@code UNKNOWN}, so a floor still fails closed against the advisory rather than admitting it as clean.
     */
    public static Severity strongest(Severity first, Severity second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (first == UNKNOWN) {
            return second == NONE ? UNKNOWN : second;
        }
        if (second == UNKNOWN) {
            return first == NONE ? UNKNOWN : first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    /**
     * The band an advisory names in words - {@code low} (or the {@code negligible} Debian and Ubuntu rate with, which
     * scanners pass on), {@code medium} (or {@code moderate}, or Socket's {@code middle}), {@code high},
     * {@code critical}, in any case and around any whitespace - or {@code fallback} for any other word or none: a
     * source chooses whether a word it cannot read is {@link #UNKNOWN} or {@link #NONE}.
     */
    public static Severity ofWord(String word, Severity fallback) {
        return switch (word == null ? "" : word.strip().toLowerCase(Locale.ROOT)) {
            case "low", "negligible" -> LOW;
            case "medium", "moderate", "middle" -> MEDIUM;
            case "high" -> HIGH;
            case "critical" -> CRITICAL;
            default -> fallback;
        };
    }

    public static Severity ofScore(double score) {
        if (score >= 9.0) {
            return CRITICAL;
        }
        if (score >= 7.0) {
            return HIGH;
        }
        if (score >= 4.0) {
            return MEDIUM;
        }
        if (score > 0.0) {
            return LOW;
        }
        return NONE;
    }
}
