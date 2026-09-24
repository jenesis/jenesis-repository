package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.compliance.SignatureQuality;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.SignerTrust;
import build.jenesis.repository.compliance.TrustAware;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The one inspector that verifies inbound publisher signatures, for every format that has them.
 *
 * <h2>Why there is one of these and not one per format</h2>
 *
 * What differs between formats is where the signature material sits and what it covers, and a format already states
 * both through {@code ArtifactSignatures}: it hands back the signature bytes and a reopenable stream of exactly what
 * they commit to. Everything after that - parsing the packet, checking it against the deployment's key material,
 * grading it, naming the signer - is identical whether the material came from a Maven {@code .asc} sidecar or a
 * {@code .deb}'s embedded {@code _gpgorigin} member, and this class never learns which it was.
 *
 * <p>That is the whole point. A per-format inspector would make the capability a property of whichever format was
 * written first, and the second would arrive as a copy with its own drift. Here a format contributes a declaration and
 * inherits verification, trust, grading and the signer index; the only thing it can get wrong is its own layout.
 *
 * <h2>Trust arrives by overlay</h2>
 *
 * Verification needs three things at once - the artifact bytes, the signature, and the key. Only an inspector is ever
 * handed the bytes, and only the screen has the tenant's durable key material, so the screen overlays a
 * {@link SignerTrust} through {@link TrustAware} exactly as it overlays the maintainer-health ledger onto the
 * health-aware dimension. Built without one, this verifies against nothing and reports every signature
 * {@linkplain ComplianceGate.Signature.Outcome#UNTRUSTED untrusted}, which is the fail-closed direction: a deployment
 * that holds no keys has no grounds to believe a signature, and must not be told it does.
 *
 * <h2>The verifiers arrive by discovery</h2>
 *
 * What a scheme's signature is, and how it is checked, is a {@link SignatureScheme} discovered through the
 * compliance SPI: the OpenPGP, PKCS#7, bare-RSA and X.509 verifiers ride the signing module that loads Bouncy
 * Castle, the Sigstore one the keyless module that loads sigstore-java, and this class imports neither library.
 * It owns what every scheme shares - the bound on the body, the choice of the trust source whose material
 * holds the signer, the trust decision on the identity the scheme named, the covering-document comparison -
 * and evidence of a scheme no installed module verifies is reported as exactly that, never skipped.
 *
 * <h2>What it does not do</h2>
 *
 * It reaches no verdict. It records what it found - verified, did not verify, verified by someone we have no reason
 * to believe, absent, unreadable - and the discovered signature dimension turns that into a finding under the
 * operator's dials. Keeping the two apart is what lets the same facts be shown on a screen while enforcement is still
 * switched off.
 */
public final class SignatureInspector implements QualityInspector, TrustAware {

    /**
     * Why a signature was not checked where the artifact could not be seen whole. One constant, because the
     * completeness report keys off it: a screen has to be able to tell "we did not finish looking" from "we looked
     * and there was nothing", and matching on prose would drift the day someone rewords it.
     */
    private static final String NOT_WHOLE = "the artifact was not available whole at this inspection, and a signature "
            + "covers the whole artifact - it is verified off the publish thread instead";

    /**
     * That reason, as a method rather than the constant.
     *
     * <p>javac inlines a {@code static final String} into every reader, so a test comparing against the constant
     * would be comparing against whatever the sentence said the day it was compiled - and would keep passing after
     * the sentence changed. Routing it through a call keeps one value in one place, which is the same reason
     * {@code AuditActions} does it.
     */
    public static String notWholeReason() {
        return NOT_WHOLE;
    }

    private final SignerTrust trust;

    /** The {@link ServiceLoader} constructor: no trust material until the screen overlays some. */
    public SignatureInspector() {
        this(SignerTrust.NONE);
    }

    private SignatureInspector(SignerTrust trust) {
        this.trust = trust;
    }

    @Override
    public QualityInspector withTrust(SignerTrust trust) {
        return new SignatureInspector(trust == null ? SignerTrust.NONE : trust);
    }

    /**
     * Whether any installed format expects this path to be signed, or says it is material covering something that is.
     *
     * <p>Asked of every installed format and unioned rather than taking a first match - two layouts may serve one
     * ecosystem, and which of them answers first is a property of the module path's ordering rather than of the
     * artifact.
     */
    @Override
    public boolean handles(String path) {
        for (ArtifactSignatures format : signatureFormats()) {
            if (!format.expects(path).isEmpty() || format.covers(path).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A signature is read beside an artifact, never in place of the artifact's own inspection: this claims a path
     * where a format places it on a coordinate, and otherwise only where something is stored beside it for this
     * inspector to read - one point read per convention the format declares, never the body. A raw file with
     * nothing beside it stays unclaimed content, admitted without a read; one with a bundle beside it is read, and
     * the screen assesses the bundle beside the file's own deny-list screening rather than in place of it.
     */
    @Override
    public boolean claims(String path, Lookup siblings) {
        List<ArtifactSignatures> undescribed = new ArrayList<>();
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (installed instanceof ArtifactSignatures format
                    && (!format.expects(path).isEmpty() || format.covers(path).isPresent())) {
                Optional<ArtifactDescriptor> described = describe(installed, path);
                if (described.isPresent() && described.get().coordinate() != null) {
                    return true;
                }
                if (format.embedsEvidence(path)) {
                    // The format says its evidence is in the artifact's own bytes, which the probe below will not
                    // open. Claiming on the declaration is the whole of the cost: a claimed artifact's head is
                    // read once for every inspector that claimed it, so where the layout's own inspector claims
                    // the path anyway this adds no read at all.
                    return true;
                }
                undescribed.add(format);
            }
        }
        // The probe hands the format a body that is present and never opened: evidence() answers from the sibling
        // reads alone, and a sidecar that is there is the claim.
        ArtifactSignatures.Material probe = new LookupMaterial(siblings, InputStream::nullInputStream);
        for (ArtifactSignatures format : undescribed) {
            try {
                if (!format.expects(path).isEmpty() && !format.evidence(path, probe).isEmpty()) {
                    return true;
                }
            } catch (IOException | RuntimeException unreadable) {
                return true;   // something is there and could not be read as a sidecar: that is a finding, not silence
            }
        }
        return false;
    }

    /**
     * A published piece of signature material completes the artifact it covers.
     *
     * <p>This is not a Maven special case, though Maven is where it bites hardest: a deploy is several requests and
     * the signature follows its subject, so the artifact is screened while the only evidence about it is still in
     * flight. The format says which paths are material and what each covers; this turns that into the version
     * directory whose held neighbours are worth re-assessing, and the screen releases only those the gate now allows
     * on their own stored bytes.
     */
    @Override
    public Optional<String> completes(String path) {
        for (ArtifactSignatures format : signatureFormats()) {
            Optional<String> covered = format.covers(path);
            if (covered.isPresent()) {
                int slash = covered.get().lastIndexOf('/');
                return slash < 0 ? Optional.empty() : Optional.of(covered.get().substring(0, slash + 1));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, Lookup lookup) throws IOException {
        return inspectArtifact(path, content, lookup);
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, Lookup lookup)
            throws IOException {
        // readNBytes(limit) comes back short ONLY at end of stream, so an array under the prefix tier is provably
        // the whole artifact and anything at the tier may be a prefix. That inference is the only thing this leg has:
        // the publish screen hands over a bounded head and no flag saying whether it truncated.
        boolean whole = content.length < QualityInspector.prefixInspectionLimit();
        return subjects(path, () -> new ByteArrayInputStream(content), lookup, content.length, whole);
    }

    /** It reads the artifact as a stream where one is offered: a signature is a fact about the whole body, and
     *  the material for several schemes rides inside the artifact past any prefix - a NuGet signature entry is
     *  stored last and a zip is read from its directory at the very end. */
    @Override
    public boolean streams() {
        return true;
    }

    /**
     * The full-body tier: the artifact is handed over as a reopenable stream rather than a prefix, so a signature over
     * a multi-gigabyte package is checked without the package ever being in heap.
     *
     * <p>Always {@linkplain Inspection#complete() complete}: unlike an inspector that reads a declaration out of the
     * front of a body and may be cut short by a bound, this one never reads the artifact for its own sake - it feeds
     * the bytes through a digest. A truncated <em>sibling</em> is a different matter and is reported as unreadable
     * material rather than as an incomplete inspection, because the fact it produces is exact.
     */
    @Override
    public Inspection inspectArtifact(String path, Content body, Lookup lookup) throws IOException {
        // The spooled leg is the whole body by contract - that is what the handle is for.
        Inspected inspected = inspected(path, body::open, lookup, body.size(), true);
        // A bound that bound is reported, never swallowed. A screen reads a COMPLETE inspection as "the artifact was
        // seen whole", and an empty answer under it as "understood, declares nothing" - which is exactly how an
        // artifact nobody finished screening gets allowed through.
        return new Inspection(inspected.subjects(), inspected.complete());
    }

    /** What one assessment produced, and whether every read behind it ran to the end. */
    private record Inspected(List<ComplianceGate.Subject> subjects, boolean complete) {
    }

    private List<ComplianceGate.Subject> subjects(String path, ArtifactSignatures.Signed body, Lookup lookup,
                                                 long size, boolean whole) throws IOException {
        return inspected(path, body, lookup, size, whole).subjects();
    }

    private Inspected inspected(String path, ArtifactSignatures.Signed body, Lookup lookup, long size, boolean whole)
            throws IOException {
        // One fan-out over the installed formats, not one per question: the set is the same for every use of it here.
        List<ArtifactSignatures> claiming = new ArrayList<>();
        ArtifactDescriptor described = null;
        String ecosystem = "";
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (!(installed instanceof ArtifactSignatures format) || format.expects(path).isEmpty()) {
                continue;
            }
            claiming.add(format);
            if (ecosystem.isEmpty()) {
                ecosystem = format.ecosystem();
            }
            if (described == null) {
                described = describe(installed, path).orElse(null);
            }
        }
        if (claiming.isEmpty()) {
            return new Inspected(List.of(), true);
        }
        // Without a descriptor the subject still names something an operator can act on, so it falls back to the
        // request path. It is deliberately not dropped: "we could not place this signature on a coordinate" is a fact
        // about a screened artifact, and a silent empty would read as "nothing to check here".
        ComplianceGate.Subject subject = described != null
                ? new ComplianceGate.Subject(described.ecosystem(), described.coordinate(), described.version(),
                        List.of())
                : new ComplianceGate.Subject(ecosystem, path, "", List.of());

        List<ComplianceGate.Signature> found = new ArrayList<>();
        for (ArtifactSignatures format : claiming) {
            found.addAll(assess(path, format, format.expects(path), body, lookup,
                    subject.ecosystem(), subject.coordinate(), size, whole));
        }
        boolean complete = found.stream().noneMatch(SignatureInspector::cutShort);
        if (found.isEmpty()) {
            return new Inspected(List.of(), complete);
        }
        // Continuity: what this coordinate's earlier versions established, or an operator pinned, is asked once per
        // artifact and only when a signature verified - the one outcome a history can contradict - and a verified
        // signature by another signer carries the expectation for the signer-changed dial to decide. Asked of the
        // composed trust, so an operator's pin outranks what was learned.
        if (found.stream().anyMatch(ComplianceGate.Signature::trusted) && subject.coordinate() != null) {
            // Once per scheme rather than once per artifact: an artifact carrying signatures of two schemes has two
            // independent histories, and one expectation laid over both made each read as a change of the other.
            Map<String, Optional<SignerTrust.Expectation>> expectations = new HashMap<>();
            found.replaceAll(signature -> signature.signer() == null ? signature
                    : expectations.computeIfAbsent(signature.signer().scheme(),
                            scheme -> trust.expected(subject.ecosystem(), subject.coordinate(), scheme))
                    .map(signature::expecting).orElse(signature));
        }
        return new Inspected(List.of(subject.withSignatures(found)), complete);
    }

    /** Whether this outcome is one a read bound produced rather than one the artifact did - which the screen must
     *  hear about, or it reads an unfinished inspection as a finished one that found nothing. */
    private static boolean cutShort(ComplianceGate.Signature signature) {
        return signature.outcome() == ComplianceGate.Signature.Outcome.UNREADABLE
                && signature.location() != null
                && (signature.location().endsWith(NOT_WHOLE) || signature.location().contains("inspection bound"));
    }

    /**
     * The coordinate a format places a request path at, through whichever of the two layout families it belongs to -
     * the {@code publish/} namespace Maven and the raw layout use, or a blobs namespace of its own.
     *
     * <p>Asking only one of them is how a capability quietly becomes single-family: every blobs-namespace format -
     * Debian, npm, PyPI, Go, Conan and the rest - would fall through to the path-derived subject and never appear on a
     * coordinate screen, for no reason anyone had decided.
     */
    private static Optional<ArtifactDescriptor> describe(RepositoryFormat format, String path) {
        if (format instanceof ArtifactLayout layout) {
            return layout.describe(path);
        }
        if (format instanceof BlobLayout layout) {
            return layout.describe(path);
        }
        // A format whose coordinate mapping lives in a separate installed layout - the OCI format serves, the
        // OCI layout describes - is asked through that layout, matched by the ecosystem both declare,
        // so its signatures land on a coordinate rather than on a path nothing can pin a signer to.
        if (format instanceof EcosystemLayout claiming) {
            for (RepositoryFormat installed : RepositoryFormat.installed()) {
                if (installed != format && installed instanceof BlobLayout layout
                        && layout.ecosystem().equals(claiming.ecosystem())) {
                    Optional<ArtifactDescriptor> described = layout.describe(path);
                    if (described.isPresent()) {
                        return described;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The signature naming the trust source whose key identified its signer, when one did. */
    private static ComplianceGate.Signature sourced(SignerTrust source, ComplianceGate.Signature signature) {
        return signature.sourced(source == null ? null : source.source());
    }

    /** Everything one format says about one path, verified. */
    private List<ComplianceGate.Signature> assess(String path, ArtifactSignatures format,
                                                  List<ArtifactSignatures.Expectation> expectations,
                                                  ArtifactSignatures.Signed body, Lookup lookup,
                                                  String ecosystem, String coordinate, long size,
                                                  boolean whole) {
        List<ArtifactSignatures.Evidence> evidence;
        try {
            evidence = format.evidence(path, new LookupMaterial(lookup, body));
        } catch (IOException unreadable) {
            // Clause 6 of the seam: material that is present but could not be read. Reporting "absent" here would turn
            // a truncated or corrupt signature into a merely unsigned artifact, which is the one wrong answer.
            return expectations.stream()
                    .map(expected -> ComplianceGate.Signature.unreadable(path, expected.scheme().name(), path,
                            unreadable.getMessage() == null ? "unreadable" : unreadable.getMessage()))
                    .toList();
        }
        // Whether "carries none" is a finding is the format's declaration, and it is read per scheme: an expected
        // scheme no evidence carries is absent whatever else the artifact carries - a package covered by its
        // mirror's signed index still carries no signature of its own where a provisioned keyring says it should.
        // For a coverage that depends on whom the deployment trusts, the trust's answer for this ecosystem is read
        // here, where the trust is already in hand, rather than by the pure expects() a serving path calls.
        Set<ArtifactSignatures.Scheme> carried = EnumSet.noneOf(ArtifactSignatures.Scheme.class);
        for (ArtifactSignatures.Evidence one : evidence) {
            carried.add(one.scheme());
        }
        List<ComplianceGate.Signature> assessed = new ArrayList<>();
        for (ArtifactSignatures.Expectation expected : expectations) {
            boolean required = switch (expected.coverage()) {
                case REQUIRED -> true;
                case REQUIRED_WHEN_TRUSTED -> trust.anchored(ecosystem);
                case OPTIONAL -> false;
            };
            if (required && !carried.contains(expected.scheme())) {
                assessed.add(ComplianceGate.Signature.absent(path, expected.scheme().name()));
            }
        }
        for (ArtifactSignatures.Evidence one : evidence) {
            ComplianceGate.Signature verified = verify(path, one, ecosystem, coordinate, size, whole);
            assessed.add(one.named() == null ? verified : bound(path, one, verified, body, size));
        }
        return assessed;
    }

    /**
     * The trust source that admits this signer for this coordinate, or null when none does. For a scheme whose
     * material names its signer the question goes to the material's holder and to it alone - a deployment's
     * keyring is one pool, and a key admitted for one namespace must not thereby vouch for another, which is the
     * whole point of scoping trust and the shape a takeover exploits when it is not scoped. For a scheme whose
     * material certifies anyone - a Sigstore root - holding the material says nothing about the signer, so every
     * source of the composed trust is asked in order: an operator's pin first, then what the declared repository's
     * provenance admits. The answer names the source, which the signature records as where its trust came from.
     */
    private SignerTrust admitting(SignatureScheme verifier, SignerTrust holder, SignerIdentity signer,
                                  String ecosystem, String coordinate) {
        if (holder == null) {
            return null;   // no material held the signer: the verifier answered NO_KEY, so this is unreachable
        }
        if (verifier.materialNamesSigner()) {
            return holder.trusts(signer, ecosystem, coordinate) ? holder : null;
        }
        for (SignerTrust candidate : trust.parts()) {
            if (candidate.trusts(signer, ecosystem, coordinate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The comparison covering evidence owes once its signature verified: the digest the signed document names for
     * this artifact against the digest of the artifact itself. Equal keeps the verdict; a different digest is a
     * signature made for another artifact - cosign's payload naming another image - and is INVALID, which is the
     * outcome tampering gets and never the "unsigned" an absent signature gets; a document naming nothing is
     * material that could not be read. The document is read whole under the signature bound, since a covering
     * document is a statement and never a package; the artifact is hashed under the inspection bound.
     */
    private static ComplianceGate.Signature bound(String path, ArtifactSignatures.Evidence evidence,
                                                  ComplianceGate.Signature verified,
                                                  ArtifactSignatures.Signed artifact, long size) {
        if (verified.outcome() != ComplianceGate.Signature.Outcome.VALID
                && verified.outcome() != ComplianceGate.Signature.Outcome.UNTRUSTED) {
            return verified;   // nothing verified, so there is nothing to bind
        }
        try {
            byte[] document;
            try (InputStream in = evidence.signed().open()) {
                document = in.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE + 1);
            }
            if (document.length > ArtifactSignatures.Material.LARGEST_SIGNATURE) {
                return ComplianceGate.Signature.unreadable(path, evidence.scheme().name(), evidence.location(),
                        "the signed document is larger than the " + ArtifactSignatures.Material.LARGEST_SIGNATURE
                                + "-byte signature bound");
            }
            Optional<String> named = evidence.named().sha256(document);
            if (named.isEmpty()) {
                return ComplianceGate.Signature.unreadable(path, evidence.scheme().name(), evidence.location(),
                        "the signed document names no digest for this artifact");
            }
            String actual;
            try {
                ArtifactSignatures.Signed bounded = bounded(artifact, size, QualityInspector.fullBodyInspectionLimit());
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                try (InputStream in = bounded.open()) {
                    for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                        sha256.update(buffer, 0, n);
                    }
                }
                actual = HexFormat.of().formatHex(sha256.digest());
            } catch (Oversized past) {
                return ComplianceGate.Signature.unreadable(path, evidence.scheme().name(), evidence.location(),
                        "the artifact is larger than the " + QualityInspector.fullBodyInspectionLimit()
                                + "-byte inspection bound, so the digest its signature names was not compared here");
            }
            if (named.get().equalsIgnoreCase(actual)) {
                return verified;
            }
            return new ComplianceGate.Signature(verified.coveredPath(), verified.scheme(),
                    ComplianceGate.Signature.Outcome.INVALID, verified.signer(), verified.keyAlgorithm(),
                    verified.keyBits(), verified.hashAlgorithm(), verified.created(), verified.signerExpiry(),
                    verified.quality(), evidence.location() + ": the signed document names sha256:" + named.get()
                            + " and this artifact is sha256:" + actual, verified.expected(), verified.keySource(),
                    verified.details());
        } catch (IOException | GeneralSecurityException unreadable) {
            return ComplianceGate.Signature.unreadable(path, evidence.scheme().name(), evidence.location(),
                    unreadable.getMessage() == null ? unreadable.getClass().getSimpleName() : unreadable.getMessage());
        }
    }

    /**
     * One piece of evidence, verified by the discovered verifier for its scheme - the forty lines every scheme
     * shares, written once.
     *
     * <p>The source that holds this signature's signer, and its material - never a pooled one. Trust has several
     * independent sources (what an operator configured, a format's own provisioned keyring, continuity), each
     * answering {@code trusts} on the assumption that the signer came from ITS material. Verifying against the pool
     * and then asking any source would let a key admitted for one ecosystem vouch for another - trust widened by the
     * very composition meant to preserve it. Choosing by signer keeps identification and decision together, and the
     * scheme's probe is a lookup - a key id against a keyring, a chain against its anchors - never a read of the
     * body, so the body is still streamed exactly once, in the verification.
     */
    private ComplianceGate.Signature verify(String path, ArtifactSignatures.Evidence evidence, String ecosystem,
                                            String coordinate, long size, boolean whole) {
        String scheme = evidence.scheme().name();
        Optional<SignatureScheme> installed = SignatureScheme.installed(evidence.scheme());
        if (installed.isEmpty()) {
            // A scheme whose verifier is not installed is reported honestly rather than silently skipped: an
            // operator reading "we do not check PKCS#7 here" can act, and a missing row cannot be acted on.
            return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(),
                    "no verifier for " + evidence.scheme() + " is installed");
        }
        if (!whole) {
            // The artifact was handed over as a bounded head. Verifying against it would be an answer about bytes
            // nobody checked - a 65 MiB jar judged on its first 32 MiB - so nothing is concluded here and the
            // completion observer re-derives it from the stored artifact, streamed, off the publish thread.
            return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(), NOT_WHOLE);
        }
        SignatureScheme verifier = installed.get();
        try {
            Optional<SignatureScheme.Reading> read = verifier.read(evidence);
            if (read.isEmpty()) {
                return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(), verifier.unrecognised());
            }
            SignatureScheme.Reading reading = read.get();
            SignerTrust source = null;
            byte[] material = null;
            for (SignerTrust candidate : trust.parts()) {
                byte[] held = candidate.material(verifier.material()).orElse(null);
                if (held != null && reading.heldBy(held)) {
                    source = candidate;
                    material = held;
                    break;
                }
            }
            SignatureScheme.Verification verified;
            try {
                verified = reading.verify(bounded(evidence.signed(), size, QualityInspector.fullBodyInspectionLimit()),
                        material, Instant.now());
            } catch (Oversized past) {
                // The artifact is larger than the inspection bound, so its signature cannot be checked here. Every
                // read an inspector makes is bounded - full-body does not mean unbounded, or an attacker-shaped
                // artifact pins the publish thread - and a signature is a digest over the WHOLE artifact, so there is
                // no partial answer to give. Reported as unreadable rather than as anything else: we concluded
                // nothing, which must never be rounded down to "carries no signature". The same shape the attestation
                // inspector takes when an artifact outruns its window.
                return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(),
                        "the artifact is larger than the " + QualityInspector.fullBodyInspectionLimit()
                                + "-byte inspection bound, so its signature was not verified here");
            }
            SignerTrust admitting = verified.result() == SignatureScheme.Result.VALID
                    ? admitting(verifier, source, verified.signer(), ecosystem, coordinate)
                    : null;
            ComplianceGate.Signature.Outcome outcome = switch (verified.result()) {
                case INVALID -> ComplianceGate.Signature.Outcome.INVALID;
                // Holding the key is not the same as believing it for this coordinate. A deployment's keyring is one
                // pool, and a key admitted for one namespace must not thereby vouch for another - which is the whole
                // point of scoping trust, and the shape a takeover exploits when it is not scoped. So a signature that
                // verifies is VALID only if the trust ledger also expects this signer here; otherwise it is a
                // perfectly good signature by someone with no standing over this coordinate.
                // Asked of the source that holds the key, not of the composed whole. A null source means no material
                // held this signer, which the verifier already answered NO_KEY for; the branch is unreachable from
                // VALID and fails closed if it ever is not.
                case VALID -> admitting != null
                        ? ComplianceGate.Signature.Outcome.VALID
                        : ComplianceGate.Signature.Outcome.UNTRUSTED;
                case NO_KEY -> ComplianceGate.Signature.Outcome.UNTRUSTED;
            };
            String location = verified.reason() == null || verified.reason().isEmpty()
                    ? evidence.location()
                    : evidence.location() + ": " + verified.reason();
            return sourced(admitting != null ? admitting : source,
                    new ComplianceGate.Signature(path, scheme, outcome, verified.signer(),
                    verified.keyAlgorithm(), verified.keyBits(), verified.hashAlgorithm(), verified.created(),
                    verified.signerExpiry(), verified.quality(), location, null, null, verified.details()));
        } catch (IOException | GeneralSecurityException | RuntimeException unreadable) {
            return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(),
                    unreadable.getMessage() == null ? unreadable.getClass().getSimpleName() : unreadable.getMessage());
        }
    }

    /**
     * Raised when an artifact outruns the inspection bound, so the caller reports an unverified signature rather
     * than reading on.
     *
     * <p>An {@link IOException}, deliberately, where it used to be unchecked. Every verifier streams the covered
     * bytes through a call that declares {@code IOException} and lets one ride out, while three of them catch
     * {@code RuntimeException} around that read as their "a key this signature cannot be checked with" outcome -
     * so an unchecked bound came back from those three as INVALID, holding a well-signed apk, gem or NuGet
     * package against its own signature the moment it outgrew the bound, and from the others as "not verified
     * here". As an {@code IOException} it takes the one path every verifier already leaves open, which the scheme
     * contract's bound check holds each of them to. No stack: it is raised once per oversized artifact and the
     * caller's finding is the whole diagnosis.
     */
    private static final class Oversized extends IOException {

        Oversized() {
            super("the artifact outran the inspection bound");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * The signed bytes, capped at the full-body tier.
     *
     * <p>A signature covers the whole artifact, so a bound here is not a smaller answer - it is no answer, and the
     * caller says so. It exists because the alternative is an unbounded read on the publish thread, which is the one
     * thing every inspector in this product is held not to do.
     *
     * <p>The cap is exact: never a byte past {@code limit}, because a read that overshoots by a buffer has not
     * honoured the bound it declares. Where the artifact's size is known the two cases are told apart from it rather
     * than by probing - one of exactly the limit is read whole and verified, and only a genuinely larger one degrades.
     * Where the size is unknown a single probing byte decides, which is the one place this may read {@code limit + 1}.
     */
    private static ArtifactSignatures.Signed bounded(ArtifactSignatures.Signed signed, long size, long limit) {
        if (size >= 0 && size <= limit) {
            return signed;   // it fits: nothing to cap, and nothing to probe for
        }
        boolean knownOversized = size > limit;
        return () -> {
            InputStream in = signed.open();
            return new FilterInputStream(in) {

                private long read;

                @Override
                public int read() throws IOException {
                    if (read >= limit) {
                        throw new Oversized();
                    }
                    int one = super.read();
                    if (one != -1) {
                        read++;
                    }
                    return one;
                }

                @Override
                public int read(byte[] buffer, int offset, int count) throws IOException {
                    if (read >= limit) {
                        // The allowance is spent. A known-oversized artifact stops here without reading further;
                        // where the size was unknown, one byte decides whether anything remains.
                        if (knownOversized || super.read() != -1) {
                            throw new Oversized();
                        }
                        return -1;
                    }
                    int got = super.read(buffer, offset, (int) Math.min(count, limit - read));
                    if (got != -1) {
                        read += got;
                    }
                    return got;
                }
            };
        };
    }

    /** Every installed format that declares a signature layout - asked in full and unioned, never a first match,
     *  because two layouts may serve one ecosystem and which answers first is a property of the module path. */
    private static List<ArtifactSignatures> signatureFormats() {
        List<ArtifactSignatures> formats = new ArrayList<>();
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (installed instanceof ArtifactSignatures format) {
                formats.add(format);
            }
        }
        return formats;
    }

    /**
     * The seam's {@link ArtifactSignatures.Material} over the inspector's own bounded sibling reader - so a format
     * reads its signature material through the screen's existing bounds and reaches no storage key of its own.
     */
    private record LookupMaterial(Lookup lookup, ArtifactSignatures.Signed artifact)
            implements ArtifactSignatures.Material {

        @Override
        public Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) throws IOException {
            // The two Bounded records are the same pair of values in the store contract and the
            // compliance one, which must not depend on each other's shapes; the array is handed on rather than copied,
            // since duplicating it would double the very heap the bound protects.
            // fetchStored, not fetchBounded: a signature is material of the artifact's own publish rather than
            // evidence about the world, so the question is what is stored beside it and not what a client would be
            // served. Asking the serving question here is self-referential - a sidecar is withheld by its subject's
            // hold, so an artifact held for the want of a signature would hide the very signature that releases it -
            // and it costs two extra store reads on every publish for a sidecar that is usually absent.
            return lookup.fetchStored(path, limit)
                    .map(bounded -> new PublishInterceptor.Content.Bounded(bounded.content(), bounded.truncated()));
        }

        @Override
        public Optional<PublishInterceptor.Content.Bounded> recorded(String key, int limit) throws IOException {
            return lookup.fetchRecorded(key, limit)
                    .map(bounded -> new PublishInterceptor.Content.Bounded(bounded.content(), bounded.truncated()));
        }

        @Override
        public Optional<ArtifactSignatures.Signed> body() {
            return Optional.of(artifact);
        }
    }
}
