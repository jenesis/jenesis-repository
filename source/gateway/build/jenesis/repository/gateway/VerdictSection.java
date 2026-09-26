package build.jenesis.repository.gateway;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code verdict} section codec of the consolidated metadata document: the durable,
 * <b>digest-pinned</b> record of what the hardened proxy leg ({@link HardenedScreen}) decided over a fully-spooled
 * upstream body. Where the {@code published}/{@code licenses}/{@code provenance} sections are per coordinate version,
 * this section is bound to the artifact's <em>content digest</em> (SHA-256): the recorded verdict applies only to the
 * exact bytes it screened, so a coordinate whose upstream content changed under it (a mutated tag, a re-published
 * snapshot) never reuses a verdict reached over different bytes - the reuse is digest-exact, and any digest mismatch
 * re-screens (no coordinate-level reuse across changed content).
 *
 * <p>The {@code data} payload is
 * {@code {"digest":"sha256:<hex>", "verdict":"ALLOW|QUARANTINE|REJECT", "refusal":<name|null>,
 * "screenedAt":<instant>, "profile":<tier>, "source":<upstream|fallback>, "inspectionLimit":<bytes>,
 * "validators":[{"name":<inspector>, "version":<version|null>}, ...]}}. The verdict captures which typed disposition
 * the leg reached (an {@link Verdict#ALLOW} or a withholding), the optional named structural {@code refusal} when the
 * body could not be screened, when it was {@code screenedAt}, the screening {@code profile} (which tier/policy), the
 * {@code source} the bytes came from (which upstream or the local-store repair), and the {@code validators} that ran
 * (the claiming inspectors, with versions where a validator supplies one). All methods are pure and return a fresh
 * {@link Section} folded from the arguments, re-derivable on each CAS attempt.
 *
 * <p>The section's {@link Signal} realises the §6 gate-mirror at the envelope: an {@code ALLOW} verdict is neutral, a
 * withholding or a refusal carries a {@link Severity#HIGH} signal so a consumer that does not parse {@code data} still
 * sees the coordinate was withheld.
 *
 * <p><b>Composition with the {@code origin} section.</b> The verdict record and the
 * {@code OriginSection origin} acquisition rows are siblings in the same document: the verdict answers "what the screen
 * decided about digest D", origin answers "where D came from". They are reconciled - not duplicated - by the
 * <em>digest</em>: this record's {@code digest} ({@code sha256:<hex>}) names the same bytes an origin {@code fallback}
 * row's {@code sha256} ({@code <hex>}) does, and this record's {@code source} (the fetched upstream URL) is the same
 * value that row's {@code target} carries. A reader joins the two by digest rather than re-deriving the provenance from
 * either alone.
 */
public final class VerdictSection {

    /** The section tag - the short built-in name the hardened screening verdict owns in the document. */
    public static final String TAG = "verdict";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String DIGEST_FIELD = "digest";
    private static final String VERDICT_FIELD = "verdict";
    private static final String REFUSAL_FIELD = "refusal";
    private static final String SCREENED_AT_FIELD = "screenedAt";
    private static final String PROFILE_FIELD = "profile";
    private static final String SOURCE_FIELD = "source";
    private static final String VALIDATORS_FIELD = "validators";
    private static final String INSPECTION_LIMIT_FIELD = "inspectionLimit";
    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";

    /** The digest is pinned with an explicit algorithm prefix so the record is unambiguous across a later hash change. */
    private static final String DIGEST_ALGORITHM = "sha256:";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private VerdictSection() {
    }

    /** A validator that ran over the artifact: the inspector/scanner name and its version where it supplies one
     *  (absent as {@code null} when the validator carries no version). */
    public record Validator(String name, String version) {

        public Validator {
            name = Objects.requireNonNull(name, "name");
        }

        /** A validator that reports no version. */
        public static Validator of(String name) {
            return new Validator(name, null);
        }
    }

    /** The verdict a section carries, digest-pinned to the exact bytes it screened. */
    public record Recorded(String digest, Verdict verdict, String refusal, Instant screenedAt, String profile,
                           String source, List<Validator> validators, long inspectionLimit) {

        public Recorded {
            validators = validators == null ? List.of() : List.copyOf(validators);
        }

        /** How completely the screen behind this verdict looked: the full-body inspection ceiling in force when it
         *  ran, or {@code 0} for a record written before completeness travelled with the answer. */
        public boolean completeAt(long currentLimit) {
            return inspectionLimit >= currentLimit;
        }

        /** Whether this record is an {@code ALLOW} verdict pinned to exactly {@code digest} - the digest-exact reuse
         *  test the hardened leg keys its hot-path dedup off (§7). A withholding, a refusal, or a verdict over
         *  different bytes never reuses. */
        public boolean allows(String digest) {
            return verdict == Verdict.ALLOW && this.digest != null && this.digest.equals(pinned(digest));
        }

        /**
         * The reuse test, with completeness: an {@code ALLOW} over exactly these bytes, reached by a screen that
         * looked at least as far as this deployment now looks.
         *
         * <p>Digest-exactness alone is not enough, and the gap is sharp. The verdict pins <em>what</em> was decided
         * about the bytes; it did not carry <em>how completely we looked</em> at them. So an ALLOW reached while the
         * full-body ceiling was 64 MiB was reused digest-exactly after an operator raised that ceiling - and raising
         * it is the one change that would have let the screen finish. The artifact whose tail was never inspected is
         * precisely the one the operator raised the tier to inspect, and it was the one guaranteed not to be
         * re-screened.
         *
         * <p>A record written before this field existed carries {@code 0} and so never satisfies a positive
         * ceiling: it re-screens once, which is the fail-closed direction and costs one inspection.
         */
        public boolean allows(String digest, long currentLimit) {
            return allows(digest) && completeAt(currentLimit);
        }

        /** Whether this record pins exactly {@code digest}, regardless of verdict - the drift baseline: a
         *  re-fetch of an immutable coordinate whose bytes do NOT match the pinned digest is upstream drift/tampering.
         *  A record with no readable digest never pins, so a torn record is treated as no baseline (re-screen), not a
         *  false drift alarm. */
        public boolean pins(String digest) {
            return this.digest != null && this.digest.equals(pinned(digest));
        }
    }

    /** The verdict a section carries, or empty when the section is absent or not a derived verdict record. */
    public static Optional<Recorded> recorded(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return Optional.empty();
        }
        return section.get().payload().map(data -> new Recorded(
                text(data.path(DIGEST_FIELD)),
                verdict(data.path(VERDICT_FIELD)),
                text(data.path(REFUSAL_FIELD)),
                instant(data.path(SCREENED_AT_FIELD)),
                text(data.path(PROFILE_FIELD)),
                text(data.path(SOURCE_FIELD)),
                validators(data.path(VALIDATORS_FIELD)),
                data.path(INSPECTION_LIMIT_FIELD).asLong(0L)));
    }

    /** Whether a section records an {@code ALLOW} verdict pinned to exactly {@code digest} - the digest-exact reuse
     *  check, false for an absent section, a withholding, a refusal, or a verdict over different bytes. */
    public static boolean allows(Optional<Section> section, String digest) {
        return recorded(section).map(recorded -> recorded.allows(digest)).orElse(false);
    }

    /** {@link #allows(Optional, String)} with the completeness test - see {@link Recorded#allows(String, long)}. */
    public static boolean allows(Optional<Section> section, String digest, long currentLimit) {
        return recorded(section).map(recorded -> recorded.allows(digest, currentLimit)).orElse(false);
    }

    /**
     * Record the hardened leg's digest-pinned verdict: idempotent (re-recording the same screen of the same bytes
     * converges) and re-derivable on each CAS attempt. A withholding or a refusal is recorded too, so the audit is
     * complete and a subsequent fetch of the same bytes re-screens fail-closed rather than reusing a non-{@code ALLOW}.
     */
    public static SectionMutation record(String digest, Verdict verdict, String refusal, String profile, String source,
                                         List<Validator> validators, Instant screenedAt, long inspectionLimit) {
        return current -> section(digest, verdict, refusal, profile, source, validators, screenedAt, inspectionLimit);
    }

    /** A verdict section for the given digest-pinned decision. */
    public static Section section(String digest, Verdict verdict, String refusal, String profile, String source,
                                  List<Validator> validators, Instant screenedAt, long inspectionLimit) {
        ObjectNode data = JSON.createObjectNode();
        data.put(DIGEST_FIELD, pinned(digest));
        data.put(VERDICT_FIELD, verdict.name());
        if (refusal == null) {
            data.putNull(REFUSAL_FIELD);
        } else {
            data.put(REFUSAL_FIELD, refusal);
        }
        data.put(SCREENED_AT_FIELD, screenedAt.toString());
        data.put(PROFILE_FIELD, profile);
        data.put(SOURCE_FIELD, source);
        // How completely we looked, beside what we decided. Without it a raised ceiling cannot invalidate a
        // verdict the old ceiling cut short.
        data.put(INSPECTION_LIMIT_FIELD, inspectionLimit);
        ArrayNode validatorsNode = data.putArray(VALIDATORS_FIELD);
        for (Validator validator : validators) {
            ObjectNode node = validatorsNode.addObject();
            node.put(NAME_FIELD, validator.name());
            if (validator.version() == null) {
                node.putNull(VERSION_FIELD);
            } else {
                node.put(VERSION_FIELD, validator.version());
            }
        }
        Signal signal = verdict == Verdict.ALLOW ? Signal.NEUTRAL : Signal.of(Severity.HIGH);
        return Section.derived(TAG, SCHEMA, screenedAt, signal, data);
    }

    /** The stored digest form - the SHA-256 hex prefixed with its algorithm, tolerant of an already-prefixed value. */
    private static String pinned(String digest) {
        return digest.startsWith(DIGEST_ALGORITHM) ? digest : DIGEST_ALGORITHM + digest;
    }

    private static Verdict verdict(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Verdict.REJECT;   // a record with no readable verdict is treated as a withholding, never an ALLOW
        }
        try {
            return Verdict.valueOf(node.asString());
        } catch (IllegalArgumentException e) {
            return Verdict.REJECT;
        }
    }

    private static List<Validator> validators(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<Validator> validators = new ArrayList<>();
        for (JsonNode entry : node) {
            String name = text(entry.path(NAME_FIELD));
            if (name != null) {
                validators.add(new Validator(name, text(entry.path(VERSION_FIELD))));
            }
        }
        return validators;
    }

    private static Instant instant(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }
}
