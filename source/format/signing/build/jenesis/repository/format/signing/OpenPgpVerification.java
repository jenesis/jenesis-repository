package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.KeyIdentifier;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

/**
 * The consumer's half of OpenPGP: reading what a detached signature states about itself, and checking it against a
 * keyring over a streamed body. Beside {@link OpenPgpSigner}, since the library may live in one module only.
 *
 * <p>{@link #facts} needs no key: algorithm, digest, time and issuer live in the packet, so an artifact can be
 * described and graded before anyone decides whether the key is trusted. {@link #verify} needs the key and streams the
 * bytes in bounded chunks, so a multi-gigabyte package is checked on a publish thread.
 */
public final class OpenPgpVerification {

    private OpenPgpVerification() {
    }

    /** What checking one signature against a keyring produced. */
    public enum Result {

        /** The signature verifies over the bytes, by a key the keyring carries. */
        VALID,

        /** The signature does not verify over the bytes - tampered, corrupt, or made for different content. */
        INVALID,

        /** The keyring carries no key with the signature's issuer, so nothing could be checked. */
        NO_KEY
    }

    /**
     * What a signature states about itself, read with no key.
     *
     * @param fingerprint the issuer's fingerprint as upper-case hex, or {@code null} when the packet carries only a key
     *     id, which a v4 signature may
     * @param keyId the issuer's 64-bit key id, as sixteen upper-case hex digits
     */
    public record Facts(String fingerprint, String keyId, String keyAlgorithm, String hashAlgorithm,
                        Instant created) {

        /** The most specific issuer spelling available - the fingerprint where the signature carried one. */
        public String issuer() {
            return fingerprint != null ? fingerprint : keyId;
        }
    }

    /** What the signing key states, the half of a grade about the key rather than the signature. */
    public record KeyFacts(int bits, Instant expiry, boolean signingCapable) {
    }

    /**
     * The facts the first signature in {@code signature} states, or empty when the bytes are no OpenPGP signature.
     *
     * @throws IOException never for material that is merely not a signature, but the decoder may raise on a stream it
     *     cannot read at all
     */
    public static Optional<Facts> facts(byte[] signature) throws IOException {
        return first(signature).map(pgp -> {
            PGPSignatureSubpacketVector hashed = pgp.getHashedSubPackets();
            String fingerprint = null;
            if (hashed != null && hashed.getIssuerFingerprint() != null) {
                fingerprint = hex(hashed.getIssuerFingerprint().getFingerprint());
            } else {
                for (KeyIdentifier identifier : pgp.getKeyIdentifiers()) {
                    if (identifier.getFingerprint() != null && identifier.getFingerprint().length > 0) {
                        fingerprint = hex(identifier.getFingerprint());
                        break;
                    }
                }
            }
            return new Facts(fingerprint, String.format(Locale.ROOT, "%016X", pgp.getKeyID()),
                    keyAlgorithm(pgp.getKeyAlgorithm()), hashAlgorithm(pgp.getHashAlgorithm()),
                    pgp.getCreationTime() == null ? null : pgp.getCreationTime().toInstant());
        });
    }

