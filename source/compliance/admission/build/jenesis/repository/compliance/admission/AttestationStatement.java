package build.jenesis.repository.compliance.admission;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.Dsse;

/**
 * A parsed inbound attestation: a DSSE (Dead Simple Signing Envelope) wrapping an in-toto Statement, the shape cosign,
 * in-toto and the SLSA generators emit - on its own, one per line of a {@code .jsonl} bundle, or inside a Sigstore
 * bundle, which carries the same envelope under {@code dsseEnvelope} beside its verification material. It reads the
 * envelope and its statement, verifies the signature the way a consumer does - over the DSSE pre-authentication
 * encoding, against the tenant's configured trust-anchor keys, so a tampered payload or an untrusted signer does not
 * verify - and extracts the statement-subject digests and the provenance predicate's builder identity and source
 * URIs so the admission policy can bind and match them. The envelope is {@link Dsse}'s, the same one the
 * repository's own signers produce, so an attestation this repository signs round-trips through this verifier. Only
 * an {@code application/vnd.in-toto+json} envelope is understood - anything else is not an in-toto attestation and
 * does not parse.
 *
 * <p>A Sigstore bundle's material - a certificate and a transparency-log entry - names an identity this policy has
 * no anchor for, so it is not what admits the file here: the configured keys are, exactly as for a bare envelope.
 * Before the bundle shape was read, a {@code .sigstore} referrer parsed as no attestation at all and its artifact
 * published ungated.
 */
final class AttestationStatement {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Dsse.Envelope envelope;
    private final JsonNode statement;

    private AttestationStatement(Dsse.Envelope envelope, JsonNode statement) {
        this.envelope = envelope;
        this.statement = statement;
    }

    /** Parse the first in-toto DSSE envelope in the text - a single JSON object, or the first parsable line of a
     *  {@code .jsonl} attestation bundle - or empty when the text is not an in-toto attestation envelope. The
     *  claims-a-subject question the inspector asks ("is this an attestation at all?") needs only the first; the
     *  admission policy verifies every envelope through {@link #parseAll}. */
    static Optional<AttestationStatement> parse(String text) {
        return parseAll(text).stream().findFirst();
    }

    /**
     * Every in-toto DSSE envelope the text carries - the one object of a single-envelope referrer (compact on one
     * line or pretty-printed across several), or <em>every</em> line of a {@code .jsonl} attestation bundle - so
     * admission verifies each envelope rather than only the first: a trusted first line must never launder an
     * untrusted, replayed or wrong-builder envelope appended after it (Principles 5, 9). Empty when the text is not
     * an in-toto attestation envelope at all.
     */
    static List<AttestationStatement> parseAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // A .jsonl bundle is one complete envelope per line: when every non-blank line parses as its own envelope it
        // is a multi-entry bundle and each is returned. A single envelope (compact, or pretty-printed across lines
        // whose individual lines are not complete JSON) is not line-per-envelope, so it falls through to the
        // whole-text parse below and is returned as the one envelope it is - never split.
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() > 1) {
            List<AttestationStatement> bundle = new ArrayList<>(lines.size());
            boolean everyLineAnEnvelope = true;
            for (String line : lines) {
                Optional<AttestationStatement> parsed = parseEnvelope(line);
                if (parsed.isEmpty()) {
                    everyLineAnEnvelope = false;
                    break;
                }
                bundle.add(parsed.get());
            }
            if (everyLineAnEnvelope && !bundle.isEmpty()) {
                return List.copyOf(bundle);
            }
        }
        return parseEnvelope(text).map(List::of).orElseGet(List::of);
    }

    private static Optional<AttestationStatement> parseEnvelope(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
        // A Sigstore bundle carries the envelope under dsseEnvelope, the same object a bare referrer is.
        JsonNode node = root.has("dsseEnvelope") ? root.path("dsseEnvelope") : root;
        Optional<Dsse.Envelope> envelope = Dsse.Envelope.from(node).filter(Dsse.Envelope::inToto);
        if (envelope.isEmpty()) {
            return Optional.empty();
        }
        JsonNode statement;
        try {
            statement = JSON.readTree(envelope.get().payload());
        } catch (RuntimeException _) {
            return Optional.empty();
        }
        return Optional.of(new AttestationStatement(envelope.get(), statement));
    }

    /** Whether at least one signature verifies over the DSSE pre-authentication encoding against at least one of the
     *  configured trust-anchor keys - the check that the attestation was signed by a builder the tenant trusts. */
    boolean verifiedBy(Collection<PublicKey> keys) {
        return envelope.verifiedBy(keys);
    }

    /** The SHA-256 digests the statement's subjects declare, lower-cased - what the artifact's own digest is bound
     *  against, so a valid attestation for one artifact cannot be replayed onto another. */
    Set<String> subjectDigests() {
        Set<String> digests = new LinkedHashSet<>();
        for (JsonNode subject : statement.path("subject")) {
            String sha256 = subject.path("digest").path("sha256").asString(null);
            if (sha256 != null && !sha256.isBlank()) {
                digests.add(sha256.strip().toLowerCase(Locale.ROOT));
            }
        }
        return digests;
    }

    /** The builder identity the provenance predicate names (SLSA v0.2 {@code predicate.builder.id}, SLSA v1
     *  {@code predicate.runDetails.builder.id}), or empty when the predicate carries none. */
    Optional<String> builderId() {
        JsonNode predicate = statement.path("predicate");
        for (JsonNode builder : List.of(predicate.path("builder"), predicate.path("runDetails").path("builder"))) {
            String id = builder.path("id").asString(null);
            if (id != null && !id.isBlank()) {
                return Optional.of(id.strip());
            }
        }
        return Optional.empty();
    }

    /** The source locations the provenance predicate names, across the SLSA v0.2 and v1 shapes - the config-source
     *  and material / resolved-dependency URIs a build ran from - so an expected-source policy can match any of them. */
    List<String> sourceUris() {
        JsonNode predicate = statement.path("predicate");
        SequencedSet<String> uris = new LinkedHashSet<>();
        addUri(uris, predicate.path("invocation").path("configSource").path("uri"));
        for (JsonNode material : predicate.path("materials")) {
            addUri(uris, material.path("uri"));
        }
        JsonNode buildDefinition = predicate.path("buildDefinition");
        for (JsonNode dependency : buildDefinition.path("resolvedDependencies")) {
            addUri(uris, dependency.path("uri"));
        }
        JsonNode external = buildDefinition.path("externalParameters");
        addUri(uris, external.path("source").path("uri"));
        addUri(uris, external.path("sourceToBuild").path("uri"));
        addUri(uris, external.path("repository"));
        return List.copyOf(uris);
    }

    private static void addUri(SequencedSet<String> uris, JsonNode node) {
        String uri = node.asString(null);
        if (uri != null && !uri.isBlank()) {
            uris.add(uri.strip());
        }
    }
}
