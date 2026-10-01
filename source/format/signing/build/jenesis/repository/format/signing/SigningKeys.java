package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The OpenPGP key a repository signs its own index with - RPM's {@code repomd.xml}, Debian's {@code Release}, a
 * Terraform registry's {@code SHA256SUMS} - and the public keyring a client verifies against, kept as one stored
 * document so every change to the pair is one compare-and-set.
 *
 * <p>A rotation changes both: a fresh secret key, and the keyring with the fresh public key merged beside the retiring
 * one. As two objects, a crash between the writes or two nodes rotating at once would leave a secret whose signatures
 * the served keyring cannot verify. As one, a rotation lands whole, and of two nodes rotating at once the second
 * re-reads the first's key, finds it not due, and signs with it.
 *
 * <h2>Sealed at rest</h2>
 *
 * <p>The secret key is {@linkplain SecretCipher#sealed sealed} with the deployment's {@link SecretCipher}, under the
 * {@value SecretCipher#ENV} master key, so the store holds only ciphertext. With no master key it is kept in the clear,
 * since a repository that cannot store its key cannot sign, and the first such write is logged; a node holding a master
 * key seals a clear key the first time it signs. An envelope this node cannot open fails the signing loudly.
 *
 * <p>The document is {@code java.util.Properties}: {@code version=1}, {@code secret=} the sealed armoured secret key
 * ring, {@code public=} the base64 of the armoured public keyring.
 */
public final class SigningKeys {

    private static final System.Logger LOGGER = System.getLogger(SigningKeys.class.getName());

    private static final String VERSION = "1";

    /** Whether this JVM has logged that it stores a signing key in the clear: said once. */
    private static final AtomicBoolean UNSEALED_REPORTED = new AtomicBoolean();

    private final ArtifactStore store;
    private final String key;
    private final String identity;
    private final Duration validity;
    private final Duration rotationWindow;
    private final SecretCipher cipher;

    /**
     * The key stored at {@code key} in {@code store}, generated for {@code identity} with {@code validity} and rotated
     * once it is within {@code rotationWindow} of expiring, sealed under the master key the environment names.
     */
    public SigningKeys(ArtifactStore store, String key, String identity, Duration validity, Duration rotationWindow) {
        this(store, key, identity, validity, rotationWindow, Environment.CIPHER);
    }

    /** As {@link #SigningKeys(ArtifactStore, String, String, Duration, Duration)}, sealed with {@code cipher}. */
    public SigningKeys(ArtifactStore store, String key, String identity, Duration validity, Duration rotationWindow,
                       SecretCipher cipher) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.validity = Objects.requireNonNull(validity, "validity");
        this.rotationWindow = Objects.requireNonNull(rotationWindow, "rotationWindow");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    /** The environment's cipher, parsed once, on the first signing: a malformed master key fails it loudly. */
    private static final class Environment {
        private static final SecretCipher CIPHER = SecretCipher.fromEnvironment();
    }

    /** The signer, rotated first when due and sealed first when stored in the clear and this node holds a master key;
     *  empty when no key is provisioned, and the repository signs nothing. */
    public Optional<OpenPgpSigner> signer() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Pair current = parse(stored.get().content());
        OpenPgpSigner signer = new OpenPgpSigner(current.secret());
        if (!signer.dueForRotation(now, rotationWindow) && (current.sealed() || !cipher.configured())) {
            return Optional.of(signer);
        }
        return Optional.of(Retries.decide(store, key, read -> {
            if (read.isEmpty()) {
                throw new IOException("the signing key at " + key + " was removed while it was being rotated");
            }
            Pair pair = parse(read.get().content());
            OpenPgpSigner standing = new OpenPgpSigner(pair.secret());
            if (standing.dueForRotation(now, rotationWindow)) {
                OpenPgpSigner.KeyMaterial fresh = OpenPgpSigner.generate(identity, validity);
                byte[] keyring = OpenPgpSigner.mergePublicKeyrings(pair.keyring(), fresh.publicKey(), now);
                return Retries.Verdict.write(render(fresh.secretKey(), keyring),
                        new OpenPgpSigner(fresh.secretKey()));
            }
            if (!pair.sealed() && cipher.configured()) {
                return Retries.Verdict.write(render(pair.secret(), pair.keyring()), standing);
            }
            return Retries.Verdict.keep(standing);
        }));
    }

    /** The signer, generating the key when none is provisioned: exactly one caller's key is stored, and every other
     *  caller signs with it. */
    public OpenPgpSigner provision() throws IOException {
        Retries.decide(store, key, read -> {
            if (read.isPresent()) {
                return Retries.Verdict.keep(null);
            }
            OpenPgpSigner.KeyMaterial fresh = OpenPgpSigner.generate(identity, validity);
            return Retries.Verdict.write(render(fresh.secretKey(), fresh.publicKey()), null);
        });
        return signer().orElseThrow(() -> new IOException("the signing key at " + key + " was not provisioned"));
    }

    /** The public keyring a client verifies with, or empty when no key is provisioned. Read without opening the secret
     *  half, so a node without the master key still serves it. */
    public Optional<byte[]> publicKeyring() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        return stored.isEmpty() ? Optional.empty()
                : Optional.of(Base64.getDecoder().decode(properties(stored.get().content()).getProperty("public", "")));
    }

    /** The long key id of the stored key, read as it stands without rotating; empty when none is provisioned. */
    public Optional<String> keyId() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        return stored.isEmpty() ? Optional.empty()
                : Optional.of(new OpenPgpSigner(parse(stored.get().content()).secret()).keyId());
    }

    /** The stored key, opened, and the keyring beside it. */
    private record Pair(byte[] secret, byte[] keyring, boolean sealed) {
    }

    private Pair parse(byte[] document) throws IOException {
        Properties properties = properties(document);
        String secret = properties.getProperty("secret", "");
        try {
            return new Pair(cipher.opened(secret),
                    Base64.getDecoder().decode(properties.getProperty("public", "")), SecretCipher.isSealed(secret));
        } catch (IllegalStateException | IllegalArgumentException unopened) {
            throw new IOException("the signing key at " + key + " cannot be opened on this node", unopened);
        }
    }

    /** The stored document, read as the version it must be. */
    private Properties properties(byte[] document) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(document), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            throw new IOException("the signing key at " + key + " is not a version " + VERSION + " document");
        }
        return properties;
    }

    /** The document holding {@code secret}, sealed where this node holds a master key, and {@code keyring}. */
    private byte[] render(byte[] secret, byte[] keyring) {
        if (!cipher.configured() && UNSEALED_REPORTED.compareAndSet(false, true)) {
            LOGGER.log(System.Logger.Level.WARNING, "A repository signing key is stored unsealed, because "
                    + SecretCipher.ENV + " names no master key; set one and the key is sealed the next time it signs");
        }
        return ("version=" + VERSION + "\nsecret=" + cipher.sealed(secret) + "\npublic="
                + Base64.getEncoder().encodeToString(keyring) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
