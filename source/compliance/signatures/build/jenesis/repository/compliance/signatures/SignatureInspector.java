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
 * The one inspector that verifies inbound publisher signatures, for every format that has them. A format declares
 * through {@code ArtifactSignatures} where its signature material sits and what it covers; parsing, checking against
 * the deployment's keys, grading and naming the signer are the same for a Maven {@code .asc} as for a {@code .deb}'s
 * {@code _gpgorigin}, and are written once here.
 *
 * <p>Trust arrives by overlay: the screen holds the tenant's key material and overlays a {@link SignerTrust} through
 * {@link TrustAware}. Built without one, every signature reads
 * {@linkplain ComplianceGate.Signature.Outcome#UNTRUSTED untrusted}, the fail-closed direction.
 *
 * <p>Each scheme's verifier is a {@link SignatureScheme} discovered through the compliance SPI, so this class imports no
 * cryptographic library. It owns what every scheme shares: the body bound, the choice of the trust source holding the
 * signer, the trust decision, the covering-document comparison. Evidence of a scheme no installed module verifies is
 * reported as such, never skipped.
 *
 * <p>It reaches no verdict: it records what it found, and the signature dimension turns that into a finding under the
 * operator's dials, so the facts show while enforcement is off.
 */
public final class SignatureInspector implements QualityInspector, TrustAware {

    /**
     * Why a signature was not checked where the artifact could not be seen whole, which the completeness report keys
     * off to tell "did not finish looking" from "found nothing".
     */
    private static final String NOT_WHOLE = "the artifact was not available whole at this inspection, and a signature "
            + "covers the whole artifact - it is verified off the publish thread instead";

    /**
     * That reason, through a method, since javac would inline the constant into a reader compiled against an older
     * value.
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
     * Whether any installed format expects this path to be signed, or says it is material covering something that is;
     * every format is asked and the answers unioned.
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
     * Claims a path a format places on a coordinate, and otherwise only one with material stored beside it, found by one
     * point read per declared convention, never the body; a raw file with nothing beside it stays unclaimed.
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
                    // The evidence is in the artifact's own bytes, whose head every claiming inspector shares.
                    return true;
                }
                undescribed.add(format);
            }
        }
        // A body that is never opened: evidence() answers from the sibling reads alone.
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
     * A published piece of signature material completes the artifact it covers, which a multi-request deploy screened
     * before it arrived: the version directory whose held neighbours the screen re-assesses on their stored bytes.
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
        // The head comes without a truncation flag; an array under the prefix tier is provably whole.
        boolean whole = content.length < QualityInspector.prefixInspectionLimit();
        return subjects(path, () -> new ByteArrayInputStream(content), lookup, content.length, whole);
    }

    /** Streams, since a signature covers the whole body and some schemes' material sits at its end (a zip's
     *  directory). */
    @Override
    public boolean streams() {
        return true;
    }

    /**
     * The full-body tier: a reopenable stream fed through a digest, so a large package is checked without being in heap.
     * Complete unless a read bound was hit, which is reported rather than read as "declares nothing".
     */
    @Override
    public Inspection inspectArtifact(String path, Content body, Lookup lookup) throws IOException {
        Inspected inspected = inspected(path, body::open, lookup, body.size(), true);
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
        // Without a descriptor the subject falls back to the request path rather than being dropped.
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
        // Continuity is asked only of a verified signature, the one outcome a history can contradict, from the composed
        // trust so a pin outranks what was learned.
        if (found.stream().anyMatch(ComplianceGate.Signature::trusted) && subject.coordinate() != null) {
            // Once per scheme: two schemes have independent histories.
            Map<String, Optional<SignerTrust.Expectation>> expectations = new HashMap<>();
            found.replaceAll(signature -> signature.signer() == null ? signature
                    : expectations.computeIfAbsent(signature.signer().scheme(),
                            scheme -> trust.expected(subject.ecosystem(), subject.coordinate(), scheme))
                    .map(signature::expecting).orElse(signature));
        }
        return new Inspected(List.of(subject.withSignatures(found)), complete);
    }

    /** Whether a read bound rather than the artifact produced this outcome. */
    private static boolean cutShort(ComplianceGate.Signature signature) {
        return signature.outcome() == ComplianceGate.Signature.Outcome.UNREADABLE
                && signature.location() != null
                && (signature.location().endsWith(NOT_WHOLE) || signature.location().contains("inspection bound"));
    }

    /**
     * The coordinate a format places a request path at, through either layout family: the {@code publish/} namespace or
     * a blobs namespace of its own.
     */
    private static Optional<ArtifactDescriptor> describe(RepositoryFormat format, String path) {
        if (format instanceof ArtifactLayout layout) {
            return layout.describe(path);
        }
        if (format instanceof BlobLayout layout) {
            return layout.describe(path);
        }
        // A format whose mapping lives in a separate layout (OCI) is asked through it, matched by ecosystem.
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
            // Material present but unreadable is reported as such, never as absent.
            return expectations.stream()
                    .map(expected -> ComplianceGate.Signature.unreadable(path, expected.scheme().name(), path,
                            unreadable.getMessage() == null ? "unreadable" : unreadable.getMessage()))
                    .toList();
        }
        // Absence is judged per expected scheme, whatever else the artifact carries; a coverage that depends on trust
        // is read here, where the trust is in hand.
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
     * The trust source that admits this signer for this coordinate, or {@code null}. Where the material names the
     * signer, only its holder is asked, so a key admitted for one namespace cannot vouch for another; where it
     * certifies anyone (a Sigstore root), every source of the composed trust is asked in order, a pin first.
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
     * Compares the digest a verified covering document names with the artifact's own: equal keeps the verdict, a
     * different one is INVALID (a signature made for another artifact), and none is unreadable material. The document is
     * read whole under the signature bound, the artifact hashed under the inspection bound.
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
     * One piece of evidence, verified by its scheme's discovered verifier against the material of the one source holding
     * its signer, never a pool, so a key admitted for one ecosystem cannot vouch for another. The scheme's probe is a
     * lookup, so the body is streamed once, in the verification.
     */
    private ComplianceGate.Signature verify(String path, ArtifactSignatures.Evidence evidence, String ecosystem,
                                            String coordinate, long size, boolean whole) {
        String scheme = evidence.scheme().name();
        Optional<SignatureScheme> installed = SignatureScheme.installed(evidence.scheme());
        if (installed.isEmpty()) {
            // Reported rather than skipped, so an operator can act on it.
            return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(),
                    "no verifier for " + evidence.scheme() + " is installed");
        }
        if (!whole) {
            // Only a bounded head was handed over; the completion observer verifies the stored artifact later.
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
                // Past the inspection bound there is no partial answer, so nothing is concluded, never "unsigned".
                return ComplianceGate.Signature.unreadable(path, scheme, evidence.location(),
                        "the artifact is larger than the " + QualityInspector.fullBodyInspectionLimit()
                                + "-byte inspection bound, so its signature was not verified here");
            }
            SignerTrust admitting = verified.result() == SignatureScheme.Result.VALID
                    ? admitting(verifier, source, verified.signer(), ecosystem, coordinate)
                    : null;
            ComplianceGate.Signature.Outcome outcome = switch (verified.result()) {
                case INVALID -> ComplianceGate.Signature.Outcome.INVALID;
                // VALID only where the holding source admits this signer here; a null source cannot reach VALID and
                // fails closed.
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
     * Raised when an artifact outruns the inspection bound, so the caller reports the signature unverified. An
     * {@link IOException}, which every verifier lets through, where some read a runtime exception as an invalid
     * signature. Stackless.
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
     * The signed bytes capped exactly at {@code limit}, past which {@link Oversized} is raised. A known size decides
     * whether the artifact fits; an unknown one is decided by one probing byte, the only read past the limit.
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
                        // The allowance is spent; an unknown size is decided by one more byte.
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

    /** Every installed format that declares a signature layout. */
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
     * {@link ArtifactSignatures.Material} over the inspector's bounded sibling reader, so a format reads its material
     * within the screen's bounds.
     */
    private record LookupMaterial(Lookup lookup, ArtifactSignatures.Signed artifact)
            implements ArtifactSignatures.Material {

        @Override
        public Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) throws IOException {
            // What is stored beside the artifact, not what would be served: a sidecar is withheld by its subject's
            // hold, which would hide the signature that releases it. The array is handed on, not copied.
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
