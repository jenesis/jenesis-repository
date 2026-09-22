package build.jenesis.repository.maintenance;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Durations;

/**
 * One task's cadence dial: the config key a {@link MaintenanceTaskProvider} reads its {@link MaintenanceTask#interval()
 * interval} from, the product default a deployment gets when it is unset, and the one parse that turns the stored
 * string into a positive {@link Duration}. A provider holds one of these as a constant and calls
 * {@link #resolve(UnaryOperator)} inside {@code create}, so the deployment has exactly one answer to "how often does
 * this pass run" instead of the four it grew - a guarded ISO-8601 parse, an unguarded one, an unguarded
 * {@code Long.parseLong} over a {@code *-interval-millis} key, and one hard-coded constant with no key at all.
 *
 * <p><strong>{@code resolve} never throws.</strong> That is the property the whole type exists for.
 * {@link MaintenanceTaskProvider#resolve(UnaryOperator)} calls every discovered provider's {@code create} in one
 * uncontained loop, and the application constructs the scheduler from it at boot - so an unchecked throw
 * out of one provider's cadence parse does not disable one pass, it drops <em>every</em> pass and fails the server's
 * startup. A malformed value therefore falls back to {@link #fallback()} and says so once (see below); it never
 * escapes.
 *
 * <p><strong>What parses.</strong> An ISO-8601 duration ({@code PT1H}, {@code PT10M}, {@code P1D}) and the suffixed
 * style an operator reasonably expects from a Spring-bound environment variable ({@code 500ms}, {@code 90s},
 * {@code 5m}, {@code 6h}, {@code 2d}) - the deployment's one grammar, {@link Durations}. A <em>bare</em> number is
 * deliberately <em>not</em> a duration: Spring's relaxed binding reads it as milliseconds where an operator usually
 * means seconds (and three of the product's own dials once read it that way), so a cadence dial refuses it rather
 * than silently picking a winner that is wrong by a factor of a thousand. A {@link #millis(String, String)
 * millis-keyed} dial is the one place a bare number is unambiguous - its unit is in the key - and there it is read
 * as milliseconds, while an ISO-8601 or suffixed value entered into such a key is still honoured rather than
 * discarded.
 *
 * <p><strong>Zero does not disable a pass.</strong> A non-positive cadence ({@code PT0S}, {@code -PT1H}, {@code 0})
 * falls back exactly like a malformed one. Disabling a maintenance pass has two routes and gains no third here: the
 * neutral {@code jenreg.<task>=false} toggle (plus the required-config self-disable) that
 * {@link MaintenanceTaskProvider#resolve} applies before a provider is even asked, and the provider's own enablement
 * setting, which returns an empty {@code create}. Letting a cadence of zero mean "off" would give one dial two
 * meanings and turn a typo into a pass that is installed, listed and never runs - the silently-incomplete state
 * &sect;5 forbids. This is deliberately <em>not</em> the convention the retention-age dials use
 * ({@link RetentionSetting}), where a zero {@code ttl}/{@code max-age} does mean "reap nothing": that is a policy
 * value, not a cadence, and the two families stay distinct.
 *
 * <p><strong>One line, once.</strong> A rejected value is logged at {@code WARNING} naming the key, the offending
 * value and the cadence actually in effect - the &sect;9 shape {@code GateDimension} landed for the gate dials, since
 * an operator whose sweep quietly runs hourly instead of every ten minutes otherwise learns nothing. It is announced
 * once per {@code key=value} pair, because the enabled task list is re-resolved on every settings-convergence tick and
 * a bad dial would otherwise print a line every thirty seconds; the same log-once idiom (and the same reason) as the
 * feature-activation announcement.
 *
 * <p><strong>A ceiling, only where the cadence is a safety bound.</strong> Most cadences are a cost/freshness trade an
 * operator owns outright: a sweep set to run yearly is a slow sweep, and slow is their decision. A few are not - they
 * are the <em>only</em> mechanism that bounds an exposure, and there a large enough value does not slow the mechanism
 * down, it removes it. {@link #atMost(Duration)} marks such a dial, and a configured value above the ceiling is
 * clamped to the ceiling and announced, rather than falling back: the operator asked for "as infrequent as possible"
 * and gets the least frequent value that still bounds the thing, which is the answer nearest their intent.
 * {@code index-rebase-interval} is the one dial with a ceiling today - it is what re-screens a withheld path
 * out of an immutable, consumer-cached index chunk when the live retraction signal was lost, and at {@code P3650D} that
 * repair never runs. The contrast with {@code forwarding-repair-interval}, which deliberately has none, is the rule:
 * a dial whose extreme value costs <em>timeliness</em> takes no ceiling, a dial whose extreme value costs the
 * <em>bound itself</em> does. This is a judgement per dial, not something a scan can infer from a type.
 *
 * <p><strong>The default lives here.</strong> Today each cadence default is written twice - once in the module's
 * {@code SettingsContributor} catalogue entry and once as the provider's fallback constant - so the two can drift.
 * A provider that holds its dial as one {@code IntervalSetting} constant renders the catalogue entry from
 * {@link #key()} and {@link #fallback()}, and the divergence stops being possible rather than being tested for.
 */
