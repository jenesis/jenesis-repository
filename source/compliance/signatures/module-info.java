/**
 * The inbound signature dimension's reader, as a plugin module: it provides the one
 * {@link build.jenesis.repository.compliance.QualityInspector} that verifies a publisher's signature for <em>every</em>
 * installed format that declares one through {@code ArtifactSignatures}, and stamps what it found onto the compliance
 * subject. Discovered through {@code provides}, exactly like a format inspector and the license / secret dimensions -
 * the format-agnostic gate never names a format, and a deployment without this module simply does not check inbound
 * signatures.
 *
 * <p>There is deliberately one inspector rather than one per format. A format already states where its signature
 * material sits and what it covers; parsing the packet, checking it against the deployment's key material, grading it
 * and naming the signer are identical afterwards, whether the material was a Maven {@code .asc} sidecar or the
 * {@code _gpgorigin} member inside a {@code .deb}. Writing it per format would make the capability a property of
 * whichever format was implemented first, with every later one arriving as a copy that drifts.
 *
 * <p>No verifier lives here. Each scheme's check is a {@code SignatureScheme} the inspector discovers through the
 * compliance SPI - the OpenPGP, PKCS#7 and bare-signature ones from {@code build.jenesis.repository.format.signing},
 * the one module in this build permitted to load Bouncy Castle, and the Sigstore one from the keyless module - so
 * this module imports no cryptographic library and a scheme no installed module verifies is reported as such.
 * This module makes no network call and holds no key: the trust material is overlaid onto it by the screen through
 * {@code TrustAware}, and without one it reports every signature untrusted rather than trusted, which is the
 * fail-closed direction.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.compliance.signatures {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.gate;
    requires build.jenesis.repository.settings;
    // The Sigstore scheme rides the keyless module. Nothing here names it - it is discovered - but a composition that
    // verifies signatures verifies bundles too, so the module is required for what it provides rather than exports.
    // The key-discovery pass rides the maintenance scheduler and reaches the keyserver an operator named over HTTP.
    requires build.jenesis.repository.maintenance;
    // The sweep walks the inventory's releases, whose visitor speaks the cleanup module's release type.
    requires build.jenesis.repository.cleanup;
    requires java.net.http;
    exports build.jenesis.repository.compliance.signatures to
            build.jenesis.repository.compliance.test, build.jenesis.repository.compliance.contract.test,
            build.jenesis.repository.gateway.test,
            // The signer index is read by the API and the console through one implementation, and seeded by their tests.
            build.jenesis.repository.compliance.web, build.jenesis.repository.ui.store,
            build.jenesis.repository.server.kernel.test, build.jenesis.repository.ui.admin.installed.test,
            // and by the browser suites' seed, which records a signer so the screens have one to render: what a
            // keyless identity looks like on a page is not decidable from the model behind it.
            build.jenesis.repository.console.seed.testkit;
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
