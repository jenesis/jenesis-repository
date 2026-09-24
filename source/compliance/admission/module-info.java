/**
 * Inbound provenance / attestation admission as a plugin module: it verifies an in-toto / DSSE / SLSA attestation a
 * publisher uploaded beside an artifact (its {@code .intoto.jsonl} / {@code .att} referrer) against the tenant's
 * configured trust anchor and expected-builder / expected-source policy, and gates admission on the outcome. A
 * discovered {@link build.jenesis.repository.compliance.QualityInspector} reads the co-located referrer for an
 * artifact (or the referrer itself as it publishes) and stamps it onto the compliance subject; a discovered
 * {@link build.jenesis.repository.compliance.GatePolicyProvider} ({@code provenance-admission}) verifies the DSSE
 * signature against the trust anchor, binds the signed subject digest to the artifact, checks the builder and source,
 * and raises a gate finding at the configured verdict (default quarantine) when any of those fail. Discovered through
 * {@code provides}, exactly like a format inspector and the license / secret dimensions - the format-agnostic gate
 * never names provenance admission, and a deployment without this module simply does not verify inbound attestations.
 *
 * <p>The verify path is over {@code java.base} crypto and Jackson (the same DSSE pre-authentication encoding the
 * repository's own provenance signer produces, so an attestation this repository signs round-trips through it), so
 * the module carries no signing library and makes no network call - the trust anchor is a configured key, not a
 * fetched one. It buffers no artifact blob: the inspector reads only the bounded attestation referrer and hashes the
 * artifact bytes the screen already materialised for its inspectors (leaving the binding unconfirmed when the
 * artifact is larger than that window). The verified attestation is the referrer the publisher uploaded, which stays
 * stored and served beside the artifact once admission clears; a failing one is quarantined or rejected.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.admission {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance.admission to
            build.jenesis.repository.compliance.test, build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.admission.AttestationInspector;
    provides build.jenesis.repository.compliance.GatePolicyProvider
            with build.jenesis.repository.compliance.admission.AttestationGatePolicyProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.admission.AttestationSettingsContributor;
}
