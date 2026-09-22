package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Features;

/**
 * One retention dial: the config key a pass reads its age bound from, the product default a deployment gets when
 * the key is unset, and the one parse that turns the stored string into a bound - or into no bound at all.
 *
 * <p>The retention family's twin of {@link IntervalSetting}, and the one rule that keeps the two families distinct
 * is the meaning of zero. A cadence of zero would be a pass that is installed, listed and never runs, so the cadence
 * dial refuses it; a retention of zero is a policy an operator may legitimately hold - keep everything - so here
 * blank, zero and negative all mean exactly that. Seven reapers had grown the same fifteen lines to say so, each
 * citing a sibling as the rule to mirror, and one of them had drifted: the audit trail read zero as the default year
 * rather than as "keep forever", so one value meant opposite things on two dials of one family.
 *
 * <p><strong>{@code resolve} never throws</strong>, for the cadence dial's reason: an unchecked throw out of a
 * pass's dial parse does not disable one reaper, it fails the whole pass - or, for a dial read while a provider is
 * resolved, the server's startup. A malformed value falls back to the product default and is announced once at
 * {@code WARNING}, naming the key, the value and the bound actually in effect.
 *
 * <p><strong>The default lives here.</strong> A pass holds its dial as one constant, and its module's settings
 * catalogue entry renders the key and the default from {@link #key()} and {@link #fallback()}, so the two cannot
 * drift.
 */
public final class RetentionSetting {

    private static final System.Logger LOGGER = System.getLogger(RetentionSetting.class.getName());

    private final String key;
    private final String fallbackText;
    private final Duration fallback;

    private RetentionSetting(String key, String fallbackText) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A retention dial needs a config key");
        }
        if (fallbackText == null || fallbackText.isBlank()) {
            throw new IllegalArgumentException("The default bound for " + key + " must be given as a duration");
        }
        Duration fallback;
        try {
            // Not operator input - a literal this pass shipped - so a value that does not parse throws here.
            fallback = Durations.parse(fallbackText.trim());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("The default bound for " + key + " is not a duration: "
                    + fallbackText, malformed);
        }
        if (fallback == null || !fallback.isPositive()) {
            // Not operator input: a pass shipping a zero or negative default is a programming error, and it must fail
            // where it is written rather than ship a reaper whose product default is "reap nothing".
            throw new IllegalArgumentException("The default bound for " + key + " must be positive, was " + fallback);
        }
        this.key = key;
        this.fallbackText = fallbackText;
        this.fallback = fallback;
    }

    /** An age bound read from a duration dial ({@code staging-ttl=P30D}, {@code =30d}). The default is given as
     *  <strong>text</strong> for the reason {@code IntervalSetting.of} gives: the settings reference is extracted
     *  from the compiled contributor, so a default built at run time renders as {@code (computed)} and documents
     *  nothing. */
    public static RetentionSetting of(String key, String fallback) {
        return new RetentionSetting(key, fallback);
    }

    /** The config key this bound is read from - also what the module's settings catalogue entry renders. */
    public String key() {
        return key;
    }

    /** The product default a deployment runs with when the dial is unset or malformed. Always positive. A module's
     *  settings catalogue entry renders {@link #fallbackText()} rather than this. */
    public Duration fallback() {
        return fallback;
    }

    /** The product default exactly as the pass wrote it - the string a settings catalogue entry renders. */
    public String fallbackText() {
        return fallbackText;
    }

    /**
     * The bound in effect: the {@link #fallback()} when the dial is unset or malformed, empty - no bound, keep
     * everything - when it is blank, zero or negative, and otherwise the configured duration. Never throws.
     */
    public Optional<Duration> resolve(UnaryOperator<String> config) {
        String value = config.apply(key);
        if (value == null) {
            return Optional.of(fallback);
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        Duration parsed;
        try {
            parsed = Durations.parse(trimmed);
        } catch (IllegalArgumentException malformed) {
            if (Announced.first(key, trimmed)) {
                LOGGER.log(System.Logger.Level.WARNING, Features.key(key) + "=" + trimmed + " is not a duration; "
                        + "the pass keeps the product default " + fallback + ". Accepted: an ISO-8601 duration (P30D, "
                        + "PT12H) or a suffixed one (30d, 12h); blank, zero or negative means no bound.");
            }
            return Optional.of(fallback);
        }
        return parsed.isPositive() ? Optional.of(parsed) : Optional.empty();
    }
}
