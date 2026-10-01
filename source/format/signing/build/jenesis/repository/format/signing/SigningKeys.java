package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The OpenPGP key a repository signs its own index with - RPM's {@code repomd.xml}, Debian's {@code Release}, a
 * Terraform registry's {@code SHA256SUMS} - and the public keyring a client verifies it against, kept as <b>one</b>
 * stored document so that every change to the pair is one compare-and-set.
 *
 * <h2>Why one document</h2>
 *
 * <p>The served keyring has to verify whatever the secret key signs, and a rotation changes both: a fresh secret key,
 * and the keyring with the fresh public key merged in beside the retiring one, which stays until it expires so a
 * client holding it still verifies what it signed during the overlap. Held as two objects, a rotation is two writes,
 * and a crash between them, or two nodes rotating at once, leaves a secret key whose signatures the served keyring
 * cannot verify. Held as one, a rotation lands whole or not at all, and of two nodes rotating at once the second
 * re-reads the first one's key, finds it no longer due, and signs with it.
 *
 * <h2>Sealed at rest</h2>
 *
 * <p>The secret key is stored {@linkplain SecretCipher#sealed sealed} with the deployment's {@link SecretCipher} -
 * the envelope the upstream credentials are sealed with, under the {@value SecretCipher#ENV} master key - so the
 * store holds only ciphertext and a reader of the store alone cannot sign as the repository. A deployment with no
 * master key keeps the key in the clear, because a repository that cannot store its key cannot sign at all, and an
 * unsigned index is one a client reaches only by switching its verification off; it says so in its log the first
 * time it does. A key stored in the clear is sealed the first time a node holding a master key signs with it. An
 * envelope this node cannot open - a master key rotated away - fails the signing loudly rather than signing with
 * nothing.
 *
 * <h2>The document</h2>
 *
 * <p>A {@code java.util.Properties} document: {@code version=1}, {@code secret=} the sealed armoured secret key ring,
 * and {@code public=} the base64 of the armoured public keyring a client is served.
 */
public final class SigningKeys {

    private static final System.Logger LOGGER = System.getLogger(SigningKeys.class.getName());

    private static final String VERSION = "1";

    /** Whether this JVM has said that it stores a signing key in the clear - said once, not once per signature. */
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

    /**
     * The signer - rotated first when it is due, and sealed first when it is stored in the clear and this node holds a
     * master key; empty when no key has been provisioned, in which case the repository signs nothing.
     */
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

    /** The signer, generating the key when none is provisioned: exactly one caller's key is ever stored, and every
     *  other caller signs with that one. */
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

    /** The public keyring a client verifies this repository's signatures with, or empty when no key is provisioned.
     *  Read without opening the secret half, so a node that does not hold the master key still serves it. */
    public Optional<byte[]> publicKeyring() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        return stored.isEmpty() ? Optional.empty()
                : Optional.of(Base64.getDecoder().decode(properties(stored.get().content()).getProperty("public", "")));
    }

    /** The long key id of the stored signing key, as OpenPGP writes it - read as it stands, rotating nothing, for a
     *  read that names the key beside the keyring it serves; empty when no key is provisioned. */
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
