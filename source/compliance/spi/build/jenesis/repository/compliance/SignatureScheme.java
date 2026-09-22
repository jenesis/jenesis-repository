package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * One signature scheme's verifier, discovered: how evidence of one {@link ArtifactSignatures.Scheme} is read, which
 * kind of trust material it is checked against, and what it states once checked. A format declares <em>where</em> a
 * signature sits and what it covers; a scheme says how such a signature is verified; the one signature inspector
 * joins the two and knows the internals of neither.
 *
 * <h2>Why a service and not a switch</h2>
 *
 * The inspector used to dispatch on the scheme enum with one branch per verifier, each importing its library's
 * module: five branches over Bouncy Castle and one over sigstore-java, all in the module that also owns trust, the
 * signer index and the sweeps. Every scheme added since arrived as a seventh copy of the same forty lines - read the
 * facts, pick the trust source that holds the signer, verify over the bounded body, name the signer, grade - with
 * one library call different in each, and the inspector's module acquired every verifier's dependency. As a
 * service the forty lines are written once in the inspector and a scheme contributes only its library calls, from
 * the module that already loads the library; a deployment carrying no verifier for a scheme reports evidence of it
 * as unreadable - "no verifier is installed" - rather than pretending to have checked it, exactly as before.
 *
 * <p>The scheme enum stays where it is. It is the vocabulary a <em>format</em> speaks about the shape of its
 * evidence, and a format must be able to name a scheme this deployment has no verifier for - the honest report
 * above depends on it. What moved is the verifier, not the name.
 *
 * <h2>The two steps, and why they are separate</h2>
 *
 * {@link #read} parses what the evidence states about itself with no trust in hand - the key it names, the chain it
 * carries, the identity Fulcio certified. Only then is the trust source chosen: the inspector asks every source of
 * the composed {@link SignerTrust} for this scheme's {@linkplain #material() kind of material} and takes the first
 * whose material {@linkplain Reading#heldBy holds the signer}, so the signature is verified against that source's
 * material alone and that source's pins are asked afterwards. Verifying against a pool and then asking any source
 * would let a key admitted for one ecosystem vouch for another. The probe is a lookup and never a read of the
 * covered bytes, which are streamed once, in {@link Reading#verify}.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>One scheme, one provider.</b> {@link #scheme()} is constant, and the census refuses two providers naming the
 *     same scheme: which of two verifiers answered would otherwise be a property of the module path.</li>
 * <li><b>Read purity.</b> {@link #read} and {@link Reading#heldBy} read the evidence and the material they are handed
 *     and nothing else - no store, no network, no covered bytes. Evidence that is not of this scheme at all answers
 *     empty (a malformed stream may raise, which the caller reports as unreadable material); it is never a
 *     verdict.</li>
 * <li><b>The holder is chosen by the material.</b> {@link Reading#heldBy} answers true only for material that holds
 *     the signer the reading names - the key by its id, the chain by an anchor, the certificate by a root's
 *     authority - and false for {@code null}, empty or foreign material.</li>
 * <li><b>Verification is over the covered bytes, against the material handed over.</b> {@link Reading#verify}
 *     answers {@link Result#VALID} only for a signature that checks over exactly the bytes the
 *     {@link ArtifactSignatures.Signed} opens against a key the material holds; other bytes are
 *     {@link Result#INVALID}, and no material or foreign
 *     material is {@link Result#NO_KEY}, never VALID and never a throw. Holding the key is not trusting the signer:
 *     the trust decision is the caller's, made on the identity the verification names.</li>
 * <li><b>The signer is named in the scheme's own identity vocabulary.</b> Every {@link Verification#signer()} carries
 *     {@link #material()} as its {@link SignerIdentity#scheme()}, whatever the result, so a pin written for the
 *     material names the signer, and the same signature names the same signer whether or not material was held.</li>
 * <li><b>What verified is graded.</b> A VALID verification carries the key's algorithm and size, the digest, and a
 *     {@link SignatureQuality} that was assessed - never {@link SignatureQuality#unassessed()}, which is the
 *     inspector's word for material it could not read.</li>
 * <li><b>A bound rides out.</b> An {@link IOException} the covered stream raises leaves {@link Reading#verify}
 *     unchanged - it is how the inspector's read bound reports that the artifact outran it - and is never turned into
 *     a verdict. INVALID for an artifact nobody finished reading would hold a good signature against its artifact;
 *     VALID would be a claim over bytes nobody checked.</li>
 * <li><b>The material's meaning.</b> {@link #materialNamesSigner()} says whether holding this scheme's material is
 *     itself a statement about the signer. A keyring or an anchor bundle names whom it holds, so a signature is
 *     judged by the trust source whose material verified it and by no other; a Sigstore root certifies anyone
 *     the issuers know, so a verified bundle is put to every source of the composed trust, and the one that
 *     admits it is recorded as the signature's source.</li>
 * <li><b>Thread-safety.</b> A provider keeps no per-call state on itself; a {@link Reading} is one evidence's and is
 *     used from the thread that read it.</li>
 * <li><b>Material is read by the scheme that verifies against it.</b> {@link #trustMaterial} answers the stored
 *     form of what a discovery source or a fetched document served - one armoured block for a keyring however it
 *     arrived, the bytes themselves for a trusted root this build can read - and empty for anything that is not
 *     this scheme's material; {@link #holdsKey} says whether material holds a key by the id a signature names.
 *     Both are lookups over the bytes handed over and nothing else. The defaults answer empty and false, which is
 *     what a scheme whose material is only ever pasted by an operator needs; a scheme whose material is discovered
 *     overrides them, so the inspector's discovery passes read a keyring or a root through the module that owns
 *     the library rather than importing it.</li>
 * </ol>
 */
public interface SignatureScheme {

    /** The scheme this verifier reads - the one a format names on its evidence. */
    ArtifactSignatures.Scheme scheme();

    /**
     * The kind of trust material a signature of this scheme is checked against: the key a {@link SignerTrust#material}
     * answers for, and the scheme every {@link SignerIdentity} this verifier names carries - one of the
     * {@link SignerIdentity} scheme tokens. Two schemes may share one kind: a PKCS#7 signature and a gem's bare
     * signature both chain to the deployment's X.509 anchors.
     */
    String material();

    /**
     * Read what the evidence states about itself, with no trust in hand. Empty when the material is not of this
     * scheme at all, which the caller reports with {@link #unrecognised()}; a stream the scheme's reader cannot read
     * may raise instead, which the caller reports as unreadable material. Neither is ever a verdict.
     */
    Optional<Reading> read(ArtifactSignatures.Evidence evidence) throws IOException;

    /** Why {@link #read} answered empty - what the evidence was not, in the words an operator reads on the finding. */
    String unrecognised();

    /**
     * Whether holding this scheme's material is itself a statement about the signer - true for a key or an
     * anchor an operator supplied, which names whom it admits, and false for a certificate authority's root that
     * certifies anyone its issuers know. Decides whom the caller asks whether a verified signer is trusted here:
     * the material's holder alone, or every source of the composed trust.
     */
    default boolean materialNamesSigner() {
        return true;
    }

    /**
     * The stored form of trust material of this scheme's kind that a discovery source or a fetched document served,
     * or empty when the bytes are not this scheme's material at all. A lookup over the bytes handed over, never a
     * fetch; the default, for a scheme whose material is only ever supplied by an operator, is empty.
     */
    default Optional<byte[]> trustMaterial(byte[] served) {
        return Optional.empty();
    }

    /**
     * Whether {@code material} of this scheme's kind holds the key {@code keyId} names - the id a signature of this
     * scheme carries when it names its key rather than its signer. A lookup over the material handed over, false
     * for {@code null}, empty or foreign material; the default is false.
     */
    default boolean holdsKey(String keyId, byte[] material) {
        return false;
    }

    /** A signature read but not yet checked: what it states about its signer, and the check itself. */
    interface Reading {

        /**
         * Whether this material holds the signer the signature names - the probe that picks the trust source whose
         * material the signature is then verified against, and whose pins are asked afterwards. A lookup, never a
         * read of the covered bytes, and false for {@code null} or empty material.
         */
        boolean heldBy(byte[] material) throws IOException;

        /**
         * Check the signature over the bytes {@code covered} opens, against {@code material} - the trust source's own,
         * or {@code null} where no source holds the signer, which answers {@link Result#NO_KEY} and still names the
         * signer and grades what can be graded without a key. The stream is opened here and read at most once; an
         * {@link IOException} it raises rides out unchanged.
         *
         * @param now the instant the grade judges expiry against
         */
        Verification verify(ArtifactSignatures.Signed covered, byte[] material, Instant now)
                throws IOException, GeneralSecurityException;
    }

    /** What checking a signature against material established. */
    enum Result {

        /** Verifies over the covered bytes, by a key the material holds. */
        VALID,

        /** Does not verify: other bytes, or a signature the key cannot have made. */
        INVALID,

        /** The material holds no key that could check it - none was handed over, or another signer's. */
        NO_KEY
    }

    /**
     * A signature checked: the result, the signer it names, what its key and digest are, when it was made and when
     * its signer's key expires (either {@code null} where the scheme does not say), the grade, for an INVALID
     * result the scheme's own reason where it has one, which the caller appends to the material's location, and
     * {@code details} - what else the scheme's material states that an operator reads apart, keyed by the
     * {@link ComplianceGate.Signature} detail names ({@code issuer}, {@code subject}, {@code log-index},
     * {@code integrated-time}), recorded on the version's summary and shown on its screen. Empty for a scheme
     * whose identity says everything.
     */
    record Verification(Result result, SignerIdentity signer, String keyAlgorithm, int keyBits, String hashAlgorithm,
                        Instant created, Instant signerExpiry, SignatureQuality quality, String reason,
                        Map<String, String> details) {

        public Verification {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(signer, "signer");
            Objects.requireNonNull(quality, "quality");
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        /** A verification with nothing to say beyond its identity and its key. */
        public Verification(Result result, SignerIdentity signer, String keyAlgorithm, int keyBits,
                            String hashAlgorithm, Instant created, Instant signerExpiry, SignatureQuality quality,
                            String reason) {
            this(result, signer, keyAlgorithm, keyBits, hashAlgorithm, created, signerExpiry, quality, reason, null);
        }
    }

    /** Every installed scheme, in discovery order, resolved once for the life of the class loader. */
    static List<SignatureScheme> installed() {
        return Installed.SCHEMES;
    }

    /** The installed verifier for one scheme, or empty where this deployment carries none for it. */
    static Optional<SignatureScheme> installed(ArtifactSignatures.Scheme scheme) {
        return Optional.ofNullable(Installed.BY_SCHEME.get(scheme));
    }

    /**
     * The discovered schemes, resolved once. A holder rather than a {@code ServiceLoader.load} in the method for the
     * reason {@link SignerTrustProvider} records: both screens verify per artifact, and a walk of the module graph's
     * service declarations per artifact was measured as a proxy too slow to serve a large index. First use rather
     * than class-init, so a composition that never verifies pays nothing.
     */
    final class Installed {

        private static final List<SignatureScheme> SCHEMES = load();

        private static final Map<ArtifactSignatures.Scheme, SignatureScheme> BY_SCHEME = index(SCHEMES);

        private Installed() {
        }

        private static List<SignatureScheme> load() {
            List<SignatureScheme> schemes = new ArrayList<>();
            ServiceLoader.load(SignatureScheme.class).forEach(schemes::add);
            return List.copyOf(schemes);
        }

        private static Map<ArtifactSignatures.Scheme, SignatureScheme> index(List<SignatureScheme> schemes) {
            Map<ArtifactSignatures.Scheme, SignatureScheme> index = new EnumMap<>(ArtifactSignatures.Scheme.class);
            for (SignatureScheme scheme : schemes) {
                // The first in discovery order, as a lookup must answer something; the census is what refuses two.
                index.putIfAbsent(scheme.scheme(), scheme);
            }
            return Map.copyOf(index);
        }
    }
}
