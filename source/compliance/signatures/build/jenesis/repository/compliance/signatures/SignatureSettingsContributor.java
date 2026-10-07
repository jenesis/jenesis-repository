package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The signature dimension's dials. The trust dials are {@link Setting.Scope#TENANT}, since which keys a tenant believes
 * is a statement about its own supply chain. The keyless dials are listed only where a scheme verifies Sigstore
 * bundles, since without one they configure nothing; that answer is held, installation being fixed for the JVM.
 */
public final class SignatureSettingsContributor implements SettingsContributor {

    private static final boolean KEYLESS =
            SignatureScheme.installed(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE).isPresent();

    private static final Set<String> KEYLESS_KEYS = Set.of(ConfiguredSignerTrust.SIGSTORE_ROOT, TrustedRootTask.URL,
            TrustedRootTask.INTERVAL.key(), ProvenanceTrust.ACCEPT);

    @Override
    public List<Setting> settings() {
        List<Setting> settings = all();
        return KEYLESS ? settings : settings.stream().filter(setting -> !KEYLESS_KEYS.contains(setting.key())).toList();
    }

    private static List<Setting> all() {
        return List.of(
                new Setting(ConfiguredSignerTrust.KEYS, "Compliance", "Trusted signing keys",
                        "The armoured OpenPGP public keys this deployment verifies publisher signatures against. Empty "
                                + "trusts nobody: a signature is still read and graded, but none is reported trusted, "
                                + "because a deployment that has named no keys has given no grounds to believe anyone.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.TEXT).standard(),
                new Setting(ConfiguredSignerTrust.CERTIFICATES, "Compliance", "Trusted signing certificates",
                        "The PEM certificates a PKCS#7 (CMS) publisher signature must chain to: a NuGet author or "
                                + "repository signing root, a Swift registry's. The chain is built from the "
                                + "certificates the signature carries, judged at its signing time and never revoked "
                                + "online, since an OCSP fetch would make a publish depend on a third party answering. "
                                + "Empty trusts nobody, as the keyring above does for OpenPGP.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.TEXT).advanced(),
                new Setting(ConfiguredSignerTrust.PUBLIC_KEYS, "Compliance", "Trusted signing public keys",
                        "The PEM public keys a bare RSA publisher signature is verified against - an Alpine package's "
                                + "signature member, whose key the client keeps in /etc/apk/keys/. Each key may be "
                                + "preceded by a line starting with # that names the key file a package refers to it "
                                + "by (a signature naming a listed key is judged by that key alone; one naming none is "
                                + "tried against the unnamed keys). Empty trusts nobody.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.TEXT).advanced(),
                new Setting(ConfiguredSignerTrust.PINS, "Compliance", "Pinned signers",
                        "Per-namespace pinned signers, each pinning a signer - an OpenPGP key id, or a Sigstore issuer "
                                + "and subject - to a namespace, a trailing * matching a whole namespace. A namespace "
                                + "carrying a pin admits only the signers pinned to it; one without falls back to "
                                + "trusting any key above. Scoping is the point: a key admitted for one namespace "
                                + "should not thereby vouch for another, which is the shape a compromised-but-real key "
                                + "exploits.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.LINES).standard(),
                new Setting(ConfiguredSignerTrust.SIGSTORE_ROOT, "Compliance", "Sigstore trusted root",
                        "The Sigstore trusted root bundles are verified against: the document naming the Fulcio "
                                + "certificate authorities and Rekor transparency logs to believe. Set it for a "
                                + "self-hosted Fulcio or to pin the public one by hand; empty, a root is fetched only "
                                + "if the trusted root URL below names one, and with neither no bundle verifies. A "
                                + "root trusts nobody by itself: a verified bundle is trusted only where a pinned "
                                + "signer names its identity.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.JSON).advanced(),
                new Setting(TrustedRootTask.URL, "Compliance", "Sigstore trusted root URL",
                        "Where the Sigstore trusted root is fetched from when none is pasted above. Empty, the "
                                + "signature checks fetch nothing, so they make no outbound call; for the public-good "
                                + "instance set it to " + TrustedRootTask.DEFAULT_URL + " - fetched by the "
                                + "trusted-root pass on its own cadence and stored, never on a request path. A tool "
                                + "this deployment manages fetches it as it verifies the release it fetches, or the "
                                + "public-good root where this is empty. Taking the document "
                                + "over HTTPS trusts the host serving it; the same root is served through Sigstore's "
                                + "TUF repository with its own signatures, which is the stronger statement and what "
                                + "an internal mirror should be a mirror of. A root arriving after versions were "
                                + "screened does not re-judge them: their recorded summaries say what was concluded "
                                + "when nothing could be verified, and only a re-derivation - a late sidecar, a "
                                + "re-publish - revisits that.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).advanced(),
                new Setting(TrustedRootTask.INTERVAL.key(), "Compliance", "Sigstore trusted root interval",
                        "How often the trusted root is fetched again. A root changes about as often as a certificate "
                                + "authority rotates, and an unchanged document is not rewritten, so a daily pass "
                                + "costs one read.",
                        Setting.Kind.STRING, TrustedRootTask.INTERVAL.fallbackText(), true).advanced(),
                new Setting(ProvenanceTrust.ACCEPT, "Compliance", "Accept signatures by provenance",
                        "The OIDC issuers whose keyless identities are trusted by provenance - GitHub Actions' "
                                + "https://token.actions.githubusercontent.com first among them. A Sigstore bundle by "
                                + "a listed issuer's identity is trusted for a coordinate when the signing workflow "
                                + "belongs to the repository the coordinate's own metadata names; any other "
                                + "repository's workflow, a fork included, stays untrusted. Empty admits nothing this "
                                + "way; a pinned signer decides first.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.LINES).standard(),
                new Setting(KeyDiscoveryTask.SOURCES, "Compliance", "Signing-key discovery",
                        "Where to fetch the signing keys this deployment does not hold, in the order named; empty "
                                + "fetches nothing, so no outbound call is made until this names a source. "
                                + "\"keyserver.ubuntu.com,keys.openpgp.org\" asks both public keyservers by key id; "
                                + "wkd and github ask the maintainers the artifact's own metadata names. A discovered "
                                + "key verifies a signature but stays untrusted until it is added to the trusted keys "
                                + "or the sources are accepted below.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).standard(),
                new Setting(KeyDiscoveryTask.ACCEPT, "Compliance", "Accept discovered keys",
                        "Trust the keys the discovery sources served: a key looked up by its own id as if the "
                                + "operator had pasted it into the trusted signing keys, a key found through a "
                                + "maintainer for the artifacts that name that maintainer. Off, discovery only lets a "
                                + "signature be verified and named, and admission stays a human's decision per key.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.TENANT).advanced(),
                new Setting(KeyDiscoveryTask.URL, "Compliance", "Key discovery server",
                        "Where keys.openpgp.org is reached - the public instance, or an internal mirror of it that "
                                + "speaks the same lookup by key id.",
                        Setting.Kind.STRING, KeyDiscoveryTask.DEFAULT_URL, true, Setting.Scope.TENANT).advanced(),
                new Setting(KeyDiscoveryTask.UBUNTU_URL, "Compliance", "HKP key discovery server",
                        "Where keyserver.ubuntu.com is reached - the public instance, or any host speaking the HKP "
                                + "lookup (op=get&options=mr&search=0x<key id>), which every SKS-descended keyserver "
                                + "and most internal mirrors do.",
                        Setting.Kind.STRING, KeyDiscoveryTask.DEFAULT_UBUNTU, true, Setting.Scope.TENANT).advanced(),
                new Setting(KeyDiscoveryTask.INTERVAL.key(), "Compliance", "Key discovery interval",
                        "How often the key-discovery pass asks the named sources for the keys still wanted; a key a "
                                + "source did not have is asked for again after a day.",
                        Setting.Kind.STRING, KeyDiscoveryTask.INTERVAL.fallbackText(), true).advanced(),
                new Setting(SignatureSweepTask.ENABLED, "Compliance", "Retroactive signature sweep",
                        "Apply the signature dials below to what is already published: the sweep re-judges each "
                                + "version's recorded signature outcome under the current dials and holds one they no "
                                + "longer admit, in the review queue. A tightened dial can hold much of a repository "
                                + "at once, so read the queue after switching it on. It judges the record, not the "
                                + "bytes, and nothing is released automatically when a dial is loosened.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.TENANT).advanced(),
                new Setting(SignatureSweepTask.INTERVAL.key(), "Compliance", "Retroactive signature sweep interval",
                        "How often the signature sweep runs while switched on; every version is judged on its first "
                                + "and every Nth pass, the versions published since between.",
                        Setting.Kind.STRING, SignatureSweepTask.INTERVAL.fallbackText(), true).advanced(),
                new Setting(AttestationLookupObserver.STORES, "Compliance", "Attestation lookup by digest",
                        "The attestation stores asked, by the artifact's digest, for the bundles they hold for an "
                                + "artifact just published, each an ecosystem and the address of the store asked "
                                + "for it, joined by an equals sign; the answer is kept beside the artifact and "
                                + "read as its evidence. Empty, nothing is asked. A Homebrew mirror names GitHub's "
                                + "store for the bottles homebrew-core's CI attests - \""
                                + AttestationLookupObserver.HOMEBREW_STORE + "\" - asked unauthenticated for public "
                                + "attestations. A store answering 404 holds nothing for the digest; any other "
                                + "failure is logged and the publish is unaffected.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).form(Setting.Form.LINES).advanced(),
                new Setting(SignaturePolicy.INVALID, "Compliance", "Invalid-signature action",
                        "Verdict for an artifact whose signature does not match its bytes - the artifact was altered "
                                + "after signing, or the signature was made for different content. Of the signature "
                                + "outcomes, this is the only one that is evidence of something actively wrong. It can "
                                + "be refused, held for review, or let through with the finding recorded.",
                        Setting.Choice.VERDICTS, SignaturePolicy.INVALID_DEFAULT, true,
                        Setting.Scope.TENANT).standard(),
                new Setting(SignaturePolicy.UNTRUSTED, "Compliance", "Untrusted-signer action",
                        "Verdict for a well-formed signature by a signer this deployment has no reason to believe - no "
                                + "key for it, or a key not admitted for that namespace. It is what every signed "
                                + "artifact reads as until an operator admits a signer, and nothing known to be wrong, "
                                + "so by default it is served and the outcome recorded on the version. A deployment "
                                + "that has admitted the signers it relies on holds or refuses the rest.",
                        Setting.Choice.VERDICTS, SignaturePolicy.UNTRUSTED_DEFAULT, true,
                        Setting.Scope.TENANT).standard(),
                new Setting(SignaturePolicy.CHANGED, "Compliance", "Signer-changed action",
                        "Verdict for a coordinate signed by a different signer than its earlier versions carried. A "
                                + "legitimate key rotation and a compromised account look identical here, and only a "
                                + "person can tell them apart, which makes holding it for review the fitting answer "
                                + "rather than refusing it. This is the case a single global keyring cannot see, "
                                + "because the signature is perfectly valid.",
                        Setting.Choice.VERDICTS, SignaturePolicy.CHANGED_DEFAULT, true,
                        Setting.Scope.TENANT).standard(),
                new Setting(SignaturePolicy.MISSING, "Compliance", "Missing-signature action",
                        "Verdict for an artifact carrying no signature where its format expects one. At screen time "
                                + "this is often not yet a fact: a publish is several requests and the signature may "
                                + "still be in flight, so holding on it sends every properly signed release through "
                                + "the review queue. A deployment that requires every artifact to arrive signed holds "
                                + "or refuses it. The proxy path has a dial of its own.",
                        Setting.Choice.VERDICTS, SignaturePolicy.MISSING_DEFAULT, true,
                        Setting.Scope.TENANT).standard(),
                new Setting(SignaturePolicy.MISSING_PROXY, "Compliance", "Missing-signature action on the proxy path",
                        "Verdict for a proxied artifact carrying no signature where its format expects one, whatever "
                                + "the publish-path dial says. An upstream carries artifacts published long before its "
                                + "own signing requirement existed, and a proxy that holds them all stops being a "
                                + "proxy. The signatures an upstream publishes beside an artifact are fetched before "
                                + "the screen decides, so a deployment mirroring a registry that signs everything can "
                                + "hold or refuse it.",
                        Setting.Choice.VERDICTS, SignaturePolicy.MISSING_PROXY_DEFAULT, true,
                        Setting.Scope.TENANT).advanced(),
                new Setting(SignaturePolicy.QUALITY_FLOOR, "Compliance", "Signature quality floor",
                        "The grade below which a signature raises a finding; with no floor, quality is reported and "
                                + "never gated. The grade is arithmetic over the signature packet and the key "
                                + "(algorithm, bit length, the digest the signature was made over, expiry), never a "
                                + "judgement about the signer.",
                        Setting.Kind.CHOICE, List.of("none", "UNUSABLE", "WEAK", "ACCEPTABLE", "STRONG"), "none",
                        true, Setting.Scope.TENANT).advanced(),
                new Setting(SignaturePolicy.QUALITY_ACTION, "Compliance", "Below-floor quality action",
                        "What a signature below the quality floor does. A weak signature is still a signature, and an "
                                + "operator raising a floor is usually asking to be told rather than to be refused: "
                                + "allowing it reports it, while holding or refusing it makes the floor a gate.",
                        Setting.Choice.VERDICTS, SignaturePolicy.QUALITY_ACTION_DEFAULT, true,
                        Setting.Scope.TENANT).advanced());
    }
}
