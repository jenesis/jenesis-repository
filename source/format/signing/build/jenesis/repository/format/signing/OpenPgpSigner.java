package build.jenesis.repository.format.signing;

import module java.base;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRing;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;

/**
 * The repository's OpenPGP signing key, and the two things a format ever does with one: sign a document detached,
 * and sign a text document inline.
 *
 * <h2>Why this is one class</h2>
 *
 * <p>It was two. {@code debian/ReleaseSigner} and {@code rpm/RpmMetadataSigner} were separate classes in separate
 * modules, and <b>130 of the RPM one's 151 lines were byte-identical to the Debian one</b> once the class name and
 * the client's name were normalised - the key loading, the generation, the expiry, the rotation window, the keyring
 * merge and the armouring, all of it. What actually differed was twenty lines: Debian additionally clearsigns, and
 * signs from a stream.
 *
 * <p>That is the shape the standing rule is about - a mechanism that is really the same moves to a helper and the
 * formats reuse it - and the cost of leaving it was about to be paid a third time, because a Terraform provider
 * registry signs its {@code SHA256SUMS} the same way. Three copies of a key-rotation policy is three places for a
 * rotation window to drift, on the one mechanism where drift means a client suddenly cannot verify anything.
 *
 * <h2>What stays with the format</h2>
 *
 * <p>Everything that names something: which document is signed, what the signature file is called, and which of the
 * two operations a client expects. apt reads a clearsigned {@code InRelease} <em>and</em> a detached
 * {@code Release.gpg}; dnf reads a detached {@code repomd.xml.asc} and has no inline form. Those are statements
 * about a protocol, and they belong in the format that speaks it - so a format calls the operation it needs rather
 * than this class knowing about repositories at all.
 *
 * <h2>The key</h2>
 *
 * <p>An unprotected RSA-3072 secret key ring, generated once by {@link #generate} and held armoured in the store.
 * It carries its own expiry, so a client stops trusting it if the repository has not rotated - an eternal signing
 * key is the smell that removes. Signatures are SHA-256, which is what both clients expect. Bouncy Castle does the
 * OpenPGP: the JDK has only the raw RSA and digest primitives.
 */
public final class OpenPgpSigner {

    private final PGPSecretKeyRing secretKeyRing;

