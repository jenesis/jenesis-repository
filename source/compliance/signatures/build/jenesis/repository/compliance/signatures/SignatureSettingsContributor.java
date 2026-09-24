package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The dials that tell this deployment which publisher signatures it believes, surfacing on the settings screens
 * exactly when this module is installed - without it they would guard nothing.
 *
 * <p>Both are {@link Setting.Scope#TENANT}: which keys are trusted, and for which namespaces, is a statement one
 * tenant makes about its own supply chain, and two tenants sharing a deployment have no reason to share a keyring.
 */
public final class SignatureSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(ConfiguredSignerTrust.KEYS, "Compliance", "Trusted signing keys",
                        "The armoured OpenPGP public keys this deployment verifies publisher signatures against - one "
                                + "or more concatenated -----BEGIN PGP PUBLIC KEY BLOCK----- sections. Empty trusts "
                                + "nobody: a signature is still read and graded, but none is reported trusted, "
                                + "because a deployment that has named no keys has given no grounds to believe "
                                + "anyone.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(ConfiguredSignerTrust.CERTIFICATES, "Compliance", "Trusted signing certificates",
                        "The PEM certificates a PKCS#7 (CMS) publisher signature must chain to - one or more "
                                + "concatenated -----BEGIN CERTIFICATE----- blocks: a NuGet author or repository "
                                + "signing root, a Swift registry's. The chain is built from the certificates the "
                                + "signature carries, judged at its signing time and never revoked online, since an "
                                + "OCSP fetch would make a publish depend on a third party answering. Empty trusts "
                                + "nobody, as the keyring above does for OpenPGP.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(ConfiguredSignerTrust.PUBLIC_KEYS, "Compliance", "Trusted signing public keys",
                        "The PEM public keys a bare RSA publisher signature is verified against - an Alpine package's "
                                + "signature member, whose key the client keeps in /etc/apk/keys/. One or more "
                                + "-----BEGIN PUBLIC KEY----- blocks, each optionally preceded by a line \"# <keyfile>\" "
                                + "naming the key file the package names (a signature naming a listed key is judged by "
                                + "that key alone; one naming none is tried against the unnamed keys). Empty trusts nobody.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(ConfiguredSignerTrust.PINS, "Compliance", "Pinned signers",
                        "Per-namespace pinned signers, e.g. \"org.apache.* = openpgp:0x1234ABCD\", comma- or "
                                + "newline-separated, a trailing * matching a whole namespace. A namespace carrying a "
                                + "pin admits only the signers pinned to it; one without falls back to trusting any "
                                + "key above. Scoping is the point: a key admitted for one namespace should not "
                                + "thereby vouch for another, which is the shape a compromised-but-real key exploits.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(ConfiguredSignerTrust.SIGSTORE_ROOT, "Compliance", "Sigstore trusted root",
                        "The Sigstore trusted root this deployment verifies bundles against - the JSON a "
                                + "`cosign trusted-root` or the public-good TUF repository serves, naming the Fulcio "
                                + "certificate authorities and the Rekor transparency logs to believe. Set this for a "
                                + "self-hosted Fulcio, or to pin the public one by hand; left empty, a root is fetched "
                                + "instead if - and only if - the trusted root URL below names one. With neither set, "
                                + "no bundle verifies against anything, which is the shipped posture. Holding a root "
                                + "trusts nobody by itself: a verified "
                                + "bundle is trusted only where a pinned signer names its identity, "
                                + "sigstore:<issuer>|<subject>, since the public-good Fulcio certifies anyone the "
                                + "issuers know.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(TrustedRootTask.URL, "Compliance", "Sigstore trusted root URL",
                        "Where the Sigstore trusted root is fetched from when none is pasted above. Empty (the "
                                + "default) fetches nothing, so an installation makes no outbound call; for the "
                                + "public-good instance set it to "
                                + TrustedRootTask.DEFAULT_URL + " - fetched by the trusted-root "
                                + "pass on its own cadence and stored, never on a request path. Taking the document "
                                + "over HTTPS trusts the host serving it; the same root is served through Sigstore's "
                                + "TUF repository with its own signatures, which is the stronger statement and what "
                                + "an internal mirror should be a mirror of. A root arriving after versions were "
                                + "screened does not re-judge them: their recorded summaries say what was concluded "
                                + "when nothing could be verified, and only a re-derivation - a late sidecar, a "
                                + "re-publish - revisits that.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(TrustedRootTask.INTERVAL.key(), "Compliance", "Sigstore trusted root interval",
                        "How often the trusted root is fetched again, as a duration. A root changes about as often "
                                + "as a certificate authority rotates, and an unchanged document is not rewritten, "
                                + "so a daily pass costs one read.",
                        Setting.Kind.STRING, TrustedRootTask.INTERVAL.fallbackText(), true),
                new Setting(ProvenanceTrust.ACCEPT, "Compliance", "Accept signatures by provenance",
                        "The OIDC issuers whose keyless identities are trusted by provenance, comma- or "
                                + "newline-separated - GitHub Actions' https://token.actions.githubusercontent.com "
                                + "being the one to name first. A Sigstore bundle by an identity of a listed issuer is "
                                + "trusted for a coordinate when the signing workflow belongs to the repository the "
                                + "coordinate's own metadata names - a POM's <scm>, a package.json's repository - as "
                                + "the maintainer record kept per coordinate has it; a workflow of any other "
                                + "repository, a fork included, stays untrusted. Empty (the default) admits nothing "
                                + "this way, and a pinned signer still decides ahead of it.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(KeyDiscoveryTask.SOURCES, "Compliance", "Signing-key discovery",
                        "Sources to fetch the signing keys this deployment does not hold from, comma-separated, "
                                + "asked in the order named; empty (the default) fetches nothing and the pass does not "
                                + "run, so an installation makes no outbound call until this names a source. The two "
                                + "public keyservers are one word each: \"keyserver.ubuntu.com,keys.openpgp.org\" asks "
                                + "both, in that order, which is the order the build tool asks them in - the first "
                                + "keeps every user id a key carries, the second serves them only for an address its "
                                + "owner "
                                + "verified, and a key often exists on one and not the other. Supported: "
                                + "keyserver.ubuntu.com and keys.openpgp.org, asked by the "
                                + "signature's own key id; wkd, the Web Key Directory of each e-mail address the "
                                + "artifact's own metadata names as a maintainer (a POM's developers, a "
                                + "package.json's author and maintainers); github, the keys published by each GitHub "
                                + "login that metadata names (a developer's profile, the repository's owner). A "
                                + "discovered key verifies a signature but is not trusted: the outcome stays "
                                + "untrusted, saying the key was discovered, until the key is added to the trusted "
                                + "signing keys or the sources are accepted below - and a key found through a "
                                + "maintainer is then trusted only for artifacts whose metadata names that "
                                + "maintainer, never for the repository at large. The fetch runs on the "
                                + "key-discovery pass, never on a publish.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(KeyDiscoveryTask.ACCEPT, "Compliance", "Accept discovered keys",
                        "Trust the keys the discovery sources served: a key looked up by its own id as if the "
                                + "operator had pasted it into the trusted signing keys, a key found through a "
                                + "maintainer for the artifacts that name that maintainer. Off by default: discovery "
                                + "then only lets a signature be verified and named, and admission stays a human's "
                                + "decision per key.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.TENANT),
                new Setting(KeyDiscoveryTask.URL, "Compliance", "Key discovery server",
                        "Where keys.openpgp.org is reached - the public instance by default, or an internal "
                                + "mirror of it that speaks the same lookup by key id.",
                        Setting.Kind.STRING, KeyDiscoveryTask.DEFAULT_URL, true, Setting.Scope.TENANT),
                new Setting(KeyDiscoveryTask.UBUNTU_URL, "Compliance", "HKP key discovery server",
                        "Where keyserver.ubuntu.com is reached - the public instance by default, or any host "
                                + "speaking the HKP lookup (op=get&options=mr&search=0x<key id>), which every "
                                + "SKS-descended keyserver and most internal mirrors do.",
                        Setting.Kind.STRING, KeyDiscoveryTask.DEFAULT_UBUNTU, true, Setting.Scope.TENANT),
                new Setting(KeyDiscoveryTask.INTERVAL.key(), "Compliance", "Key discovery interval",
                        "How often the key-discovery pass asks the named sources for the keys still wanted, as a "
                                + "duration; a key a source did not have is asked for again after a day.",
                        Setting.Kind.STRING, KeyDiscoveryTask.INTERVAL.fallbackText(), true),
                new Setting(SignatureSweepTask.ENABLED, "Compliance", "Retroactive signature sweep",
                        "Apply the signature dials below to what is already published: the sweep re-judges the "
                                + "signature outcome and grade the gate recorded for each version under the current "
                                + "dials and holds a version they no longer admit, in the same review queue as a "
                                + "publish-time hold. Off by default, because a tightened dial can hold much of a "
                                + "repository at once; switch it on with the change and read the queue. It judges the "
                                + "record, never the bytes: a key admitted or withdrawn since changes nothing here, "
                                + "and nothing is auto-released when a dial is loosened again.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.TENANT),
                new Setting(SignatureSweepTask.INTERVAL.key(), "Compliance", "Retroactive signature sweep interval",
                        "How often the signature sweep runs while switched on, as a duration; every version is "
                                + "judged on its first and every Nth pass, the versions published since between.",
                        Setting.Kind.STRING, SignatureSweepTask.INTERVAL.fallbackText(), true),
                new Setting(AttestationLookupObserver.STORES, "Compliance", "Attestation lookup by digest",
                        "The attestation stores asked, by the artifact's digest, for the bundles they hold for an "
                                + "artifact just published, one <ecosystem> = <url> per line; the answer is kept "
                                + "beside the artifact and read as its evidence. Empty by default, so nothing is "
                                + "asked. A Homebrew mirror sets \"" + AttestationLookupObserver.HOMEBREW_STORE
                                + "\", GitHub's store for the bottles homebrew-core's CI attests, asked "
                                + "unauthenticated for public attestations. A store answering 404 holds nothing for "
                                + "the digest; any other failure is logged and the publish is unaffected.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT),
                new Setting(SignaturePolicy.INVALID, "Compliance", "Invalid-signature action",
                        "Verdict for an artifact whose signature does not match its bytes - the artifact was altered "
                                + "after signing, or the signature was made for different content. REJECT refuses it "
                                + "(the default, and the only one of these outcomes that is evidence of something "
                                + "actively wrong), QUARANTINE holds it for review, ALLOW lets it through with the "
                                + "finding recorded.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.INVALID_DEFAULT, true,
                        Setting.Scope.TENANT),
                new Setting(SignaturePolicy.UNTRUSTED, "Compliance", "Untrusted-signer action",
                        "Verdict for a well-formed signature by a signer this deployment has no reason to believe - "
                                + "no key for it, or a key not admitted for that namespace. QUARANTINE holds it for "
                                + "review (the default: this is the common outcome the day enforcement is switched "
                                + "on, and a decision waiting on a human rather than something known to be wrong).",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.UNTRUSTED_DEFAULT, true,
                        Setting.Scope.TENANT),
                new Setting(SignaturePolicy.CHANGED, "Compliance", "Signer-changed action",
                        "Verdict for a coordinate signed by a different signer than its earlier versions carried. "
                                + "QUARANTINE by default and deliberately not REJECT: a legitimate key rotation and "
                                + "a compromised account look identical here, and only a person can tell them apart. "
                                + "This is the case a single global keyring cannot see, because the signature is "
                                + "perfectly valid.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.CHANGED_DEFAULT, true,
                        Setting.Scope.TENANT),
                new Setting(SignaturePolicy.MISSING, "Compliance", "Missing-signature action",
                        "Verdict for an artifact carrying no signature where its format expects one. Defaults to "
                                + "ALLOW, alone among the signature dials, because at screen time this is not yet a "
                                + "fact: a publish is several requests and the signature is legitimately still in "
                                + "flight when the artifact it covers is screened, so holding on it would put every "
                                + "properly signed release through the review queue on its way out of it. The other "
                                + "outcomes are decidable the moment the material is there and keep their secure "
                                + "floors. Set QUARANTINE or REJECT for a deployment that requires every artifact to "
                                + "arrive signed. Always ALLOW on the proxy path whatever this says: an upstream "
                                + "carries artifacts published long before its own signing requirement existed.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.MISSING_DEFAULT, true,
                        Setting.Scope.TENANT),
                new Setting(SignaturePolicy.MISSING_PROXY, "Compliance", "Missing-signature action on the proxy path",
                        "Verdict for a proxied artifact carrying no signature where its format expects one. ALLOW "
                                + "by default, whatever the publish-path dial says: an upstream carries artifacts "
                                + "published long before its own signing requirement existed, and a proxy that holds "
                                + "every one of them stops being a proxy. The pull-through fetches what the upstream "
                                + "publishes beside an artifact - Maven's .asc and .sigstore.json, a registry's "
                                + "attestations - before the screen decides, so an artifact that arrives unsigned "
                                + "here really is unsigned upstream; a deployment mirroring a registry that signs "
                                + "everything can set QUARANTINE or REJECT.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.MISSING_PROXY_DEFAULT, true,
                        Setting.Scope.TENANT),
                new Setting(SignaturePolicy.QUALITY_FLOOR, "Compliance", "Signature quality floor",
                        "The grade below which a signature raises a finding - none (the default, quality is reported "
                                + "and never gated), unusable, weak, acceptable or strong. The grade is arithmetic "
                                + "over the signature packet and the key (algorithm, bit length, the digest the "
                                + "signature was made over, expiry), never a judgement about the signer.",
                        Setting.Kind.CHOICE, List.of("none", "UNUSABLE", "WEAK", "ACCEPTABLE", "STRONG"), "none",
                        true, Setting.Scope.TENANT),
                new Setting(SignaturePolicy.QUALITY_ACTION, "Compliance", "Below-floor quality action",
                        "What a signature below the quality floor does. ALLOW by default: a weak signature is still "
                                + "a signature, and an operator raising a floor is asking to be told rather than to "
                                + "be refused until they say otherwise.",
                        Setting.Kind.CHOICE, List.of("ALLOW", "QUARANTINE", "REJECT"),
                                SignaturePolicy.QUALITY_ACTION_DEFAULT, true,
                        Setting.Scope.TENANT));
    }
}