public final class IntervalSetting {

    private static final System.Logger LOGGER = System.getLogger(IntervalSetting.class.getName());

    private final String key;
    private final String fallbackText;
    private final Duration fallback;
    private final boolean bareIsMillis;
    private final Duration ceiling;

    private IntervalSetting(String key, String fallbackText, boolean bareIsMillis, Duration ceiling) {
        this(key, fallbackText, parseFallback(key, fallbackText), bareIsMillis, ceiling);
    }

    /** Parse a dial's own default. Unlike {@link #resolve} this is NOT operator input - it is a literal a provider
     *  shipped - so a value that does not parse is a programming error and throws where it is written. */
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
            // Not operator input: a provider shipping a zero or negative default is a programming error, and it must
            // fail where it is written rather than resolve to a pass that spins on every worker iteration.
            throw new IllegalArgumentException("The default cadence for " + key + " must be positive, was " + fallback);
        }
        if (ceiling != null && ceiling.compareTo(fallback) < 0) {
            // Also a programming error, and a silent one: the product default would itself be clamped, so every
            // deployment would run at a cadence the provider never declared.
            throw new IllegalArgumentException("The maximum cadence for " + key + " (" + ceiling + ") must not be "
                    + "below its default " + fallback);
        }
        this.key = key;
        this.fallbackText = fallbackText;
        this.fallback = fallback;
        this.bareIsMillis = bareIsMillis;
        this.ceiling = ceiling;
    }

    /** A cadence read from an ISO-8601 / suffixed dial ({@code cleanup-interval=PT1H}, {@code =6h}).
     *
     *  <p>The default is given as <strong>text</strong> rather than a {@link Duration}, and that is not a matter of
     *  taste: the settings reference is extracted from the compiled contributor, and a value built at run time -
     *  {@code Duration.ofHours(1).toString()} - is invisible to it, so the published row rendered {@code (computed)}
     *  and told an operator nothing about the cadence it was documenting. A {@code String} literal reaches the
     *  constant pool, so the value a deployment gets and the value the reference prints are the same characters. */
    public static IntervalSetting of(String key, String fallback) {
        return new IntervalSetting(key, fallback, false, null);
    }

    /** A cadence read from a dial whose unit is in its name ({@code scan-interval-millis=3600000}), so a bare number
     *  is unambiguous there; an ISO-8601 or suffixed value entered into it is still honoured. The default is text,
     *  for the reason {@link #of(String, String)} gives - and is written in the same grammar, not as a bare number,
     *  so every dial's default reads alike whatever its key is named. */
    public static IntervalSetting millis(String key, String fallback) {
        return new IntervalSetting(key, fallback, true, null);
    }

    /**
     * The same dial with a maximum: a configured value above {@code ceiling} is clamped to it and announced. Declare
     * one only where the cadence is the bound on an exposure rather than a cost/freshness trade (see the class
     * javadoc) - a ceiling on an ordinary sweep would take a decision away from the operator that is theirs to make.
     * Must not be below the product default, or every deployment would silently run clamped.
     */
    public IntervalSetting atMost(Duration ceiling) {
        return new IntervalSetting(key, fallbackText, fallback, bareIsMillis,
                Objects.requireNonNull(ceiling, "ceiling"));
    }

    /** The config key this cadence is read from - also what the module's settings catalogue entry renders. */
    public String key() {
        return key;
    }

    /** The product default a deployment runs at when the dial is unset, malformed or non-positive. Always
     *  positive. A module's settings catalogue entry renders {@link #fallbackText()} rather than this. */
    public Duration fallback() {
        return fallback;
    }

    /** The product default exactly as the provider wrote it - the string a settings catalogue entry renders, so the
     *  reference prints the characters a deployment would have to type to get the same cadence. */
    public String fallbackText() {
        return fallbackText;
    }

    /** The product default as a plain number of milliseconds, for a {@link #millis(String, String) millis-keyed}
     *  dial whose catalogue entry is a {@code LONG}: what an operator edits there is a number, and a duration
     *  string in a number field is a default nobody could type back. Derived from the same literal
     *  {@link #fallbackText()} returns, so the two spellings cannot disagree. */
    public String fallbackMillis() {
        return Long.toString(fallback.toMillis());
    }

    /** The maximum this dial may be set to, for the few cadences that bound an exposure rather than trade cost against
     *  freshness ({@link #atMost(Duration)}), or empty for the ordinary majority. The module's settings catalogue entry
     *  renders it into the setting's description from here, so the bound an operator is held to and the bound they are
     *  told about are one value. */
    public Optional<Duration> ceiling() {
        return Optional.ofNullable(ceiling);
    }

    /**
     * The cadence this deployment runs the pass at: the configured value when it parses to a positive duration, the
     * {@link #fallback()} otherwise, and never more than a declared {@link #ceiling()}. Never throws and never returns
     * a non-positive duration, so a provider can call it from {@code create} without putting the whole maintenance
     * resolve - and the server's startup - behind an operator's typo.
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
                // Not a bare number: an operator who wrote PT1H into a millis-named key meant an hour, not a failure.
            }
        }
        // The shared grammar, in the module the console's validator also reads it from, so the message this dial
        // prints and the values the settings screen accepts cannot drift apart again.
        return Durations.parse(value);
    }


    /** Report a clamped dial once and run at the ceiling. Deliberately not a fallback: the operator asked for the
     *  least frequent pass they could get, and the ceiling is the least frequent one that still bounds what this
     *  cadence bounds - dropping them back to the product default would override an intent that is legitimate up to
     *  the bound. The line says what was asked for, what runs, and why the dial has a maximum at all, because an
     *  operator who is not told will read the setting back, see their own value stored, and believe it. */
    private Duration clamp(String value) {
        if (Announced.first(key, value)) {
            LOGGER.log(System.Logger.Level.WARNING, Features.key(key) + "=" + value + " exceeds the maximum "
                    + ceiling + " for this dial; the pass runs every " + ceiling + ". This cadence is not a cost "
                    + "trade-off but the bound on an exposure - past the maximum the repair it paces stops happening "
                    + "at all rather than merely happening later - so it is capped instead of honoured.");
        }
        return ceiling;
    }

    /** Report a rejected dial once and fall back, so a sweep running at the wrong cadence is visible rather than
     *  merely wrong. */
    private Duration announce(String value, String why) {
        if (Announced.first(key, value)) {
            LOGGER.log(System.Logger.Level.WARNING, Features.key(key) + "=" + value + " " + why
                    + "; the pass runs every " + fallback + ". Accepted: an ISO-8601 duration (PT1H, P1D) or a "
                    + "suffixed one (500ms, 90s, 5m, 6h, 2d)" + (bareIsMillis ? " or a plain number of milliseconds" : "")
                    + ". A cadence of zero does not disable a pass - use jenreg.<task>=false or the "
                    + "task's own enablement setting.");
        }
        return fallback;
    }
}
