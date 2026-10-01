package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Features;

/**
 * One retention dial: the config key a pass reads its age bound from, the default when the key is unset, and the
 * parse that turns the stored string into a bound or into no bound.
 *
 * <p>The retention twin of {@link IntervalSetting}, differing in what zero means: keeping everything is a legitimate
 * policy, so blank, zero and negative all mean no bound. Every reaper reads its dial through here, so zero means the
 * same on every dial of the family.
 *
 * <p>{@code resolve} never throws, as for {@link IntervalSetting}: a malformed value falls back to the default and is
 * announced once at {@code WARNING}. A pass holds its dial as one constant and its catalogue entry renders
 * {@link #key()} and {@link #fallbackText()}, so the default has one definition.
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
            // A literal the pass ships, not operator input, so a malformed one throws.
            fallback = Durations.parse(fallbackText.trim());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("The default bound for " + key + " is not a duration: "
                    + fallbackText, malformed);
        }
        if (fallback == null || !fallback.isPositive()) {
            throw new IllegalArgumentException("The default bound for " + key + " must be positive, was " + fallback);
        }
        this.key = key;
        this.fallbackText = fallbackText;
        this.fallback = fallback;
    }

    /** An age bound read from a duration dial ({@code staging-ttl=P30D}); the default is text, as for
     *  {@link IntervalSetting#of(String, String)}. */
    public static RetentionSetting of(String key, String fallback) {
        return new RetentionSetting(key, fallback);
    }

    /** The config key this bound is read from. */
    public String key() {
        return key;
    }

    /** The default a deployment runs with when the dial is unset or malformed; always positive. */
    public Duration fallback() {
        return fallback;
    }

    /** The default as the pass wrote it, which the settings catalogue entry renders. */
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
