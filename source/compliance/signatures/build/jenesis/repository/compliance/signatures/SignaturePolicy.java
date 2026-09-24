package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.Verdict;

/**
 * The gate's signature dimension: it turns what the inspector found about a publisher's signature into a verdict.
 *
 * <p>It decides and reads nothing. Every fact it needs was stamped onto the subject by the inspector, which is what
 * keeps this pure as the contract requires - no key, no artifact, no store. The split is not tidiness: verification
 * needs the bytes and the key at one moment, and only an inspector is ever handed the bytes.
 *
 * <h2>Four outcomes, four dials, because they are four different statements</h2>
 *
 * <ul>
 *   <li><b>Invalid</b> - the bytes do not match the signature made for them. Corruption or tampering, and the only
 *       one of these that is evidence of something actively wrong; it defaults to REJECT.</li>
 *   <li><b>Untrusted</b> - a perfectly good signature by a signer this deployment has no reason to believe. Very
 *       common the day enforcement is switched on, and a hold for review rather than a refusal.</li>
 *   <li><b>Signer changed</b> - this coordinate's earlier versions carried a different signer. The attack a
 *       single global keyring cannot see, because the key is genuinely valid; held for a human, never refused
 *       outright, since a legitimate key rotation looks exactly the same and only a person can tell them apart.</li>
 *   <li><b>Missing</b> - the format expected a signature and none arrived.</li>
 * </ul>
 *
 * <p>Collapsing any two of these would be a real loss. "Refuse tampering" and "refuse artifacts whose maintainer we
 * have not met" are not the same policy, and a deployment that cannot say the first without the second will end up
 * saying neither.
 *
 * <h2>Why missing has a dial of its own on the proxy path</h2>
 *
 * The same reason the licence dimension softens an unknown licence: an upstream carries artifacts published long
 * before its own signing requirement existed, and a proxy that quarantines every one of them stops being a proxy. A
 * hosted publish is the deployment's own supply chain and is held to the stricter answer. So the proxy leg reads
 * {@code signature-missing-proxy}, ALLOW by default, rather than {@code signature-missing}. It is a dial and not a
 * constant because the pull-through now fetches what an upstream publishes beside an artifact - Maven's
 * {@code .asc} and {@code .sigstore.json}, a registry's attestations - before the screen decides, so "carries no
 * signature" on a proxied artifact is a fact about the upstream rather than about which sidecars a client
 * happened to request, and an operator mirroring a registry that signs everything can hold what arrives unsigned.
 */
final class SignaturePolicy implements GatePolicy {

    /** The hold kind a retroactive sweep records under, so a publish-time hold leaves the record a sweep would. */
    static final String KIND = "signature";

    static final String INVALID = "signature-invalid";
    static final String UNTRUSTED = "signature-untrusted";
    static final String CHANGED = "signature-signer-changed";
    static final String MISSING = "signature-missing";
    static final String MISSING_PROXY = "signature-missing-proxy";
    static final String QUALITY_FLOOR = "signature-quality-floor";
    static final String QUALITY_ACTION = "signature-quality-action";

    /**
     * The verdict each dial carries when a deployment sets nothing - defined here, on the code that applies them,
     * and referenced by the setting catalogue rather than written out a second time there.
     *
     * <p>They are not all the same, and the split is the dimension's whole argument. {@code INVALID_DEFAULT} is
     * {@link Verdict#REJECT} and {@code UNTRUSTED_DEFAULT}/{@code CHANGED_DEFAULT} are
     * {@link Verdict#QUARANTINE}, because each is a statement about evidence that has <em>arrived</em>: a signature
     * that does not stand for these bytes means something is actively wrong, and one by a signer nobody vouched for
     * is a decision for an operator rather than a defect. {@code MISSING_DEFAULT} is {@link Verdict#ALLOW} alone
     * among them, because "carries no signature" at screening time is usually a statement about the ordering of two
     * requests - a deploy sends the {@code .asc} after the artifact it signs - rather than about the artifact.
     *
     * <p>Written once because the licence twin of this dimension was not: its unknown-verdict default lived in three
     * places, a change moved two of them, and the product reported a floor it was not applying.
     */
    static final String INVALID_DEFAULT = "REJECT";
    static final String UNTRUSTED_DEFAULT = "QUARANTINE";
    static final String CHANGED_DEFAULT = "QUARANTINE";
    static final String MISSING_DEFAULT = "ALLOW";

