/**
 * Inbound provenance admission: it verifies an in-toto, DSSE or SLSA attestation a publisher uploaded beside an
 * artifact against the tenant's trust anchors and expected builder and source, and gates admission on the outcome. A
 * discovered {@link build.jenesis.repository.compliance.QualityInspector} stamps the co-located referrer onto the
 * compliance subject, and a discovered {@link build.jenesis.repository.compliance.GatePolicyProvider}
 * ({@code provenance-admission}) verifies the signature, binds the subject digest, checks builder and source, and
 * raises a finding at the configured verdict (default quarantine). Without this module inbound attestations are not
 * verified.
 *
 * <p>Verification uses {@code java.base} crypto and Jackson over the same DSSE encoding the repository's own provenance
 * signer produces, with no signing library and no network call - the trust anchor is a configured key. No artifact blob
 * is buffered. An admitted referrer stays stored and served beside its artifact.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.admission {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.admission;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.admission.AttestationInspector;
    provides build.jenesis.repository.compliance.GatePolicyProvider
            with build.jenesis.repository.compliance.admission.AttestationGatePolicyProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.admission.AttestationSettingsContributor;
}
