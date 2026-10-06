package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.Checksums;

/**
 * What a screen does with what it found and with what it could not find out: a repository's dial, read per screening
 * through the effective lookup of the repository screened ({@link #of}).
 *
 * <p>An advisory feed that cannot answer raises rather than reporting an empty, clean list, because the source cannot
 * tell what its caller wants; this is the caller's answer. The deny-list is not governed by it: an operator's list of
 * coordinates is a refusal the operator already made, not a finding the screen reached, so it holds or refuses in
 * every mode. A proxy repository whose fallback is hardened screens strictly in every mode: hardening is the explicit
 * opt-in to full screening of an untrusted upstream, and it reuses a recorded verdict to skip re-screening, which a
 * decision reached around an outage must not become.
 */
public enum ScreeningMode {

    /** Fail closed, and the default: an artifact a feed could not clear is held for review with the outage named, and
     *  every finding holds or refuses as its dimension's action says. */
    HOLD,

    /** Fail open on an outage: the artifact is decided by every dimension that could answer and served when they
     *  allow it, with the check that could not answer recorded among its reasons. What it found still holds or
     *  refuses as in {@link #HOLD}, and one that no dimension could decide is held. */
    ADMIT,

    /** Never hold on what the screen found or could not find: every finding is recorded and the artifact is served,
     *  the audit mode a repository runs while screening is rolled out over what it already serves. The passes that
     *  hold retroactively - the known-exploited enforcement, the licence and signature sweeps - place no hold either,
     *  leaving what they found to the findings it is recorded as. Only the deny-list
     *  still bites, so an artifact no check could decide at all - the deny-list's answer unknown with the rest - is
     *  held as in {@link #ADMIT}. */
    RECORD;

    /** The repository setting carrying the mode. */
    public static final String KEY = "screening-mode";

    /** Where a repository marks a copy it served while a feed could not answer: one {@link Properties} document per
     *  copy, named by the digest of its path and carrying the path under {@code path}, cleared or turned into a hold
     *  once a feed answers. Every surface that shows a version reads it, so the key is composed here once. */
    public static final String PENDING_ROOT = "screen-pending";

    /** The marker of the copy at {@code path} - see {@link #PENDING_ROOT}. */
    public static String pendingKey(String path) {
        return PENDING_ROOT + "/" + Checksums.sha256(path);
    }

    /** The mode with nothing set, in the form the setting catalogue publishes. */
    public static final String DEFAULT = "HOLD";

    /** The mode {@code config} names, {@link #DEFAULT} where it names none. A value naming no mode throws, naming the
     *  key: a write is validated against the declared choices, so one reaching here is a configuration error. */
    public static ScreeningMode of(UnaryOperator<String> config) {
        String value = config == null ? null : config.apply(KEY);
        if (value == null || value.isBlank()) {
            value = DEFAULT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(KEY + " names no screening mode: " + value, unknown);
        }
    }

    /** Whether a feed that could not answer leaves the artifact to the dimensions that could, rather than holding it. */
    public boolean admitsOutage() {
        return this != HOLD;
    }

    /** The reason an artifact screened while a feed could not answer carries, beside the outage itself - one wording
     *  for a publish and a proxied fill alike. */
    public static final String ADMITTED_REASON = "Admitted on the checks that could answer, as the repository's "
            + "screening mode says, while an advisory feed cannot";

    /** The reason an artifact the screen would have withheld carries under {@link #RECORD}. */
    public static final String RECORDED_REASON = "Recorded, not held: the repository's screening mode records what the "
            + "screen finds and holds none of it";

    /** {@link #ADMITTED_REASON} for the outage {@code failure} reports - see {@link #outage}. */
    public static String admitted(RuntimeException failure) {
        return ADMITTED_REASON + ": " + outage(failure);
    }

    /** What a feed's {@code failure} says went wrong: the message of what it wraps, or its own, or - for a failure
     *  that carries none - the name of what failed, so a reason never reads as {@code null}. */
    public static String outage(RuntimeException failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