    /** The proxy leg's own missing-signature default, ALLOW for the reason the class comment gives: an upstream
     *  carries artifacts from before its signing requirement, and a proxy that holds every one of them is no proxy. */
    static final String MISSING_PROXY_DEFAULT = "ALLOW";
    /** Below-floor quality is informational until an operator says otherwise: a grade is a judgement about a
     *  signature that verified, so it names a risk rather than a failure. */
    static final String QUALITY_ACTION_DEFAULT = "ALLOW";

    private final Verdict invalid;
    private final Verdict untrusted;
    private final Verdict changed;
    private final Verdict missing;
    private final Verdict missingOnProxy;
    private final SignatureQuality.Grade floor;
    private final Verdict belowFloor;

    private SignaturePolicy(Verdict invalid, Verdict untrusted, Verdict changed, Verdict missing,
                            Verdict missingOnProxy, SignatureQuality.Grade floor, Verdict belowFloor) {
        this.invalid = invalid;
        this.untrusted = untrusted;
        this.changed = changed;
        this.missing = missing;
        this.missingOnProxy = missingOnProxy;
        this.floor = floor;
        this.belowFloor = belowFloor;
    }

    static SignaturePolicy from(UnaryOperator<String> config) {
        return new SignaturePolicy(
                GateDimension.verdict(INVALID, config.apply(INVALID), Verdict.valueOf(INVALID_DEFAULT)),
                GateDimension.verdict(UNTRUSTED, config.apply(UNTRUSTED), Verdict.valueOf(UNTRUSTED_DEFAULT)),
                GateDimension.verdict(CHANGED, config.apply(CHANGED), Verdict.valueOf(CHANGED_DEFAULT)),
                GateDimension.verdict(MISSING, config.apply(MISSING), Verdict.valueOf(MISSING_DEFAULT)),
                GateDimension.verdict(MISSING_PROXY, config.apply(MISSING_PROXY), Verdict.valueOf(MISSING_PROXY_DEFAULT)),
                grade(config.apply(QUALITY_FLOOR)),
                GateDimension.verdict(QUALITY_ACTION, config.apply(QUALITY_ACTION), Verdict.valueOf(QUALITY_ACTION_DEFAULT)));
    }

    /** This policy as the proxy leg applies it: the missing-signature verdict is the proxy dial's, every other
     *  verdict the same - a signature that does not stand for its bytes is as alarming whichever way it arrived. */
    SignaturePolicy onProxy() {
        return new SignaturePolicy(invalid, untrusted, changed, missingOnProxy, missingOnProxy, floor, belowFloor);
    }

    @Override
    public List<ComplianceGate.Finding> assess(ComplianceGate.Subject subject,
                                               List<AdvisorySource.Advisory> advisories) {
        if (subject.signatures().isEmpty()) {
            return List.of();
        }
        List<ComplianceGate.Finding> findings = new ArrayList<>();
        for (ComplianceGate.Signature signature : subject.signatures()) {
            findings.addAll(assess(signature));
        }
        return findings;
    }

