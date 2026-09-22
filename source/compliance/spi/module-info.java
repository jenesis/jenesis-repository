/**
 * The compliance contracts for the repository: the gate that screens an artifact at ingestion against a
 * license policy, a vulnerability policy (a CVSS threshold), the feed's malicious-package flag, the operator
 * deny-list and a known-exploited catalogue, returning allow / quarantine / reject, so a deployment can
 * refuse or hold non-compliant uploads rather than simply storing them. The gate is a value-in,
 * decision-out unit tested in isolation, then wired into the repository's publish path separately. Every network
 * check is a {@code ServiceLoader}-discovered plugin module behind one of the provider SPIs here - the ONE
 * {@code SignalSourceProvider} for the security-signal family (advisory feeds, known-exploited catalogues,
 * exploit-probability models, maintainer-health sources, report columns; the created source opts into the
 * specialised contracts as {@code SignalSource} sub-interfaces), plus the gate-dimension, provenance-signer,
 * quality-inspector, signature-scheme and VEX-source SPIs - so this module stays framework-free ({@code java.base}
 * plus the settings contract, whose {@code Features} convention gates discovery, the {@code store} contract the
 * {@code VexProvider} carries so a tenant's ingested statements are read over the same {@code ArtifactStore} SPI,
 * and which the {@code SignalContext} reuses for the deployment-global space a mirrored catalogue's snapshots live
 * in, and the {@code format} contract, whose {@code ArtifactSignatures} vocabulary is what a
 * {@code SignatureScheme} reads evidence in) and the composition names no backend. The HTTP client a feed fetches
 * through is deliberately <em>not</em> here: it rides the
 * {@code build.jenesis.repository.feed} support module, which the implementation modules require and this
 * contract module never does.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    // A SignatureScheme reads ArtifactSignatures.Evidence and answers over its Signed: the free format contract is
    // part of this SPI's surface, so an implementor sees it through this module. Transitive for that reason alone.
    requires transitive build.jenesis.repository.format;
    // SignalSourceProvider extends IconContributor: its name() is what a finding records as its source, so the
    // provider is the seam a console resolves that recorded name back to a plug-in and its mark through.
    // Transitive, because an implementor sees IconContributor in the interface it implements.
    requires transitive build.jenesis.repository.icon;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.compliance;
    uses build.jenesis.repository.compliance.GatePolicyProvider;
    uses build.jenesis.repository.compliance.ProvenanceSignerProvider;
    uses build.jenesis.repository.compliance.QualityInspector;
    uses build.jenesis.repository.compliance.SignatureScheme;
    uses build.jenesis.repository.compliance.SignerTrustProvider;
    uses build.jenesis.repository.compliance.SignalSourceProvider;
    uses build.jenesis.repository.compliance.VexProvider;
}
