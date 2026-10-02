package build.jenesis.repository.compliance.oci;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Maintainer;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The OCI image quality inspector: it claims {@code /v2/...} and screens the manifest - the document a push ends with
 * and a pull starts from - so the {@link ComplianceGate} can assess an image's {@code {name, reference}} coordinate
 * for known vulnerabilities, malicious-package flags and the deny-list, and its declared licence against the policy.
 *
 * <h2>The coordinate is the path, and only the manifest carries one</h2>
 *
 * A Distribution client puts the coordinate in the URL - {@code /v2/<name>/manifests/<reference>} - and nowhere in
 * the bytes, so it is read from the path. A blob is not screened: {@code /v2/<name>/blobs/sha256:<digest>} names
 * content shared by every image that includes it, so a verdict on it would attach one image's verdict to many. The
 * manifest is where an image becomes something an operator can name, hold and release, which is why the hold
 * machinery in {@code format/oci-inventory} keys on it too. A digest reference is screened like a tag and reported as
 * the version, since an advisory naming that image must bite.
 *
 * <h2>The licence is the publisher's own annotation</h2>
 *
 * The OCI image spec's {@code org.opencontainers.image.licenses} annotation, an SPDX expression. An image that declares
 * none reports none and the deployment's unknown-licence dial decides; nothing guesses a licence from a layer's files.
 *
 * <p>The manifest is read under the shared prefix tier; a body that is not readable JSON is screened as the coordinate
 * alone rather than refused, because a registry accepts manifest media types this does not parse - an image index, a
 * Helm chart, an arbitrary artifact type.
 */
public final class OciQualityInspector implements QualityInspector {

    /** The ecosystem OCI coordinates report. OSV has no container feed, so a vulnerability lookup finds only what an
     *  operator recorded, while the deny-list and the malicious-package flag bite as for any ecosystem. */
    private static final String ECOSYSTEM = "OCI";

    private static final String PREFIX = "/v2/";

    private static final String MANIFESTS = "/manifests/";

    /** The OCI image spec's licence annotation - an SPDX expression, declared by the publisher. */
    private static final String LICENSES = "org.opencontainers.image.licenses";

    /** The OCI image spec's description annotation - what the publisher says the image is for. */
    private static final String DESCRIPTION = "org.opencontainers.image.description";

    /** The OCI image spec's authors annotation - whom the publisher credits. */
    private static final String AUTHORS = "org.opencontainers.image.authors";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * cosign's signature artifact, which is a signature rather than an image, so it is not claimed. It is a well-formed
     * manifest with no licence of its own: claimed, it would be held under {@code license-unknown=QUARANTINE}, and a
     * withheld sidecar is never handed to signature verification, so the image it covers would publish unjudged.
     */
    private static boolean isSignatureTag(String path) {
        int manifests = path.indexOf("/manifests/");
        return manifests >= 0 && path.endsWith(".sig")
                && path.startsWith("sha256-", manifests + "/manifests/".length());
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX) && !isSignatureTag(path);
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) {
        return subjects(path, content);
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup) {
        return subjects(path, content);
    }

    /**
     * The compliance subject for a manifest path, or an empty list for anything else under {@code /v2/} - a blob, an
     * upload session, the version probe, a tag listing - which names no single image version.
     */
    private static List<ComplianceGate.Subject> subjects(String path, byte[] content) {
        String rest = path.substring(PREFIX.length());
        int manifests = rest.indexOf(MANIFESTS);
        if (manifests < 0) {
            return List.of();
        }
        String name = rest.substring(0, manifests);
        String reference = rest.substring(manifests + MANIFESTS.length());
        if (name.isEmpty() || reference.isEmpty() || reference.indexOf('/') >= 0) {
            return List.of();
        }
        // A repository name is a path of segments (library/nginx, an org's nested namespace), so it is guarded
        // segment by segment; an unsafe one is not a value the registry would have stored or served.
        for (String segment : name.split("/", -1)) {
            if (ManifestSubjectBuilder.unsafeSegment(segment)) {
                return List.of();
            }
        }
        if (ManifestSubjectBuilder.unsafeSegment(reference)) {
            return List.of();
        }
        return ManifestSubjectBuilder.of(ECOSYSTEM)
                .licenses(licences(content))
                .about(annotation(content, DESCRIPTION), List.of(),
                        Maintainer.person(annotation(content, AUTHORS)).map(Maintainer::name).stream().toList())
                .subject(name, reference);
    }

    /** One annotation of the manifest as its publisher wrote it, or {@code null} where it carries none or does not
     *  parse - an annotation is optional beside the coordinate the path yields. */
    private static String annotation(byte[] manifest, String key) {
        if (manifest == null || manifest.length == 0) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(manifest).path("annotations").path(key);
            return value.isString() && !value.stringValue().isBlank() ? value.stringValue().trim() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The SPDX expression the manifest's {@code org.opencontainers.image.licenses} annotation declares, or empty -
     * also for a manifest this cannot parse, whose coordinate still screens.
     */
    private static List<String> licences(byte[] manifest) {
        if (manifest == null || manifest.length == 0) {
            return List.of();
        }
        try {
            JsonNode annotations = JSON.readTree(manifest).path("annotations");
            JsonNode declared = annotations.path(LICENSES);
            if (!declared.isString()) {
                return List.of();
            }
            String value = declared.stringValue().trim();
            return value.isEmpty() ? List.of() : List.of(value);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }
}
