package build.jenesis.repository.format.apk;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.StoredListing;

/**
 * The RSA key an {@code APKINDEX.tar.gz} is signed with, and the signing itself.
 *
 * <h2>The scheme, measured against a real client rather than remembered</h2>
 *
 * <p>An {@code apk} client verifies an index by reading its <b>first</b> gzip member, which is a tar carrying one
 * file named {@code .SIGN.<algorithm>.<keyfile>}. The file's content is the raw signature, {@code <keyfile>} is the
 * name of the public key in the client's {@code /etc/apk/keys/}, and what is signed is <b>every byte of the archive
 * after that member</b> - not the index text, and not the member's own tar.
 *
 * <p>Each of those was settled by measurement against Alpine 3.20's own published index and its shipped keys:
 * {@code openssl dgst -sha1 -verify <alpine key> -signature <the member's content> <the rest of the file>} answers
 * {@code Verified OK}, and the SHA-256 and SHA-512 candidates do not.
 *
 * <p><b>This signs with SHA-256, not Alpine's SHA-1.</b> {@code apk-tools} has read {@code .SIGN.RSA256.} since
 * 2.12 (Alpine 3.15, 2022); Alpine's own index is still SHA-1 for the sake of clients older than that, which is a
 * compatibility debt a repository standing up today does not inherit. A full round trip - {@code apk update} then
 * {@code apk add} - against Alpine 3.20 with a SHA-256-signed index completes with no untrusted warning.
 *
 * <h2>Why the key is generated rather than asked for</h2>
 *
 * <p>An unsigned apk repository is one a client reaches only with {@code --allow-untrusted}, which switches off the
 * verification for <em>every</em> repository that client uses. Leaving the key to an explicit provisioning step
 * would mean every repository is in that state until somebody remembers, so the first publish generates one. The
 * operator's remaining job is to fetch the public half from {@code GET /apk/<repo>/keys} and drop it into
 * {@code /etc/apk/keys/}, which no server can do for them.
 *
 * <h2>One document, because the two halves must be one pair</h2>
 *
 * <p>The private key and the public half <b>derived from it</b> are stored as one document, in one write. That is
 * the whole mechanism keeping "the key this repository serves verifies what this repository signed" true: two halves
 * written as two objects would let two first publishes racing store one pair's public half beside the other pair's
 * private half, and the served index would then not verify against the served key.
 *
 * <p>One document removes the second write; establishing it by a compare-and-set against an absent document lets
 * exactly one caller ever create the key and hands every loser the winner's. Neither alone is sufficient, because a
 * caller signs with the key it generated: without the first there are two pairs, and without the second there are
 * two keys.
 *
 * <h2>Sealed at rest</h2>
 *
 * <p>The private key is stored {@linkplain SecretCipher#sealed sealed} with the deployment's master key, as every
 * stored secret is, in a {@code java.util.Properties} document ({@code version=1}, {@code secret=} the sealed PKCS#8
 * key, {@code public=} the base64 of the public half's PEM). A deployment with no master key keeps it in the clear, since an apk repository that cannot keep its key
 * cannot sign at all; a key kept in the clear is sealed the first time a node holding a master key signs with it.
 */
final class ApkSigner {

    /** The name a client must store the public key under; it is what the signature member names. */
    static final String PUBLIC_KEY = "jenesis.rsa.pub";

    /** The tar entry the client looks for, and the algorithm it selects from the name. */
    static final String ENTRY = ".SIGN.RSA256." + PUBLIC_KEY;

    private static final String PRIVATE_KEY_PATH = "apk/keys/signing";

    private static final String VERSION = "1";

    /** The environment's cipher, parsed once, on the first signing: a malformed master key fails it loudly. */
    private static final class Environment {
        private static final SecretCipher CIPHER = SecretCipher.fromEnvironment();
    }

    private static final int KEY_SIZE = 4096;

    private final PrivateKey key;

    private ApkSigner(PrivateKey key) {
        this.key = key;
    }

    /**
     * The repository's signer, generating the key when there is none.
     *
     * <p>Only ever called from a write path (the index derivation a publish triggers), so the generation is not a
     * write on a read. A concurrent publish that established one first wins, and this signs with <em>that</em> key
     * rather than with the one it generated - which is the whole point of establishing rather than writing.
     */
    static ApkSigner of(Blobs blobs) throws IOException {
        return of(blobs.store(), Environment.CIPHER);
    }

