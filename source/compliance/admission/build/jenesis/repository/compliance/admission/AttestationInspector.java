package build.jenesis.repository.compliance.admission;

import module java.base;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The inbound-attestation inspector: a format-agnostic content inspector that finds the provenance referrer a
 * publisher uploads beside an artifact - the in-toto / DSSE / cosign / SLSA envelope at {@code <artifact>.intoto.jsonl}
 * / {@code .att} / ... - and hands it, together with the artifact's own digest, to the discovered
 * {@link AttestationPolicy} to verify and gate on. It claims two shapes so admission holds whichever order the client
 * pushes the pair in: the <em>artifact</em> itself (it looks for a co-located attestation referrer), and the
 * <em>attestation referrer</em> as it publishes (it binds it back to the artifact it names). When it finds a parsable
 * in-toto envelope it returns a single content-scan subject carrying the raw envelope and the artifact digest; when
 * there is no attestation, or the referrer is not an in-toto envelope, it claims no subject at all - so an ordinary
 * artifact without an attestation adds no gate work. It screens both legs identically ({@link #inspect} on publish,
 * {@link #inspectArtifact} on proxy fetch), because a mis-attested upstream pull-through is the same risk as a
 * first-party one.
 *
 * <p>It never buffers a blob: it reads only the bounded attestation referrer and hashes the artifact bytes the screen
 * already materialised for its inspectors - leaving the statement-subject binding unconfirmed (rather than asserting a
 * mismatch against a partial hash) when the artifact was larger than that inspection window.
 */
public final class AttestationInspector implements QualityInspector {

    /** The ecosystem tag a content-scan subject carries - not a package ecosystem, so no advisory feed keys on it; it
     *  marks the subject as content-derived for the admission policy and the license dimension's skip. */
    static final String ECOSYSTEM = "attestation";

    /** The referrer suffixes an inbound attestation is uploaded under, beside its artifact - the in-toto / DSSE /
     *  cosign conventions. A GPG {@code .asc} / {@code .sig} is deliberately absent: those are detached OpenPGP
     *  signatures, not in-toto attestation envelopes. */
    private static final List<String> ATTESTATION_SUFFIXES = List.of(
            ".intoto.jsonl", ".intoto.json", ".att", ".attestation", ".dsse", ".sigstore");

    /** The distributable artifact extensions worth binding an attestation to - the primary package archives, not the
     *  metadata / checksum / signature siblings that travel with them. */
    private static final Set<String> ARTIFACT_EXTENSIONS = Set.of(
            ".jar", ".war", ".ear", ".aar", ".whl", ".gem", ".nupkg", ".snupkg", ".crate", ".conda",
            ".tgz", ".zip", ".nar", ".apk", ".vsix");

    // Whether the artifact was read whole is the SHARED prefix tier (QualityInspector.PREFIX_INSPECTION_LIMIT), not a
    // limit of this inspector's own: at or above it the artifact was not read whole - it exceeds the gate's publish-leg
    // inspection window - so its statement-subject binding is left unconfirmed rather than asserted against a partial
    // hash. BoundedBodyReader.completeArtifact is the one place that arithmetic lives.

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
        // Screened identically on the proxy leg: a co-located attestation is verified the same way whichever leg the
        // artifact arrives on.
        return subjects(path, content, lookup);
    }

    private List<ComplianceGate.Subject> subjects(String path, byte[] content, Lookup lookup) throws IOException {
        String suffix = attestationSuffix(path);
        String envelope;
        String artifactDigest;
        boolean artifactPresent;
        if (suffix != null) {
            // The attestation referrer itself is publishing: verify it against the artifact it names (its sibling
            // without the suffix), so a tampered or wrong-builder attestation is gated at its own upload.
            envelope = new String(content, StandardCharsets.UTF_8);
            String artifactPath = path.substring(0, path.length() - suffix.length());
            // Bind the artifact digest off a BOUNDED read of the sibling: a tiny referrer must never pull a
            // multi-gigabyte artifact whole into heap on the publish thread (a >=32 MiB discard AFTER materialisation
            // is still an OOM). When the sibling exceeds the inspection window we leave the statement-subject binding
            // unconfirmed - exactly as an over-window artifact already is on the publish leg - rather than buffering it.
            Optional<QualityInspector.Lookup.Bounded> sibling =
                    lookup.fetchBounded(artifactPath, QualityInspector.prefixInspectionLimit());
            // The sibling artifact IS present when the store answered the bounded read at all - even when it came back
            // truncated (over the window). Present-but-unhashable is a can't-confirm binding the policy must hold on;
            // an absent sibling is a sidecar published before its artifact lands, whose binding is deferred.
            artifactPresent = sibling.isPresent();
            artifactDigest = sibling.filter(bounded -> !bounded.truncated())
                    .map(bounded -> completeDigest(bounded.content()))
                    .orElse(null);
        } else {
            // A distributable artifact is publishing: gate it on a co-located attestation referrer when one is present.
            Optional<byte[]> referrer = referrer(path, lookup);
            if (referrer.isEmpty()) {
                return List.of();
            }
            envelope = new String(referrer.get(), StandardCharsets.UTF_8);
            // The artifact is the very thing publishing on this leg, so it is present; its digest is null only when it
            // is at or above the inspection window (>=32 MiB) - present but unhashable, a binding the policy holds on.
            artifactPresent = true;
            artifactDigest = completeDigest(content);
        }
        if (AttestationStatement.parse(envelope).isEmpty()) {
            // Not an in-toto attestation envelope - nothing this dimension admits on; the file publishes untouched.
            return List.of();
        }
        ComplianceGate.Attestation attestation =
                new ComplianceGate.Attestation(envelope, artifactDigest, artifactPresent, path);
        // The shared content-scan subject shape: no licensable coordinate, the artifact's location standing in for one,
        // with the finding stamped on - exactly as the secret inspector stamps its detections.
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

    /** The hex SHA-256 of the artifact bytes when they were read whole, or {@code null} when they were absent or at
     *  least as large as the inspection window - in which case the statement-subject binding is left unconfirmed
     *  rather than asserted against a partial hash. */
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
