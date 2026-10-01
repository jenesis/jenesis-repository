package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Durations;

/**
 * One task's cadence dial: the config key a {@link MaintenanceTaskProvider} reads its {@link MaintenanceTask#interval()
 * interval} from, the default a deployment gets when it is unset, and the parse that turns the stored string into a
 * positive {@link Duration}. A provider holds one as a constant, calls {@link #resolve(UnaryOperator)} inside
 * {@code create}, and renders its settings catalogue entry from {@link #key()} and {@link #fallbackText()}, so the
 * default has one definition.
 *
 * <p><strong>{@code resolve} never throws.</strong> {@link MaintenanceTaskProvider#resolve(UnaryOperator)} is strict at
 * boot, so a throwing cadence parse would fail the server's startup. A malformed value falls back to
 * {@link #fallback()} instead.
 *
 * <p><strong>What parses.</strong> The deployment's duration grammar, {@link Durations}: ISO-8601 ({@code PT1H},
 * {@code P1D}) or suffixed ({@code 500ms}, {@code 90s}, {@code 5m}, {@code 6h}, {@code 2d}). A bare number is refused,
 * because Spring's relaxed binding reads it as milliseconds where an operator usually means seconds; only a
 * {@link #millis(String, String) millis-keyed} dial, whose unit is in its key, reads a bare number as milliseconds.
 *
 * <p><strong>Zero does not disable a pass.</strong> A non-positive cadence falls back like a malformed one: disabling
 * is the {@code jenrepo.<task>=false} toggle or the provider's own enablement setting, and a zero meaning "off" would
 * turn a typo into a pass that is listed and never runs. The retention-age dials ({@link RetentionSetting}) differ on
 * purpose: there zero is a policy value meaning "reap nothing".
 *
 * <p><strong>One line, once.</strong> A rejected value is logged at {@code WARNING} with the key, the value and the
 * cadence in effect, once per {@code key=value} pair, since the task list is re-resolved on every settings-convergence
 * tick.
 *
 * <p><strong>A ceiling, only where the cadence is a safety bound.</strong> Most cadences trade cost against freshness
 * and belong to the operator. A dial that is the only bound on an exposure declares {@link #atMost(Duration)}, and a
 * value above it is clamped and announced, since past the ceiling the mechanism stops running rather than running
 * later. {@code index-rebase-interval}, which re-screens a withheld path out of an immutable index chunk, is one.
 */
public final class IntervalSetting {

    private static final System.Logger LOGGER = System.getLogger(IntervalSetting.class.getName());

    /** How often the passes over the advisory and health feeds run; every pass of the scan family reads this one dial.
     *  Hourly by default, since each pass hits the upstream feeds. */
    public static final IntervalSetting SCANS = millis("scan-interval-millis", "PT1H");

    /** How often the storage reapers run - cleanup, quarantine retention, staging reap, and the telemetry and audit
     *  retentions. One dial paces the whole family, hourly by default; each reaper lists only its own small space. */
    public static final IntervalSetting CLEANUP = of("cleanup-interval", "PT1H");

    private final String key;
    private final String fallbackText;
    private final Duration fallback;
    private final boolean bareIsMillis;
    private final Duration ceiling;

    private IntervalSetting(String key, String fallbackText, boolean bareIsMillis, Duration ceiling) {
        this(key, fallbackText, parseFallback(key, fallbackText), bareIsMillis, ceiling);
    }

    /** Parses a dial's own default, a literal the provider ships, so a value that does not parse throws. */
    private static Duration parseFallback(String key, String fallbackText) {
        if (fallbackText == null || fallbackText.isBlank()) {
            throw new IllegalArgumentException("The default cadence for " + key + " must be given as a duration");
        }
        try {
            return Durations.parse(fallbackText.trim());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("The default cadence for " + key + " is not a duration: "
                    + fallbackText, malformed);
        }
    }

