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
 * The gate's signature dimension: turns what the inspector found about a publisher's signature into a verdict. It
 * reads nothing, since every fact was stamped onto the subject by the inspector, which alone is handed the bytes.
 *
 * <p>Four outcomes, each with its own dial, since refusing tampering and refusing an unfamiliar maintainer are
 * different policies:
 * <ul>
 *   <li><b>Invalid</b> - the bytes do not match the signature: tampering or corruption, REJECT by default.</li>
 *   <li><b>Untrusted</b> - a good signature by a signer the deployment has no reason to believe: recorded and served,
 *       since that is every signer before an operator admits any.</li>
 *   <li><b>Signer changed</b> - earlier versions carried another signer: held for a person, since a key rotation looks
 *       the same as a takeover.</li>
 *   <li><b>Missing</b> - the format expected a signature and none arrived.</li>
 * </ul>
 *
 * <p>On the proxy path a missing signature reads {@code signature-missing-proxy}, ALLOW by default, since an upstream
 * holds artifacts from before its signing requirement; the pull-through fetches sidecars first, so stricter is a real
 * choice for a registry that signs everything.
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
     * The dials' defaults, defined here and referenced by the settings catalogue. Evidence of something wrong is
     * acted on: invalid is {@link Verdict#REJECT} and changed {@link Verdict#QUARANTINE}. Untrusted is
     * {@link Verdict#ALLOW}: a deployment that admitted no signer would otherwise hold every signed artifact it is
     * sent, and the outcome is recorded on the version for an operator to see. Missing is {@link Verdict#ALLOW},
     * since at screening time it usually means the {@code .asc} is still in flight.
     */
    static final String INVALID_DEFAULT = "REJECT";
    static final String UNTRUSTED_DEFAULT = "ALLOW";
    static final String CHANGED_DEFAULT = "QUARANTINE";
    static final String MISSING_DEFAULT = "ALLOW";

    /** The proxy leg's missing-signature default (see the class comment). */
    static final String MISSING_PROXY_DEFAULT = "ALLOW";
    /** Below-floor quality is informational by default: a grade judges a signature that verified. */
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

    /** This policy on the proxy leg: the proxy dial's missing-signature verdict, every other verdict the same. */
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
                // A trusted signature by a signer other than the expected one is the continuity finding; its quality
                // is judged separately.
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

    /** A finding naming the hold kind's token, so a publish-time hold records what the sweep would. */
    private static ComplianceGate.Finding finding(Verdict verdict, String detail, String token) {
        return verdict == Verdict.ALLOW
                ? new ComplianceGate.Finding(verdict, detail)
                : new ComplianceGate.Finding(verdict, detail, ComplianceGate.Hold.of(KIND, Set.of(token)));
    }

    /** What the expectation rests on: a pin, or the versions that established it. */
    private static String expectedBy(SignerTrust.Expectation expected) {
        if (expected.pinned()) {
            return "the operator pinned " + expected.signer().wire() + " as this coordinate's signer";
        }
        return expected.signer().wire() + " signed its " + expected.versions() + " earlier version"
                + (expected.versions() == 1 ? "" : "s") + (expected.since() == null ? "" : " since " + expected.since());
    }

    /** The signer, where one was read, so the operator knows which key. */
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
