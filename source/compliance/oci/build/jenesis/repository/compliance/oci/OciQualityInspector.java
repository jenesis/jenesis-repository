package build.jenesis.repository.compliance.oci;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;

/**
 * The OCI / Docker image quality inspector: the publishing and proxy quality gate for the {@code /v2/} Distribution
 * layout, as a plugin of its own. It claims {@code /v2/...} artifacts and screens the MANIFEST - the one document a
 * push ends with, and the one a pull starts from - so the shared {@link ComplianceGate} can assess an image's
 * {@code {name, reference}} coordinate for known vulnerabilities, malicious-package flags and the operator deny-list,
 * and its declared licence against the licence policy.
 *
 * <h2>The coordinate is the path, and only the manifest carries one</h2>
 *
 * A Distribution client puts the coordinate in the URL - {@code /v2/<name>/manifests/<reference>} - and nowhere in
 * the bytes, so it is read from the path exactly as the Go, Conan and Hugging Face inspectors read theirs. A BLOB is
 * deliberately not screened: {@code /v2/<name>/blobs/sha256:<digest>} names content, not a version, and the same
 * layer is shared by every image that includes it - screening it would attach one image's verdict to bytes belonging
 * to many. The manifest is where an image becomes a thing an operator can name, hold and release, which is why the
 * hold machinery ({@code format/oci-inventory}) keys on it too.
 *
 * <p>A reference that is a DIGEST rather than a tag is still a coordinate and still screened: a client that pulls by
 * digest is pulling a specific image, and an advisory that names it must bite. It is reported as the version, which
 * is what the path says.
 *
 * <h2>The licence is the publisher's own annotation</h2>
 *
 * The OCI image spec defines {@code org.opencontainers.image.licenses} as an SPDX expression in the manifest's
 * {@code annotations}, and that is what this reads - a declaration the publisher made in the document being screened.
 * An image that declares none reports none, and the deployment's unknown-licence dial decides, which is the same
 * fail-closed shape every other inspector uses. Nothing inspects a layer for licence text: guessing a licence from
 * file contents is how a policy comes to be enforced against something nobody declared.
 *
 * <p>The manifest is small by the spec's own design (a config descriptor and a layer list), and it is read under the
 * shared prefix tier like every other manifest-shaped document; a body that is not readable JSON is screened as the
 * coordinate alone rather than refused, because a Distribution registry accepts manifest media types this does not
 * parse - an image index, a Helm chart, an arbitrary artifact type - and refusing them would break a conformant push
 * that this gate has no opinion about.
 */
public final class OciQualityInspector implements QualityInspector {

    /** The package-ecosystem name OCI coordinates report. OSV has no dedicated container feed, so a vulnerability
     *  lookup finds nothing unless an operator recorded one - while the deny-list and the malicious-package flag,
     *  which key on the coordinate, bite exactly as they do for any other ecosystem. */
    private static final String ECOSYSTEM = "OCI";

    private static final String PREFIX = "/v2/";

    private static final String MANIFESTS = "/manifests/";

    /** The OCI image spec's licence annotation - an SPDX expression, declared by the publisher. */
    private static final String LICENSES = "org.opencontainers.image.licenses";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * cosign's signature artifact, which is a signature rather than an image.
     *
     * <p>It is a well-formed manifest, so this inspector reads it without complaint - and that is the danger. It
     * has no licence of its own, so a deployment that holds an unlicensed artifact quarantines every cosign
     * signature pushed to it, and a withheld sidecar is never handed to an inspector: signature verification for
     * this layout switches itself off on exactly the deployments strict enough to care. Measured 2026-09-15 with
     * {@code license-unknown} at QUARANTINE, where the signature artifact was held and the image it covered
     * published unjudged. The shipped default is ALLOW, which is why nothing had noticed.
     *
     * <p>A signature is not an artifact, and an inspector that claims one is the thing that can hide it - the same
     * rule the Helm layout learned the hard way on the same day.
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
     * The compliance subject for a manifest path, or an empty list for anything else the {@code /v2/} tree carries -
     * a blob, an upload session, the {@code /v2/} version probe, a tag listing. Those name no single image version to
     * screen, and the blob deliberately so (see the class comment).
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
                .subject(name, reference);
    }

    /**
     * The SPDX expression the manifest's {@code org.opencontainers.image.licenses} annotation declares, or empty.
     *
     * <p>Never throws for the licence's sake: a manifest this cannot parse is one whose media type this gate has no
     * opinion about, and the coordinate still screens. An undeclared licence is what the deployment's dial decides
     * about - fail-closed, without this inspector inventing a verdict.
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