    private IntervalSetting(String key, String fallbackText, Duration fallback, boolean bareIsMillis,
                            Duration ceiling) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A cadence dial needs a config key");
        }
        if (fallback == null || !fallback.isPositive()) {
            throw new IllegalArgumentException("The default cadence for " + key + " must be positive, was " + fallback);
        }
        if (ceiling != null && ceiling.compareTo(fallback) < 0) {
            throw new IllegalArgumentException("The maximum cadence for " + key + " (" + ceiling + ") must not be "
                    + "below its default " + fallback);
        }
        this.key = key;
        this.fallbackText = fallbackText;
        this.fallback = fallback;
        this.bareIsMillis = bareIsMillis;
        this.ceiling = ceiling;
    }

    /** A cadence read from an ISO-8601 or suffixed dial ({@code cleanup-interval=PT1H}). The default is text so that
     *  it is a constant the settings reference extractor can read. */
    public static IntervalSetting of(String key, String fallback) {
        return new IntervalSetting(key, fallback, false, null);
    }

    /** A cadence read from a dial whose unit is in its name ({@code scan-interval-millis=3600000}), where a bare number
     *  is milliseconds and a duration is still honoured. The default is text, as for {@link #of(String, String)}. */
    public static IntervalSetting millis(String key, String fallback) {
        return new IntervalSetting(key, fallback, true, null);
    }

    /**
     * The same dial with a maximum: a configured value above {@code ceiling} is clamped to it and announced. Only for a
     * cadence that bounds an exposure; must not be below the default.
     */
    public IntervalSetting atMost(Duration ceiling) {
        return new IntervalSetting(key, fallbackText, fallback, bareIsMillis,
                Objects.requireNonNull(ceiling, "ceiling"));
    }

    /** The config key this cadence is read from. */
    public String key() {
        return key;
    }

    /** The default a deployment runs at when the dial is unset, malformed or non-positive; always positive. */
    public Duration fallback() {
        return fallback;
    }

    /** The default as the provider wrote it, which the settings catalogue entry renders. */
    public String fallbackText() {
        return fallbackText;
    }

    /** The default in milliseconds, for a {@link #millis(String, String) millis-keyed} dial whose catalogue entry is a
     *  number. */
    public String fallbackMillis() {
        return Long.toString(fallback.toMillis());
    }

    /** The maximum declared by {@link #atMost(Duration)}, or empty; the catalogue entry renders it into the setting's
     *  description. */
    public Optional<Duration> ceiling() {
        return Optional.ofNullable(ceiling);
    }

    /**
     * The cadence this deployment runs the pass at: the configured value when it parses to a positive duration, the
     * {@link #fallback()} otherwise, and never more than a declared {@link #ceiling()}. Never throws.
     */
    public Duration resolve(UnaryOperator<String> config) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        Duration parsed;
        try {
            parsed = parse(trimmed);
        } catch (RuntimeException malformed) {
            return announce(trimmed, "is not a duration");
        }
        if (!parsed.isPositive()) {
            return announce(trimmed, "is not a positive duration");
        }
        return ceiling != null && parsed.compareTo(ceiling) > 0 ? clamp(trimmed) : parsed;
    }

    /** ISO-8601 first, then the suffixed style - and, for a millis-keyed dial, a bare number before either. */
    private Duration parse(String value) {
        if (bareIsMillis) {
            try {
                return Duration.ofMillis(Long.parseLong(value));
            } catch (NumberFormatException _) {
                // Not a bare number; PT1H in a millis-named key still means an hour.
            }
        }
        return Durations.parse(value);
    }

    /** Reports a clamped dial once and runs at the ceiling, the value nearest what the operator asked for. */
    private Duration clamp(String value) {
        if (Announced.first(key, value)) {
            LOGGER.log(System.Logger.Level.WARNING, Features.key(key) + "=" + value + " exceeds the maximum "
                    + ceiling + " for this dial; the pass runs every " + ceiling + ". This cadence is not a cost "
                    + "trade-off but the bound on an exposure - past the maximum the repair it paces stops happening "
                    + "at all rather than merely happening later - so it is capped instead of honoured.");
        }
        return ceiling;
    }

    /** Reports a rejected dial once and falls back. */
    private Duration announce(String value, String why) {
        if (Announced.first(key, value)) {
            LOGGER.log(System.Logger.Level.WARNING, Features.key(key) + "=" + value + " " + why
                    + "; the pass runs every " + fallback + ". Accepted: an ISO-8601 duration (PT1H, P1D) or a "
                    + "suffixed one (500ms, 90s, 5m, 6h, 2d)" + (bareIsMillis ? " or a plain number of milliseconds" : "")
                    + ". A cadence of zero does not disable a pass - use jenrepo.<task>=false or the "
                    + "task's own enablement setting.");
        }
        return fallback;
    }
}