    /** {@link #of(Blobs)} over {@code store}, sealing with {@code cipher}. */
    static ApkSigner of(ArtifactStore store, SecretCipher cipher) throws IOException {
        byte[] key = Retries.decide(store, PRIVATE_KEY_PATH, stored -> {
            if (stored.isEmpty()) {
                byte[] generated = generate();
                return Retries.Verdict.write(document(generated, cipher), generated);
            }
            String secret = secret(stored.get().content());
            byte[] opened = opened(secret, cipher);
            return !SecretCipher.isSealed(secret) && cipher.configured()
                    ? Retries.Verdict.write(document(opened, cipher), opened)
                    : Retries.Verdict.keep(opened);
        });
        return new ApkSigner(privateKey(key));
    }

    /**
     * The public key as the PEM a client drops into {@code /etc/apk/keys/}, or empty when none was generated.
     *
     * <p>Derived from the private key when the key is generated, and stored beside it in the same document. An RSA
     * private key in PKCS#8 carries the modulus and the public exponent, so the public half is a fact about the
     * private one, and one write of both is what makes the served key and the signing key the same pair by
     * construction rather than by two writes landing in the right order; it is read without opening the private key,
     * so a node that does not hold the master key still serves it.
     */
    static Optional<byte[]> publicKey(Blobs blobs) throws IOException {
        return publicKey(blobs.store(), Environment.CIPHER);
    }

    /** {@link #publicKey(Blobs)} over {@code store}, opening the key with {@code cipher}. */
    static Optional<byte[]> publicKey(ArtifactStore store, SecretCipher cipher) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(PRIVATE_KEY_PATH);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Base64.getDecoder().decode(properties(stored.get().content()).getProperty("public", "")));
    }

    /** The public half of a PKCS#8 RSA private key, as the PEM a client keeps. */
    private static byte[] publicHalf(byte[] pkcs8) throws IOException {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) privateKey(pkcs8);
        try {
            return pem(KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()))
                    .getEncoded());
        } catch (GeneralSecurityException unreadable) {
            throw new IOException("the stored apk signing key has no derivable public half", unreadable);
        }
    }

    /** The stored document holding {@code key}, sealed where {@code cipher} holds a master key, and its public half
     *  derived from it in the same write - so a node without the master key still serves the public half, and the
     *  two are one pair because they are written together. */
    private static byte[] document(byte[] key, SecretCipher cipher) throws IOException {
        return ("version=" + VERSION + "\nsecret=" + cipher.sealed(key) + "\npublic="
                + Base64.getEncoder().encodeToString(publicHalf(key)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The sealed secret a stored document carries. */
    private static String secret(byte[] document) throws IOException {
        return properties(document).getProperty("secret", "");
    }

    private static Properties properties(byte[] document) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(document), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            throw new IOException("the stored apk signing key is not a version " + VERSION + " document");
        }
        return properties;
    }

    private static byte[] opened(String secret, SecretCipher cipher) throws IOException {
        try {
            return cipher.opened(secret);
        } catch (IllegalStateException | IllegalArgumentException unopened) {
            throw new IOException("the stored apk signing key cannot be opened on this node", unopened);
        }
    }

    private static PrivateKey privateKey(byte[] pkcs8) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException unreadable) {
            throw new IOException("the stored apk signing key is not a readable RSA private key", unreadable);
        }
    }

    private static byte[] generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair().getPrivate().getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("RSA is required of every JDK", impossible);
        }
    }

    /** {@code SubjectPublicKeyInfo} as PEM, which is the shape Alpine's own {@code .rsa.pub} files carry. */
    private static byte[] pem(byte[] encoded) {
        StringBuilder text = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        String base64 = Base64.getEncoder().encodeToString(encoded);
        for (int at = 0; at < base64.length(); at += 64) {
            text.append(base64, at, Math.min(at + 64, base64.length())).append('\n');
        }
        return text.append("-----END PUBLIC KEY-----\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    byte[] sign(byte[] content) throws IOException {
        return sign(() -> new ByteArrayInputStream(content));
    }

    /** The same signature, over a body too large to hold - an {@code APKINDEX} member is the whole architecture's
     *  index, and signing it is a scan, not a thing that needs the bytes in hand. */
    byte[] sign(StoredListing.Body content) throws IOException {
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            byte[] buffer = new byte[16 * 1024];
            try (InputStream body = content.open()) {
                for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                    signature.update(buffer, 0, read);
                }
            }
            return signature.sign();
        } catch (GeneralSecurityException failed) {
            throw new IOException("the apk index could not be signed", failed);
        }
    }
}
