package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The inbound-attestation inspector: a format-agnostic inspector that finds the provenance referrer a publisher uploads
 * beside an artifact - an in-toto, DSSE, cosign or SLSA envelope at {@code <artifact>.intoto.jsonl}, {@code .att} and
 * the like - and hands it with the artifact's digest to {@link AttestationPolicy}. It claims both the artifact (looking
 * for a co-located referrer) and the referrer as it publishes (binding it back to its artifact), so admission holds
 * whichever order the client pushes them in. Without a parsable in-toto envelope it claims no subject, so an artifact
 * without an attestation adds no gate work. Both legs are screened alike ({@link #inspect}, {@link #inspectArtifact}).
 *
 * <p>It buffers no blob: it reads only the bounded referrer and hashes the artifact bytes the screen already
 * materialised, leaving the binding unconfirmed when the artifact exceeds that window rather than asserting a mismatch
 * against a partial hash.
 */
public final class AttestationInspector implements QualityInspector {

    /** The ecosystem tag of a content-scan subject - not a package ecosystem, so no advisory feed keys on it; it marks
     *  the subject as content-derived for the admission policy and the licence dimension's skip. */
    static final String ECOSYSTEM = "attestation";

    /** The suffixes an inbound attestation is uploaded under (in-toto, DSSE, cosign conventions). {@code .asc} and
     *  {@code .sig} are detached OpenPGP signatures, not in-toto envelopes. */
    private static final List<String> ATTESTATION_SUFFIXES = List.of(
            ".intoto.jsonl", ".intoto.json", ".att", ".attestation", ".dsse", ".sigstore");

    /** The distributable artifact extensions an attestation binds to - primary archives, not their metadata, checksum
     *  or signature siblings. */
    private static final Set<String> ARTIFACT_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".aar", ".whl", ".gem", ".nupkg", ".snupkg", ".crate", ".conda",
            ".tgz", ".zip", ".nar", ".apk", ".vsix");

    // Whether the artifact was read whole is the shared prefix tier (QualityInspector.PREFIX_INSPECTION_LIMIT): at or
    // above it the binding is left unconfirmed. BoundedBodyReader.completeArtifact holds that arithmetic.

    @Override
    public boolean handles(String path) {
        return attestationSuffix(path) != null || artifactExtension(path) != null;
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) throws IOException {
        return subjects(path, content, lookup);
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup) throws IOException {
        // A co-located attestation is verified the same way on the proxy leg.
        return subjects(path, content, lookup);
    }

    private List<ComplianceGate.Subject> subjects(String path, byte[] content, Lookup lookup) throws IOException {
        String suffix = attestationSuffix(path);
        String envelope;
        String artifactDigest;
        boolean artifactPresent;
        if (suffix != null) {
            // The referrer itself is publishing: verify it against the artifact it names (its sibling without the
            // suffix), so a tampered or wrong-builder attestation is gated at its own upload.
            envelope = new String(content, StandardCharsets.UTF_8);
            String artifactPath = path.substring(0, path.length() - suffix.length());
            // A bounded read of the sibling: a tiny referrer must never pull a huge artifact into heap on the publish
            // thread. Over the window, the binding stays unconfirmed, as for an over-window artifact on the publish
            // leg.
            Optional<QualityInspector.Lookup.Bounded> sibling =
                    lookup.fetchBounded(artifactPath, QualityInspector.prefixInspectionLimit());
            // The sibling is present whenever the store answered the bounded read, truncated or not: present but
            // unhashable is a binding the policy holds on, while an absent sibling defers the binding until the
            // artifact lands.
            artifactPresent = sibling.isPresent();
            artifactDigest = sibling.filter(bounded -> !bounded.truncated())
                    .map(bounded -> completeDigest(bounded.content()))
                    .orElse(null);
        } else {
            // A distributable artifact is publishing: gate it on a co-located referrer when one is present.
            Optional<byte[]> referrer = referrer(path, lookup);
            if (referrer.isEmpty()) {
                return List.of();
            }
            envelope = new String(referrer.get(), StandardCharsets.UTF_8);
            // The artifact is publishing, so present; its digest is null only at or above the inspection window -
            // present but unhashable, which the policy holds on.
            artifactPresent = true;
            artifactDigest = completeDigest(content);
        }
        if (AttestationStatement.parse(envelope).isEmpty()) {
            // Not an in-toto envelope: nothing this dimension admits on, and the file publishes untouched.
            return List.of();
        }
        ComplianceGate.Attestation attestation =
                new ComplianceGate.Attestation(envelope, artifactDigest, artifactPresent, path);
        // The shared content-scan subject: no licensable coordinate, the location standing in for one, the attestation
        // stamped on, as the secret inspector stamps its detections.
        return List.of(ManifestSubjectBuilder.contentScan(ECOSYSTEM, path).withAttestation(attestation));
    }

    private Optional<byte[]> referrer(String path, Lookup lookup) throws IOException {
        for (String suffix : ATTESTATION_SUFFIXES) {
            Optional<byte[]> found = lookup.fetch(path + suffix);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** The hex SHA-256 of the artifact when read whole, or {@code null} when absent or at least as large as the
     *  inspection window, leaving the binding unconfirmed. */
    private static String completeDigest(byte[] artifact) {
        if (!BoundedBodyReader.completeArtifact(artifact)) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(artifact);
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The attestation referrer suffix {@code path} ends with (original case), or {@code null} when it is not one. */
    private static String attestationSuffix(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : ATTESTATION_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return path.substring(path.length() - suffix.length());
            }
        }
        return null;
    }

    /** The distributable-artifact extension {@code path} ends with, or {@code null} when it is not a package archive. */
    private static String artifactExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot);
        return ARTIFACT_EXTENSIONS.contains(extension) ? extension : null;
    }
}