    private List<ComplianceGate.Finding> assess(ComplianceGate.Signature signature) {
        List<ComplianceGate.Finding> findings = new ArrayList<>();
        String where = signature.coveredPath();
        switch (signature.outcome()) {
            case INVALID -> findings.add(finding(invalid, "Signature does not match the bytes of " + where
                    + signedBy(signature) + " - the artifact was altered after it was signed, or the signature was "
                    + "made for different content", "invalid"));
            case UNTRUSTED -> findings.add(finding(untrusted, "No trusted signer for " + where + signedBy(signature)
                    + (DiscoveredKeys.SOURCE.equals(signature.keySource())
                            ? " - the signature verifies by a discovered key nobody has admitted: add the key to "
                                    + ConfiguredSignerTrust.KEYS + " to trust it, or set " + KeyDiscoveryTask.ACCEPT
                                    + " to trust what the discovery sources serve (a key found through a maintainer "
                                    + "then admits only the artifacts that name them)"
                            : signature.signer() != null && SignerIdentity.SIGSTORE.equals(signature.signer().scheme())
                            ? " - a keyless identity is believed here only where " + ConfiguredSignerTrust.PINS
                                    + " names it, or where " + ProvenanceTrust.ACCEPT + " names its issuer and the "
                                    + "signing workflow belongs to the repository this artifact's own metadata declares"
                            : " - the signature is well-formed, but this deployment has no reason to believe that "
                                    + "signer here"), "untrusted"));
            case ABSENT -> findings.add(finding(missing, "No publisher signature for " + where
                    + ", which its format expects to carry one", "missing"));
            case UNREADABLE -> findings.add(finding(untrusted, "Signature material for " + where
                    + " is present but could not be read (" + signature.location() + ") - which is not the same as "
                    + "carrying none, and is not something to conclude anything from", "unreadable"));
            case VALID -> {
                // Nothing to say about the signature itself, unless the coordinate's history says who should have
                // made it: a verified signature by a trusted signer that is not the signer every earlier version
                // carried - or not the one an operator pinned - is the continuity finding, and a key rotation, a
                // maintainer handover and a takeover all look exactly like it at this point, which is why it is a
                // dial rather than a verdict. Its quality may still be worth saying something about, and that is
                // a separate dial: an operator raising a quality floor is not thereby changing who they trust.
                if (signature.expected() != null) {
                    findings.add(finding(changed, "Signer changed for " + where + signedBy(signature) + " - "
                            + expectedBy(signature.expected()), "changed"));
                }
            }
        }
        if (floor != SignatureQuality.Grade.UNASSESSED && signature.quality() != null
                && signature.quality().grade() != SignatureQuality.Grade.UNASSESSED
                && !signature.quality().grade().atLeast(floor)) {
            findings.add(finding(belowFloor, "Signature on " + where + " grades "
                    + signature.quality().describe() + ", below the configured floor of "
                    + floor.name().toLowerCase(Locale.ROOT), "quality"));
        }
        return findings;
    }

    /** A finding that also names what the hold kind records, so a publish-time hold leaves the record a retroactive
     *  sweep would and a human's release writes the same sticky override. */
    private static ComplianceGate.Finding finding(Verdict verdict, String detail, String token) {
        return verdict == Verdict.ALLOW
                ? new ComplianceGate.Finding(verdict, detail)
                : new ComplianceGate.Finding(verdict, detail, ComplianceGate.Hold.of(KIND, Set.of(token)));
    }

    /** What the expectation rests on: an operator's pin, or the versions that established it - the difference
     *  between "unexpected" and something an operator can act on. */
    private static String expectedBy(SignerTrust.Expectation expected) {
        if (expected.pinned()) {
            return "the operator pinned " + expected.signer().wire() + " as this coordinate's signer";
        }
        return expected.signer().wire() + " signed its " + expected.versions() + " earlier version"
                + (expected.versions() == 1 ? "" : "s") + (expected.since() == null ? "" : " since " + expected.since());
    }

    /** The signer, where one was read - which is every outcome that got far enough to parse the packet. An operator
     *  told only that something is wrong cannot act; told which key, they can. */
    private static String signedBy(ComplianceGate.Signature signature) {
        return signature.signer() == null ? "" : ", signed by " + signature.signer().wire();
    }

    private static SignatureQuality.Grade grade(String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
            return SignatureQuality.Grade.UNASSESSED;   // no floor configured: quality is reported, never gated
        }
        for (SignatureQuality.Grade grade : SignatureQuality.Grade.values()) {
            if (grade.name().equalsIgnoreCase(value.strip())) {
                return grade;
            }
        }
        throw new IllegalArgumentException(QUALITY_FLOOR + " is not a signature grade: " + value);
    }
}
