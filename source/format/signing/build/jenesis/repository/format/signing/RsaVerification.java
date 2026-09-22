package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;

import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * The bare-signature twin of {@link OpenPgpVerification} and {@link Pkcs7Verification}: an RSA PKCS#1 v1.5 signature
 * over a digest of the covered bytes, with no envelope naming the signer - the ecosystem names the key by the file
 * the signature member is called after. An {@code .apk}'s first tar member is {@code .SIGN.RSA256.<keyfile>} (or
 * {@code .SIGN.RSA.} over SHA-1, the form {@code apk-tools} shipped for years) and the client verifies it with the key
 * it keeps as {@code /etc/apk/keys/<keyfile>}; there is nothing else to read, so {@link #facts} reads the member's
 * name and {@link #verify} needs the deployment's keys.
 *
 * <h2>Keys are a PEM bundle, named the way the ecosystem names them</h2>
 *
 * <p>The material is concatenated {@code -----BEGIN PUBLIC KEY-----} blocks, each optionally preceded by a comment
 * line {@code # <keyfile>} naming the key file. A signature naming a listed key is judged by that key alone - a
 * mismatch is {@link Result#INVALID}, since the signer claimed exactly that key - and one naming no listed key is
 * tried against the unnamed keys, where nothing verifying is {@link Result#NO_KEY}: nobody claimed it. A bundle with
 * no usable key at all answers {@code NO_KEY} whatever the signature, as an empty keyring does for OpenPGP.
 */
public final class RsaVerification {

    /** What checking one signature against the deployment's keys produced. */
    public enum Result {
        /** The signature verifies over the bytes by a key the deployment holds. */
        VALID,
        /** The signature does not verify by the key it names, or by any key when it names none the deployment holds
         *  and a named key exists - the bytes were altered or the signature was made for other content. */
        INVALID,
        /** No key the deployment holds verifies it and none is named for it: nothing could be concluded. */
        NO_KEY
    }

    /** What the signature member's name states: the key file it was made with, and the digest it was made over. */
    public record Facts(String keyFile, String hashAlgorithm) {

        /** The JCA signature algorithm this digest pairs with RSA PKCS#1 v1.5. */
        public String jca() {
            return hashAlgorithm.replace("-", "") + "withRSA";
        }
    }

    /** The result and, when a key verified the signature, that key - its size is what the grade is about. */
    public record Verdict(Result result, Optional<PublicKey> key) {
    }

    /** A key from the bundle: its file name when the bundle named it, else empty. */
    public record Key(Optional<String> name, PublicKey key) {
    }

    private static final Pattern MEMBER = Pattern.compile("^\\.SIGN\\.RSA(256|512)?\\.(.+)$");

    private RsaVerification() {
    }

    /** What a signature member's name says: {@code .SIGN.RSA.<keyfile>} is SHA-1, {@code .SIGN.RSA256.} SHA-256,
     *  {@code .SIGN.RSA512.} SHA-512; anything else is no apk signature member. */
    public static Optional<Facts> facts(String member) {
        if (member == null) {
            return Optional.empty();
        }
        String name = member.substring(member.lastIndexOf('/') + 1);
        Matcher matcher = MEMBER.matcher(name);
        if (!matcher.matches() || matcher.group(2).isBlank()) {
            return Optional.empty();
        }
        String digest = matcher.group(1) == null ? "SHA-1" : "SHA-" + matcher.group(1);
        return Optional.of(new Facts(matcher.group(2), digest));
    }

    /** Verify a signature over the covered bytes by the deployment's keys, as the class comment describes. */
    public static Verdict verify(ArtifactSignatures.Signed covered, byte[] signature, Facts facts, byte[] pemKeys)
            throws IOException {
        List<Key> keys = keys(pemKeys);
        List<Key> named = keys.stream().filter(key -> key.name().map(facts.keyFile()::equals).orElse(false)).toList();
        List<Key> candidates = named.isEmpty() ? keys.stream().filter(key -> key.name().isEmpty()).toList() : named;
        if (candidates.isEmpty()) {
            return new Verdict(Result.NO_KEY, Optional.empty());
        }
        for (Key candidate : candidates) {
            try {
                java.security.Signature verifier = java.security.Signature.getInstance(facts.jca());
                verifier.initVerify(candidate.key());
                byte[] buffer = new byte[8192];
                try (InputStream body = covered.open()) {
                    for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                        verifier.update(buffer, 0, read);
                    }
                }
                if (verifier.verify(signature)) {
                    return new Verdict(Result.VALID, Optional.of(candidate.key()));
                }
            } catch (GeneralSecurityException | RuntimeException unusable) {
                // A key this signature cannot be checked with (a different algorithm, a malformed signature) is an
                // outcome for this candidate, not a crash on a publish thread.
            }
        }
        return new Verdict(named.isEmpty() ? Result.NO_KEY : Result.INVALID, Optional.empty());
    }

    /** Whether the bundle names this signature's key, or holds any unnamed key to try: the probe an inspector runs to
     *  pick the trust source that speaks for a signer, without verifying anything. */
    public static boolean holds(Facts facts, byte[] pemKeys) {
        return keys(pemKeys).stream().anyMatch(key -> key.name().map(facts.keyFile()::equals).orElse(true));
    }

    /** The bundle's keys, in order, each named by the {@code # <keyfile>} line before it when there is one. */
    public static List<Key> keys(byte[] pemKeys) {
        if (pemKeys == null || pemKeys.length == 0) {
            return List.of();
        }
        List<Key> keys = new ArrayList<>();
        String pending = null;
        StringBuilder block = null;
        for (String line : new String(pemKeys, StandardCharsets.US_ASCII).split("\\r?\\n")) {
            String text = line.strip();
            if (block == null) {
                if (text.startsWith("#")) {
                    pending = text.substring(1).strip();
                } else if (text.equals("-----BEGIN PUBLIC KEY-----")) {
                    block = new StringBuilder();
                }
                continue;
            }
            if (text.equals("-----END PUBLIC KEY-----")) {
                try {
                    PublicKey key = KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(block.toString())));
                    keys.add(new Key(Optional.ofNullable(pending).filter(name -> !name.isEmpty()), key));
                } catch (GeneralSecurityException | IllegalArgumentException notAKey) {
                    // A block that is not an RSA public key is skipped: the bundle's other keys still count.
                }
                block = null;
                pending = null;
            } else {
                block.append(text);
            }
        }
        return keys;
    }

    /** The modulus size of an RSA key, for the grade; {@code 0} for a key that is not RSA. */
    public static int bits(PublicKey key) {
        return key instanceof RSAPublicKey rsa ? rsa.getModulus().bitLength() : 0;
    }
}
