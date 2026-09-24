/**
 * The OCI / Docker image publishing and proxy quality gate as a plugin module: it provides
 * {@link build.jenesis.repository.compliance.QualityInspector} for the {@code /v2/} Distribution layout, reading an
 * image's {@code {name, reference}} coordinate from the request path - the one place a Distribution client puts it -
 * so the shared compliance gate can assess a manifest push for known vulnerabilities, malicious-package flags, the
 * operator deny-list and licence policy.
 *
 * <p><b>Why this module exists.</b> Before it, an image was the one artifact class this product could not gate at
 * publish time: no plugin claimed a {@code /v2/} path, so a manifest push derived no coordinate and was screened as
 * unclaimed content - the deny-list from a path, and nothing else. Retroactive KEV sweeps did hold images (that is
 * what {@code format/oci-inventory} is for), which made the gap easy to miss: an image already published could be
 * withheld, while the image being pushed was waved through. Images are the artifact a compliance story is most often
 * bought for.
 *
 * <p><b>The licence comes from the manifest's own annotations</b> ({@code org.opencontainers.image.licenses}, an SPDX
 * expression the OCI image spec defines), read from the manifest JSON the push carries. That is a declaration the
 * publisher made in the document being screened - not a guess, and not a file this has to go looking for.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.oci {
    requires build.jenesis.repository.compliance;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.oci to build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.oci.OciQualityInspector;
}
