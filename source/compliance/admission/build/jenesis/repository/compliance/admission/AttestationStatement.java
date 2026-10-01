package build.jenesis.repository.compliance.admission;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.Dsse;

/**
 * A parsed inbound attestation: a DSSE envelope wrapping an in-toto Statement, as cosign, in-toto and the SLSA
 * generators emit it - alone, one per line of a {@code .jsonl} bundle, or under {@code dsseEnvelope} in a Sigstore
 * bundle. It verifies the signature as a consumer does - over the DSSE pre-authentication encoding, against the
 * tenant's trust anchors - and extracts the subject digests and the provenance builder and source URIs. The envelope is
 * {@link Dsse}'s, the one the repository's own signers produce, so an attestation this repository signs round-trips.
 * Only an {@code application/vnd.in-toto+json} envelope parses.
 *
 * <p>A Sigstore bundle's certificate and transparency-log entry name an identity this policy has no anchor for, so the
 * configured keys admit it, as for a bare envelope; the bundle shape is read so a {@code .sigstore} referrer is gated.
 */
final class AttestationStatement {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Dsse.Envelope envelope;
    private final JsonNode statement;

    private AttestationStatement(Dsse.Envelope envelope, JsonNode statement) {
        this.envelope = envelope;
        this.statement = statement;
    }

    /** The first in-toto DSSE envelope in the text - a single object, or the first parsable line of a {@code .jsonl}
     *  bundle - or empty when it is not one. The inspector needs only the first; the policy verifies all through
     *  {@link #parseAll}. */
    static Optional<AttestationStatement> parse(String text) {
        return parseAll(text).stream().findFirst();
    }

    /** Every in-toto DSSE envelope the text carries - the one object of a single referrer, compact or pretty-printed,
     *  or every line of a {@code .jsonl} bundle - so admission verifies each and a trusted first line cannot launder
     *  what follows. Empty when the text is not an in-toto envelope at all. */
    static List<AttestationStatement> parseAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // A .jsonl bundle is one complete envelope per line; when every non-blank line parses alone, each is returned.
        // A pretty-printed single envelope's lines do not, so it falls through to the whole-text parse, never split.
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

    /** Whether a signature verifies over the DSSE pre-authentication encoding against a configured trust anchor - that
     *  a builder the tenant trusts signed it. */
    boolean verifiedBy(Collection<PublicKey> keys) {
        return envelope.verifiedBy(keys);
    }

    /** The SHA-256 digests the statement's subjects declare, lower-cased - what the artifact's digest is bound
     *  against. */
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

    /** The source locations the provenance predicate names across the SLSA v0.2 and v1 shapes - config source,
     *  materials, resolved dependencies - so an expected-source policy can match any of them. */
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
