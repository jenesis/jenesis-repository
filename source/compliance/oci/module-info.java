/**
 * The OCI image quality gate: it provides {@link build.jenesis.repository.compliance.QualityInspector} for the
 * {@code /v2/} Distribution layout, reading an image's {@code {name, reference}} coordinate from the request path - the
 * one place a Distribution client puts it - so the compliance gate can assess a manifest push for known
 * vulnerabilities, malicious-package flags, the operator deny-list and licence policy. The licence is the manifest's
 * own {@code org.opencontainers.image.licenses} annotation, an SPDX expression the publisher declared.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.oci {
    requires build.jenesis.repository.compliance;
    // The format's layout names the coordinate the gate hands this inspector, so it is on every graph the
    // inspector is on: without it a path would be screened under no coordinate at all.
    requires build.jenesis.repository.format.oci.inventory;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.oci;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.oci.OciQualityInspector;
}