    /** Check the detached {@code signature} over the bytes {@code signed} opens, against the keys
     *  {@code armouredKeyring} carries. The body is read in bounded chunks, opened and closed here. */
    public static Result verify(ArtifactSignatures.Signed signed, byte[] signature, byte[] armouredKeyring)
            throws IOException {
        Optional<PGPSignature> parsed = first(signature);
        if (parsed.isEmpty()) {
            return Result.INVALID;
        }
        PGPSignature pgp = parsed.get();
        Optional<PGPPublicKey> key = key(pgp.getKeyID(), armouredKeyring);
        if (key.isEmpty()) {
            return Result.NO_KEY;
        }
        try {
            pgp.init(new JcaPGPContentVerifierBuilderProvider(), key.get());
            byte[] buffer = new byte[8192];
            try (InputStream body = signed.open()) {
                for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                    pgp.update(buffer, 0, read);
                }
            }
            return pgp.verify() ? Result.VALID : Result.INVALID;
        } catch (PGPException unusable) {
            // A key that cannot check this signature (a mismatched algorithm, an unsupported curve) is an outcome, not
            // a 500.
            return Result.INVALID;
        }
    }

    /** What the key that issued this signature states about itself, from a keyring that carries it. */
    public static Optional<KeyFacts> keyFacts(String keyId, byte[] armouredKeyring) throws IOException {
        return key(Long.parseUnsignedLong(keyId, 16), armouredKeyring).map(key -> {
            long seconds = key.getValidSeconds();
            return new KeyFacts(key.getBitStrength(),
                    seconds <= 0 ? null : key.getCreationTime().toInstant().plusSeconds(seconds),
                    signingCapable(key));
        });
    }

    /** The fingerprint of the key with this id in the keyring, upper-case hex: what a signature carrying only a key id
     *  resolves to. */
    public static Optional<String> fingerprint(String keyId, byte[] armouredKeyring) throws IOException {
        return key(Long.parseUnsignedLong(keyId, 16), armouredKeyring).map(key -> hex(key.getFingerprint()));
    }

    /** The armour header a clearsigned document opens with; the detached shapes open with a signature block. */
    private static final byte[] CLEARSIGNED_HEADER = "-----BEGIN PGP SIGNED MESSAGE-----".getBytes(StandardCharsets.US_ASCII);

    /** Whether the bytes are an OpenPGP clearsigned document rather than a detached signature. */
    public static boolean isClearsigned(byte[] document) {
        if (document == null || document.length < CLEARSIGNED_HEADER.length) {
            return false;
        }
        int start = 0;
        while (start < document.length && (document[start] == ' ' || document[start] == '\r' || document[start] == '\n'
                || document[start] == '\t')) {
            start++;
        }
        return Arrays.equals(document, start, Math.min(document.length, start + CLEARSIGNED_HEADER.length),
                CLEARSIGNED_HEADER, 0, CLEARSIGNED_HEADER.length);
    }

    /** The text a clearsigned document signs, dash-unescaped, each line's trailing whitespace dropped as the canonical
     *  form has it, joined by {@code \n}; empty for no clearsigned document. Whether anyone signed it is
     *  {@link #verifyClearsigned}'s answer. */
    public static Optional<byte[]> cleartext(byte[] document) throws IOException {
        Optional<Parsed> parsed = parseClearsigned(document);
        return parsed.map(Parsed::cleartext);
    }

    /** Verify a clearsigned document against a keyring, over the canonical form of its text. {@link Result#INVALID} for
     *  bytes that are no clearsigned document, as {@link #verify} answers for no signature. */
    public static Result verifyClearsigned(byte[] document, byte[] armouredKeyring) throws IOException {
        Optional<Parsed> parsed = parseClearsigned(document);
        if (parsed.isEmpty()) {
            return Result.INVALID;
        }
        PGPSignature pgp = parsed.get().signature();
        Optional<PGPPublicKey> key = key(pgp.getKeyID(), armouredKeyring);
        if (key.isEmpty()) {
            return Result.NO_KEY;
        }
        try {
            pgp.init(new JcaPGPContentVerifierBuilderProvider(), key.get());
            byte[][] lines = parsed.get().lines();
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) {
                    pgp.update((byte) '\r');
                    pgp.update((byte) '\n');
                }
                pgp.update(lines[index]);
            }
            return pgp.verify() ? Result.VALID : Result.INVALID;
        } catch (PGPException unusable) {
            return Result.INVALID;
        }
    }

    /** Whether a Helm provenance statement names the covered bytes: the cleartext carries {@code Chart.yaml} and a
     *  {@code files:} map of {@code <chart>.tgz: sha256:<hex>}, which {@code helm verify} checks, so the digest is
     *  streamed over the covered bytes and compared. A cleartext naming no digest names nothing. */
    public static boolean provenanceNames(byte[] cleartext, ArtifactSignatures.Signed covered) throws IOException {
        Matcher digest = PROVENANCE_DIGEST.matcher(new String(cleartext, StandardCharsets.UTF_8));
        if (!digest.find()) {
            return false;
        }
        byte[] declared = HexFormat.of().parseHex(digest.group(1).toLowerCase(Locale.ROOT));
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[8192];
        try (InputStream body = covered.open()) {
            for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                sha256.update(buffer, 0, read);
            }
        }
        return MessageDigest.isEqual(declared, sha256.digest());
    }

    private static final Pattern PROVENANCE_DIGEST =
            Pattern.compile("(?m)^\\s*[^\\s:]+\\.tgz:\\s*sha256:([0-9a-fA-F]{64})\\s*$");

    /** A clearsigned document taken apart: its canonical lines, the same joined with {@code \n}, and its signature. */
    private record Parsed(byte[][] lines, byte[] cleartext, PGPSignature signature) {
    }

    private static Optional<Parsed> parseClearsigned(byte[] document) throws IOException {
        if (!isClearsigned(document)) {
            return Optional.empty();
        }
        try {
            ArmoredInputStream armoured = new ArmoredInputStream(new ByteArrayInputStream(document));
            List<byte[]> lines = new ArrayList<>();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int lookAhead = OpenPgpSigner.readLine(line, armoured);
            if (lookAhead != -1 && armoured.isClearText()) {
                lines.add(canonical(line.toByteArray()));
                while (lookAhead != -1 && armoured.isClearText()) {
                    lookAhead = OpenPgpSigner.readLine(line, lookAhead, armoured);
                    lines.add(canonical(line.toByteArray()));
                }
            } else if (lookAhead != -1) {
                lines.add(canonical(line.toByteArray()));
            }
            // The armour reader repeats the last line when the signature block follows it directly; the text ends at
            // the last line carrying characters.
            while (!lines.isEmpty() && lines.getLast().length == 0) {
                lines.removeLast();
            }
            Object next = new PGPObjectFactory(armoured, new JcaKeyFingerprintCalculator()).nextObject();
            if (!(next instanceof PGPSignatureList list) || list.isEmpty()) {
                return Optional.empty();
            }
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            for (int index = 0; index < lines.size(); index++) {
                if (index > 0) {
                    joined.write('\n');
                }
                joined.write(lines.get(index));
            }
            return Optional.of(new Parsed(lines.toArray(new byte[0][]), joined.toByteArray(), list.get(0)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A line without its separator and trailing whitespace: the form a clearsign signature covers. */
    private static byte[] canonical(byte[] line) {
        int length = line.length;
        while (length > 0 && (line[length - 1] == ' ' || line[length - 1] == '\t'
                || line[length - 1] == '\r' || line[length - 1] == '\n')) {
            length--;
        }
        return Arrays.copyOf(line, length);
    }

    private static Optional<PGPSignature> first(byte[] signature) throws IOException {
        if (isClearsigned(signature)) {
            return parseClearsigned(signature).map(Parsed::signature);
        }
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(signature))) {
            Object next = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator()).nextObject();
            return next instanceof PGPSignatureList list && !list.isEmpty()
                    ? Optional.of(list.get(0))
                    : Optional.empty();
        } catch (RuntimeException unreadable) {
            // Bouncy Castle raises unchecked on some malformed packet streams, which means "not a signature we can
            // read", never a crash. Like an IOException, it is reported as unreadable, never rounded down to "carries
            // no signature".
            return Optional.empty();
        }
    }

    private static Optional<PGPPublicKey> key(long keyId, byte[] armouredKeyring) throws IOException {
        if (armouredKeyring == null || armouredKeyring.length == 0) {
            return Optional.empty();
        }
        // A bundle is several armoured blocks one after another, and the decoder stops at the first block's end, so
        // each is read on its own; a bundle without armour is one block.
        for (byte[] block : blocks(armouredKeyring)) {
            Optional<PGPPublicKey> found = keyIn(keyId, block);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static final byte[] ARMOUR = "-----BEGIN PGP PUBLIC KEY BLOCK-----".getBytes(StandardCharsets.US_ASCII);

    /** The armoured blocks of a bundle, each from its header to the next; the whole bundle when it has no armour
     *  header. */
    static List<byte[]> blocks(byte[] bundle) {
        List<Integer> starts = new ArrayList<>();
        for (int at = indexOf(bundle, 0); at >= 0; at = indexOf(bundle, at + ARMOUR.length)) {
            starts.add(at);
        }
        if (starts.size() <= 1) {
            return List.of(bundle);
        }
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : bundle.length;
            blocks.add(Arrays.copyOfRange(bundle, starts.get(i), end));
        }
        return blocks;
    }

    private static int indexOf(byte[] bundle, int from) {
        for (int at = from; at <= bundle.length - ARMOUR.length; at++) {
            if (Arrays.mismatch(bundle, at, at + ARMOUR.length, ARMOUR, 0, ARMOUR.length) < 0) {
                return at;
            }
        }
        return -1;
    }

    /** Public key material as one armoured block, whatever it arrived as: the Web Key Directory serves binary, a
     *  keyserver and GitHub armour, and the discovered bundle is armoured. Material that is no OpenPGP key raises. */
    public static byte[] armoured(byte[] material) throws IOException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(material))) {
            PGPPublicKeyRingCollection keys = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            if (keys.size() == 0) {
                throw new IOException("no OpenPGP public keys in the material");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ArmoredOutputStream armour = new ArmoredOutputStream(out)) {
                for (PGPPublicKeyRing ring : keys) {
                    ring.encode(armour);
                }
            }
            return out.toByteArray();
        } catch (PGPException | RuntimeException unreadable) {
            throw new IOException("not OpenPGP public key material: " + unreadable.getMessage(), unreadable);
        }
    }

    private static Optional<PGPPublicKey> keyIn(long keyId, byte[] armouredKeyring) throws IOException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(armouredKeyring))) {
            PGPPublicKeyRingCollection keys = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
            PGPPublicKey direct = keys.getPublicKey(keyId);
            if (direct != null) {
                return Optional.of(direct);
            }
            // A subkey signs while the primary carries the expiry and user ids, so either id answers; this fallback
            // covers a ring whose subkey binding the collection did not index.
            for (PGPPublicKeyRing ring : keys) {
                Iterator<PGPPublicKey> members = ring.getPublicKeys();
                while (members.hasNext()) {
                    PGPPublicKey member = members.next();
                    if (member.getKeyID() == keyId) {
                        return Optional.of(member);
                    }
                }
            }
            return Optional.empty();
        } catch (PGPException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Whether a key declares it may sign data. A key with no key-flags subpacket is treated as capable, as apt and dnf
     *  treat it; this reports the flag rather than deciding for a caller. */
    private static boolean signingCapable(PGPPublicKey key) {
        Iterator<PGPSignature> certifications = key.getSignatures();
        boolean sawFlags = false;
        while (certifications.hasNext()) {
            PGPSignatureSubpacketVector hashed = certifications.next().getHashedSubPackets();
            if (hashed == null || hashed.getKeyFlags() == 0) {
                continue;
            }
            sawFlags = true;
            if ((hashed.getKeyFlags() & KeyFlags.SIGN_DATA) != 0) {
                return true;
            }
        }
        return !sawFlags;
    }

    private static String keyAlgorithm(int algorithm) {
        return switch (algorithm) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN -> "RSA";
            case PublicKeyAlgorithmTags.DSA -> "DSA";
            case PublicKeyAlgorithmTags.ECDSA -> "ECDSA";
            case PublicKeyAlgorithmTags.EDDSA_LEGACY -> "EDDSA";
            case PublicKeyAlgorithmTags.Ed25519 -> "Ed25519";
            case PublicKeyAlgorithmTags.Ed448 -> "Ed448";
            default -> "algorithm-" + algorithm;
        };
    }

    private static String hashAlgorithm(int algorithm) {
        return switch (algorithm) {
            case HashAlgorithmTags.MD5 -> "MD5";
            case HashAlgorithmTags.SHA1 -> "SHA-1";
            case HashAlgorithmTags.RIPEMD160 -> "RIPEMD-160";
            case HashAlgorithmTags.SHA224 -> "SHA-224";
            case HashAlgorithmTags.SHA256 -> "SHA-256";
            case HashAlgorithmTags.SHA384 -> "SHA-384";
            case HashAlgorithmTags.SHA512 -> "SHA-512";
            case HashAlgorithmTags.SHA3_256 -> "SHA3-256";
            case HashAlgorithmTags.SHA3_512 -> "SHA3-512";
            default -> "digest-" + algorithm;
        };
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
