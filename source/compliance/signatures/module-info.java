/**
 * The inbound signature dimension: the one {@link build.jenesis.repository.compliance.QualityInspector} that verifies a
 * publisher's signature for every format declaring one through {@code ArtifactSignatures}, the gate policy, the trust
 * parts, and the passes that discover keys, fetch the Sigstore root and sweep retroactively. Each scheme's verifier is a
 * discovered {@code SignatureScheme}, so no cryptographic library is imported here. Without this module inbound
 * signatures are not checked.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.signatures {
    requires build.jenesis.repository.net;
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.settings;
    // The passes ride the maintenance scheduler.
    requires build.jenesis.repository.maintenance;
    // The sweep walks the inventory's releases, whose visitor speaks the cleanup module's release type.
    requires build.jenesis.repository.cleanup;
    requires java.net.http;
    // The signer index, read by the API and the console and seeded by their tests.
    exports build.jenesis.repository.compliance.signatures;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.signatures.SignatureInspector;
    provides build.jenesis.repository.gate.HoldReleaseObserver
            with build.jenesis.repository.compliance.signatures.SignatureHoldReleaseObserver;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.compliance.signatures.SignatureCompletionObserver,
                    build.jenesis.repository.compliance.signatures.AttestationLookupObserver;
    provides build.jenesis.repository.compliance.GatePolicyProvider
            with build.jenesis.repository.compliance.signatures.SignatureGatePolicyProvider;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.compliance.signatures.KeyDiscoveryTaskProvider,
                    build.jenesis.repository.compliance.signatures.SignatureSweepTaskProvider,
                    build.jenesis.repository.compliance.signatures.TrustedRootTaskProvider;
    provides build.jenesis.repository.compliance.SignerTrustProvider
            with build.jenesis.repository.compliance.signatures.ConfiguredSignerTrustProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.compliance.signatures.SignatureSettingsContributor;
}
