package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;

/**
 * The {@code provenance} summary section codec of the consolidated metadata document: the per-coordinate
 * <em>summary</em> of a version's provenance - whether its attestation verified against a trusted signer and the
 * SHA-256 of the subject it bound - pointing at, but never duplicating, the content-keyed attestation cache
 * ({@code provenance-attestation/<sha256>/<path digest>}) that stays separate by design (§8). Unlike the {@code licenses} and
 * {@code published} sections, this summary has no prior sidecar to migrate: it is newly derived at publish from the
 * gate's attestation verdict, so the cutover ships its codec and its publish-time write with no migration sweep.
 *
 * <p>The {@code data} payload is {@code {"verified":<bool>, "sha256":<hex>}}. The section's {@link Signal} realises the
 * §6 gate-mirror at the envelope: an <em>unverified</em> summary carries a <strong>non-blocking WARNING</strong>
 * signal, a verified one is neutral. The {@link Severity} enum has no {@code WARNING} band, so the warning maps to
 * {@link Severity#LOW} - the lowest visible band, deliberately below any "reject HIGH and above" gate threshold, so the
 * signal surfaces and can contribute to a verdict without ever hard-failing a release on its own. Admission enforcement
 * (holding an unsigned artifact) stays the gate's own {@code AttestationPolicy} hold path, untouched; this summary is a
 * durable, GUI-facing fact plus a soft signal, not a second blocking verdict. All methods are pure and return a fresh
 * {@link Section} (§11).
 */
public final class ProvenanceSection {

    /** The section tag - the short built-in name the provenance-summary subsystem owns in the document. */
    public static final String TAG = "provenance";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String VERIFIED_FIELD = "verified";
    private static final String SHA256_FIELD = "sha256";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProvenanceSection() {
    }

    /** The provenance summary a section carries, or empty when the version has no derived provenance section. */
    public static Optional<Summary> summary(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        return section.get().payload().map(data -> new Summary(
                data.path(VERIFIED_FIELD).asBoolean(false),
                text(data.path(SHA256_FIELD))));
    }

    /** A version's provenance summary: whether the attestation verified, and the SHA-256 hex of the subject it bound
     *  (empty when the attestation carried no subject digest). */
    public record Summary(boolean verified, String sha256) {
    }

    /** Record the provenance summary derived at publish; idempotent and re-derivable each CAS attempt. */
    public static SectionMutation record(boolean verified, String sha256, Instant updated) {
        return current -> section(verified, sha256, updated);
    }

    /** A provenance summary section for the given verdict: neutral when verified, a non-blocking WARNING
     *  ({@link Severity#LOW}) when not (§6 gate-mirror). */
    public static Section section(boolean verified, String sha256, Instant updated) {
        ObjectNode data = JSON.createObjectNode();
        data.put(VERIFIED_FIELD, verified);
        if (sha256 == null || sha256.isBlank()) {
            data.putNull(SHA256_FIELD);
        } else {
            data.put(SHA256_FIELD, sha256);
        }
        Signal signal = verified ? Signal.NEUTRAL : Signal.of(Severity.LOW);
        return Section.derived(TAG, SCHEMA, updated, signal, data);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }
}