    public OpenPgpSigner(byte[] armoredSecretKey) throws IOException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredSecretKey))) {
            this.secretKeyRing = new PGPSecretKeyRing(in, new JcaKeyFingerprintCalculator());
        } catch (PGPException e) {
            throw new IOException("Unreadable signing key", e);
        }
    }

    /** A freshly generated unprotected RSA-3072 signing key, as armored secret and public key rings. */
    public record KeyMaterial(byte[] secretKey, byte[] publicKey) {
    }

    public static KeyMaterial generate(String identity, Duration validity) throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            PGPKeyPair keyPair = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL,
                    generator.generateKeyPair(), new Date());
            // The key carries its own expiry, so a client stops trusting it once it lapses and the repository must
            // have rotated to a fresh one - an eternal signing key is the smell this removes.
            PGPSignatureSubpacketGenerator subpackets = new PGPSignatureSubpacketGenerator();
            subpackets.setKeyExpirationTime(true, validity.toSeconds());
            // What this key may be used for, stated rather than left to be inferred. A verifier looks a signature's
            // issuer up among the keys that declare they SIGN, and a key carrying no flags at all is one a strict
            // implementation declines to use - which surfaces as "signature from unknown issuer", naming the key it
            // did not consider rather than the reason. Terraform's verifier is one such; apt and dnf were not, which
            // is why a key that had worked for two ecosystems failed the third.
            subpackets.setKeyFlags(true, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
            PGPKeyRingGenerator rings = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, keyPair,
                    identity, new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1),
                    subpackets.generate(), null,
                    new JcaPGPContentSignerBuilder(keyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                    null);
            return new KeyMaterial(armor(rings.generateSecretKeyRing()), armor(rings.generatePublicKeyRing()));
        } catch (GeneralSecurityException | PGPException e) {
            throw new IOException("Failed to generate a signing key", e);
        }
    }

    /**
     * The long key id, as OpenPGP writes it: sixteen upper-case hex digits.
     *
     * <p>A protocol that has to name which key signed something names it by this - Terraform's package document
     * carries a {@code key_id} beside the armoured key - so it is the signer's to report rather than something a
     * caller derives from the armour by parsing it back.
     */
    /**
     * This signer's own public keyring, armoured - the half a client verifies with, derived from the secret half
     * rather than remembered beside it.
     *
     * <p>It exists so a format can publish the public key belonging to <em>the secret key it actually holds</em>.
     * The formats used to publish the one they generated in the same breath, which is the same thing only until two
     * first publishes race: then one pair's secret is stored and the other pair's public is served, and every
     * signature is refused by a client that is doing its job. Deriving makes the pairing structural.
     *
     * <p>It is deliberately not a replacement for the stored public keyring, which a rotation <em>merges</em> into
     * rather than overwrites - a client has to keep trusting a signature made with the key being retired. This is
     * the value that merge starts from.
     */
    public byte[] publicKeyring() throws IOException {
        List<PGPPublicKey> keys = new ArrayList<>();
        secretKeyRing.getPublicKeys().forEachRemaining(keys::add);
        return armor(new PGPPublicKeyRing(keys));
    }

    public String keyId() {
        return String.format(Locale.ROOT, "%016X", secretKeyRing.getPublicKey().getKeyID());
    }

    /** When this key expires, or {@code null} if it never does. */
    public Instant expiresAt() {
        return expiryOf(secretKeyRing.getPublicKey());
    }

    /** Whether this key has expired or is within {@code window} of expiring, so a fresh key should take over. */
    public boolean dueForRotation(Instant now, Duration window) {
        Instant expiry = expiresAt();
        return expiry != null && !now.isBefore(expiry.minus(window));
    }

    private static Instant expiryOf(PGPPublicKey key) {
        long seconds = key.getValidSeconds();
        return seconds <= 0 ? null : key.getCreationTime().toInstant().plusSeconds(seconds);
    }

    /**
     * Merge {@code added} into the {@code existing} public keyring, dropping any key that expired before
     * {@code pruneExpiredBefore}.
     *
     * <p>The served keyring therefore carries the current key and any retiring key still within its validity - a
     * client that fetched it still verifies a document signed during the rotation overlap - but not a long-dead
     * key.
     */
    public static byte[] mergePublicKeyrings(byte[] existing, byte[] added, Instant pruneExpiredBefore)
            throws IOException {
        try {
            PGPPublicKeyRingCollection collection;
            try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(existing))) {
                collection = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            }
            PGPPublicKeyRing addedRing;
            try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(added))) {
                addedRing = new PGPPublicKeyRing(in, new JcaKeyFingerprintCalculator());
            }
            if (!collection.contains(addedRing.getPublicKey().getKeyID())) {
                collection = PGPPublicKeyRingCollection.addPublicKeyRing(collection, addedRing);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
                for (PGPPublicKeyRing ring : collection) {
                    Instant expiry = expiryOf(ring.getPublicKey());
                    if (expiry == null || expiry.isAfter(pruneExpiredBefore)) {
                        ring.encode(armored);
                    }
                }
            }
            return out.toByteArray();
        } catch (PGPException e) {
            throw new IOException("Failed to merge the public keyring", e);
        }
    }

    /**
     * How a detached signature is encoded, which the client decides and not this class.
     *
     * <p>It is stated at every call site rather than defaulted, because the two are not interchangeable and a
     * client given the wrong one reports a broken signature rather than a wrong encoding. Measured against the
     * reference implementations: apt's {@code Release.gpg} and dnf's {@code repomd.xml.asc} are armoured, and
     * {@code registry.terraform.io} serves a {@code SHA256SUMS.sig} that is 566 raw bytes beginning {@code c2 c1}
     * - an OpenPGP packet header, with no {@code BEGIN PGP SIGNATURE} anywhere in it.
     */
    public enum Encoding {

        /** ASCII-armoured, wrapped in {@code BEGIN PGP SIGNATURE}. */
        ARMOURED,

        /** The raw OpenPGP packet stream. */
        BINARY
    }

    /** The detached signature over {@code content}. */
    public byte[] detachedSignature(byte[] content, Encoding encoding) throws IOException {
        return detachedSignature(new ByteArrayInputStream(content), encoding);
    }

    /** The detached signature over a streamed payload, so a large body (a multi-GB package's signed member
     *  concatenation) is digested in bounded heap rather than materialised into a {@code byte[]}. The caller owns and
     *  closes {@code content}. */
    public byte[] detachedSignature(InputStream content, Encoding encoding) throws IOException {
        try {
            PGPSignatureGenerator signature = signatureGenerator(PGPSignature.BINARY_DOCUMENT);
            byte[] buffer = new byte[8192];
            for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
                signature.update(buffer, 0, read);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (encoding == Encoding.ARMOURED) {
                try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
                    signature.generate().encode(armored);
                }
            } else {
                signature.generate().encode(out);
            }
            return out.toByteArray();
        } catch (PGPException e) {
            throw new IOException("Failed to sign the document", e);
        }
    }

    /**
     * The clearsigned form of a text document: the text inline, with the signature appended - apt's
     * {@code InRelease}.
     *
     * <p>The signature is over {@linkplain PGPSignature#CANONICAL_TEXT_DOCUMENT canonical text}, which is what makes
     * it a different operation rather than a different wrapper: trailing whitespace is stripped and line endings are
     * normalised to CRLF before digesting, so the same document signed detached and clearsigned yields different
     * signatures by design.
     */
    public byte[] clearSigned(byte[] text) throws IOException {
        try {
            PGPSignatureGenerator signature = signatureGenerator(PGPSignature.CANONICAL_TEXT_DOCUMENT);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ArmoredOutputStream armored = new ArmoredOutputStream(out);
            armored.beginClearText(HashAlgorithmTags.SHA256);
            InputStream lines = new ByteArrayInputStream(text);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int lookAhead = readLine(line, lines);
            processLine(armored, signature, line.toByteArray());
            while (lookAhead != -1) {
                lookAhead = readLine(line, lookAhead, lines);
                signature.update((byte) '\r');
                signature.update((byte) '\n');
                processLine(armored, signature, line.toByteArray());
            }
            armored.endClearText();
            signature.generate().encode(armored);
            armored.close();
            return out.toByteArray();
        } catch (PGPException e) {
            throw new IOException("Failed to clearsign the document", e);
        }
    }

    /**
     * Whether {@code signature} is a valid detached signature over {@code content} by a key in
     * {@code armouredPublicKey} - the check a client performs, in either encoding.
     *
     * <p>It lives beside the signing because it is the same key handling and the same library: a caller that had to
     * verify would otherwise take an OpenPGP dependency of its own, and in this build that is not a free choice -
     * pulling Bouncy Castle's provider into a module that already resolves the LTS one is a split package that
     * fails the boot layer, which is how this method came to exist.
     */
    public static boolean verifies(byte[] content, byte[] signature, byte[] armouredPublicKey) throws IOException {
        try {
            PGPSignatureList signatures;
            try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(signature))) {
                if (!(new PGPObjectFactory(in, new JcaKeyFingerprintCalculator()).nextObject()
                        instanceof PGPSignatureList list) || list.isEmpty()) {
                    return false;
                }
                signatures = list;
            }
            PGPPublicKeyRingCollection keys;
            try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(armouredPublicKey))) {
                keys = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            }
            PGPSignature pgp = signatures.get(0);
            PGPPublicKey key = keys.getPublicKey(pgp.getKeyID());
            if (key == null) {
                return false;   // signed by a key this ring does not carry, which is a failed verification
            }
            pgp.init(new JcaPGPContentVerifierBuilderProvider(), key);
            pgp.update(content);
            return pgp.verify();
        } catch (PGPException unreadable) {
            return false;
        }
    }

    private PGPSignatureGenerator signatureGenerator(int type) throws PGPException {
        PGPSecretKey secretKey = secretKeyRing.getSecretKey();
        PGPPrivateKey privateKey = secretKey.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().build(new char[0]));
        PGPSignatureGenerator generator = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(secretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
        generator.init(type, privateKey);
        return generator;
    }

    private static byte[] armor(PGPKeyRing ring) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
            ring.encode(armored);
        }
        return out.toByteArray();
    }

    private static void processLine(OutputStream out, PGPSignatureGenerator signature, byte[] line)
            throws IOException {
        int length = line.length;
        while (length > 0 && (line[length - 1] == ' ' || line[length - 1] == '\t'
                || line[length - 1] == '\r' || line[length - 1] == '\n')) {
            length--;
        }
        if (length > 0) {
            signature.update(line, 0, length);
        }
        out.write(line, 0, line.length);
    }

    static int readLine(ByteArrayOutputStream line, InputStream in) throws IOException {
        line.reset();
        int lookAhead = -1;
        int ch;
        while ((ch = in.read()) >= 0) {
            line.write(ch);
            if (ch == '\r' || ch == '\n') {
                lookAhead = readPastEol(line, ch, in);
                break;
            }
        }
        return lookAhead;
    }

    static int readLine(ByteArrayOutputStream line, int lookAhead, InputStream in) throws IOException {
        line.reset();
        int ch = lookAhead;
        do {
            line.write(ch);
            if (ch == '\r' || ch == '\n') {
                return readPastEol(line, ch, in);
            }
        } while ((ch = in.read()) >= 0);
        return -1;
    }

    private static int readPastEol(ByteArrayOutputStream line, int last, InputStream in) throws IOException {
        int lookAhead = in.read();
        if (last == '\r' && lookAhead == '\n') {
            line.write(lookAhead);
            lookAhead = in.read();
        }
        return lookAhead;
    }
}
