package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;

/**
 * The {@code signatures} summary section of the consolidated metadata document: the per-coordinate record of what a
 * version's publisher signature turned out to be, so a screen can say who vouched for these bytes without re-reading
 * the artifact or re-running any cryptography.
 *
 * <p>It is the signature twin of {@link ProvenanceSection}, and the two are deliberately separate rather than one
 * "trust" section. An attestation is a claim about how an artifact was <em>built</em> - which pipeline, from which
 * source - and a publisher signature is a claim about <em>these bytes</em> by whoever holds a key. An artifact can
 * easily have one and not the other, and folding them together would make the absent half look like a failure of the
 * present one.
 *
 * <p>Each file of the version keeps its own summary, and the version's is the one whose outcome is worst by
 * {@link ComplianceGate.Signature#worse}: a version is as trustworthy as its least trustworthy file, so a signed jar
 * beside an unsigned POM reads as unsigned whichever arrived last. The {@code data} payload is
 * {@code {"outcome":<name>, "signer":<wire>, "grade":<name>, "location":<path>, "source":<trust source>,
 * "details":{<name>:<value>}, "files":{<path>:{<the same fields>}}}} - source and details optional, so a record
 * carrying neither reads as unknown rather than as a signature that said nothing. The
 * section's {@link Signal} realises the gate-mirror at the envelope: a signature that verified by a trusted signer is
 * neutral, and anything else carries a non-blocking signal. {@link Severity} has no WARNING band, so an untrusted,
 * absent or unreadable signature maps to {@link Severity#LOW} - visible, below any "reject HIGH and above" threshold,
 * and deliberately unable to fail a release on its own. An <em>invalid</em> signature is the exception and carries
 * {@link Severity#HIGH}: bytes that do not match the signature made for them is not a documentation gap, it is either
 * corruption or tampering, and a summary that whispered it at the same volume as "this publisher does not sign"
 * would be actively misleading.
 *
 * <p>Enforcement is not this section's job and never becomes it - holding an artifact stays the gate dimension's
 * path. This is a durable, GUI-facing fact plus a soft signal, so the console can show what was found while an
 * operator decides whether to gate on it. All methods are pure and return a fresh {@link Section}.
 */
public final class SignatureSection {

    /** The section tag - the short built-in name the signature-summary subsystem owns in the document. */
    public static final String TAG = "signatures";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String OUTCOME_FIELD = "outcome";
    private static final String SIGNER_FIELD = "signer";
    private static final String GRADE_FIELD = "grade";
    private static final String LOCATION_FIELD = "location";
    private static final String SOURCE_FIELD = "source";
    private static final String DETAILS_FIELD = "details";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SignatureSection() {
    }

    /** The signature summary a section carries, or empty when the version has no derived signature section. */
    public static Optional<Summary> summary(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        return section.get().payload().map(SignatureSection::summary);
    }

    /** Each file's own summary, by its path in the repository, in path order; empty when the section holds none. */
    public static SequencedMap<String, Summary> files(Optional<Section> section) {
        SequencedMap<String, Summary> files = new LinkedHashMap<>();
        FileFacts.read(section).forEach((file, entry) -> files.put(file, summary(entry)));
        return files;
    }

    private static Summary summary(JsonNode data) {
        return new Summary(text(data.path(OUTCOME_FIELD)), text(data.path(SIGNER_FIELD)), text(data.path(GRADE_FIELD)),
                text(data.path(LOCATION_FIELD)), text(data.path(SOURCE_FIELD)), details(data.path(DETAILS_FIELD)));
    }

    /**
     * A version's signature summary: what verifying produced, who signed it in the scheme-neutral wire spelling, how
     * much the signature is worth, and where the material sat.
     *
     * <p>{@code signer} is kept even for an outcome nobody would call good - an untrusted or invalid signature still
     * names a key, and that name is what an operator needs in order to decide whether to trust it or to go looking
     * for what else it signed. A summary that dropped the signer on any outcome but success would answer "something
     * is wrong" and refuse to say with whom.
     *
     * <p>{@code source} is where the signer's trust came from and {@code details} what else the material stated -
     * a keyless signer's issuer and subject, its transparency-log index and integration time - each {@code null} or
     * empty on a record that does not carry them, which a reader shows as unknown rather than as absent.
     */
    public record Summary(String outcome, String signer, String grade, String location, String source,
                          Map<String, String> details) {

        public Summary {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        /** Whether this version's signature is one the deployment stood behind. */
        public boolean trusted() {
            return "VALID".equals(outcome);
        }

        /**
         * How the signer came to be believed, in the operator's words - the one wording every surface renders,
         * so the console panel, the API and the CLI cannot say it three ways; {@code null} where the record does
         * not say: a summary that carries no source, or a signature nobody admitted.
         */
        public String admittedBy() {
            if (source == null) {
                return null;
            }
            return switch (source) {
                case "configured" -> "a key or a pinned signer the operator configured";
                case "provenance" -> "the provenance of the repository this artifact declares - the signing "
                        + "workflow belongs to it, and its issuer is one the operator accepts";
                case "discovered" -> "a discovered key";
                default -> source;
            };
        }
    }

    /** Record {@code file}'s signature summary, replacing what the file had and keeping every other file's;
     *  idempotent and re-derivable on each compare-and-set attempt. */
    public static SectionMutation record(String file, String outcome, String signer, String grade, String location,
                                         String source, Map<String, String> details, Instant updated) {
        return current -> section(FileFacts.with(current, file,
                entry(outcome, signer, grade, location, source, details)), updated);
    }

    /** A section over {@code files}, its version-level fields those of the file whose outcome is worst - the first in
     *  path order among equals - and its signal the one that outcome warrants. */
    private static Section section(SortedMap<String, JsonNode> files, Instant updated) {
        JsonNode summarising = null;
        ComplianceGate.Signature.Outcome worst = null;
        for (JsonNode entry : files.values()) {
            ComplianceGate.Signature.Outcome outcome = outcome(entry);
            boolean worse = outcome != worst && ComplianceGate.Signature.worse(worst, outcome) == outcome;
            if (summarising == null || worse) {
                summarising = entry;
                worst = outcome;
            }
        }
        ObjectNode data = summarising == null ? JSON.createObjectNode() : ((ObjectNode) summarising).deepCopy();
        FileFacts.write(data, files);
        return Section.derived(TAG, SCHEMA, updated, signal(text(data.path(OUTCOME_FIELD))), data);
    }

    private static ObjectNode entry(String outcome, String signer, String grade, String location, String source,
                                    Map<String, String> details) {
        ObjectNode data = JSON.createObjectNode();
        put(data, OUTCOME_FIELD, outcome);
        put(data, SIGNER_FIELD, signer);
        put(data, GRADE_FIELD, grade);
        put(data, LOCATION_FIELD, location);
        put(data, SOURCE_FIELD, source);
        ObjectNode recorded = data.putObject(DETAILS_FIELD);
        if (details != null) {
            details.forEach((name, value) -> put(recorded, name, value));
        }
        return data;
    }

    /** The outcome an entry records, or {@code null} for one it does not name or that this node does not know. */
    private static ComplianceGate.Signature.Outcome outcome(JsonNode entry) {
        String name = text(entry.path(OUTCOME_FIELD));
        return name == null ? null : Arrays.stream(ComplianceGate.Signature.Outcome.values())
                .filter(outcome -> outcome.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * The envelope signal for an outcome: neutral when the signature verified and was believed, HIGH when it did not
     * verify, LOW for everything else.
     *
     * <p>The band is the whole editorial judgement here, and the split is between "nobody vouched" and "somebody's
     * vouch does not match the bytes". The first is the ordinary state of most of an ecosystem; the second happens to
     * artifacts that were altered. Ranking them the same would train an operator to ignore the one that matters.
     */
    private static Signal signal(String outcome) {
        if (outcome == null) {
            return Signal.of(Severity.LOW);
        }
        return switch (outcome) {
            case "VALID" -> Signal.NEUTRAL;
            case "INVALID" -> Signal.of(Severity.HIGH);
            default -> Signal.of(Severity.LOW);
        };
    }

    private static void put(ObjectNode data, String field, String value) {
        if (value == null || value.isBlank()) {
            data.putNull(field);
        } else {
            data.put(field, value);
        }
    }

    private static Map<String, String> details(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> details = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String value = text(entry.getValue());
            if (value != null) {
                details.put(entry.getKey(), value);
            }
        });
        return Map.copyOf(details);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }
}
