package build.jenesis.repository.settings;

import module java.base;

/**
 * The envelope cipher for {@link Setting.Kind#SECRET SECRET} settings: a secret written through the console or API is
 * encrypted before it reaches the store, so someone who can read the store but not the key sees only ciphertext.
 *
 * <p>JDK {@code AES/GCM/NoPadding} with a 256-bit key, a fresh 12-byte IV per encryption and a 128-bit tag, so a
 * tampered value fails rather than decrypting to garbage.
 *
 * <p>The keys come from {@value #ENV}: comma-separated {@code <key-id>:<base64(32-byte key)>} entries. The first seals
 * every new envelope and all of them decrypt, addressed by the key id an envelope carries, so a rotation prepends a
 * key, restarts, and drops the old one once nothing is sealed under it.
 *
 * <p>An envelope is {@code enc:v1:<key-id>:<base64(iv || ciphertext || tag)>}; a stored SECRET value that is not one
 * is invalid and needs re-entry, never plaintext. A malformed {@value #ENV} throws at construction, naming the
 * variable, rather than disabling encryption.
 */
public final class SecretCipher {

    /** The environment variable carrying the master key(s): comma-separated {@code <key-id>:<base64(32-byte key)>}. */
    public static final String ENV = "JENREPO_SECRETS_KEY";

    /** The versioned envelope prefix a stored ciphertext carries: {@code enc:v1:<key-id>:<base64(iv||ct||tag)>}. */
    private static final String PREFIX = "enc:v1:";

    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM standard nonce
    private static final int TAG_BITS = 128;      // GCM authentication tag

    /** The decryptors by key id, the first the active writer; empty when {@value #ENV} is unset, and a secret write is
     *  then refused. */
    private final SequencedMap<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    private SecretCipher(SequencedMap<String, SecretKey> keys) {
        this.keys = keys;
    }

    /** The cipher configured from the {@value #ENV} environment variable, or an unconfigured cipher when it is unset.
     *  A malformed value fails fast here (at boot), naming the variable. */
    public static SecretCipher fromEnvironment() {
        return of(System.getenv(ENV));
    }

    /** The cipher configured from a key specification in the shape {@value #ENV} carries; {@code null} or blank yields
     *  an unconfigured cipher, and a malformed one throws naming the variable. Lets a test inject keys without touching
     *  the environment. */
    public static SecretCipher of(String spec) {
        LinkedHashMap<String, SecretKey> parsed = new LinkedHashMap<>();
        if (spec != null && !spec.isBlank()) {
            for (String entry : spec.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf(':');
                if (separator <= 0 || separator == trimmed.length() - 1) {
                    throw malformed("entry '" + trimmed + "' is not <key-id>:<base64-32-byte-key>");
                }
                String keyId = trimmed.substring(0, separator);
                String material = trimmed.substring(separator + 1);
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(material);
                } catch (IllegalArgumentException e) {
                    throw malformed("key '" + keyId + "' is not valid base64");
                }
                if (bytes.length != KEY_BYTES) {
                    throw malformed("key '" + keyId + "' is " + bytes.length + " bytes, not " + KEY_BYTES);
                }
                if (parsed.putIfAbsent(keyId, new SecretKeySpec(bytes, "AES")) != null) {
                    throw malformed("duplicate key-id '" + keyId + "'");
                }
            }
        }
        SequencedMap<String, SecretKey> keys = new LinkedHashMap<>(parsed);
        return new SecretCipher(keys);
    }

    /** A master key specification of one freshly generated key, in the shape {@value #ENV} carries, for an operator to
     *  provision where none is. */
    public static String newKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return "k1:" + Base64.getEncoder().encodeToString(key);
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("environment variable " + ENV + " is malformed: " + detail
                + " (expected one or more comma-separated <key-id>:<base64-encoded-32-byte-key> entries)");
    }

    /** Whether at least one master key is configured - i.e. whether this deployment can persist a secret at all. */
    public boolean configured() {
        return !keys.isEmpty();
    }

    /** Whether {@code value} is an {@code enc:v1:} envelope: a prefix check, so a non-secret read never touches the
     *  cipher. */
    public static boolean isEnvelope(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Seals {@code plaintext} under the active key with a fresh IV. Throws when no key is configured; callers check
     *  {@link #configured()} and refuse the write with the remedy first. */
    public String encrypt(String plaintext) {
        if (!configured()) {
            throw new IllegalStateException("cannot encrypt a secret: " + ENV + " is not configured");
        }
        Map.Entry<String, SecretKey> active = keys.firstEntry();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, active.getValue(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
            return PREFIX + active.getKey() + ":" + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt a secret value", e);
        }
    }

    /** The prefix of a value {@link #sealed} kept in the clear for want of a master key. */
    private static final String PLAIN = "plain:";

    /**
     * {@code secret} as a stored value, for a secret the deployment generates and cannot work without, such as a
     * repository's index-signing key: sealed when a master key is configured, otherwise {@code plain:} and its base64,
     * since refusing would leave the repository unable to sign. {@link #opened} reads either; {@link #isSealed} tells a
     * reader holding a key that a plain value is still to be sealed.
     */
    public String sealed(byte[] secret) {
        String encoded = Base64.getEncoder().encodeToString(secret);
        return configured() ? encrypt(encoded) : PLAIN + encoded;
    }

    /** The secret a {@link #sealed} value holds. Fail-closed as {@link #decrypt}: an envelope no configured key opens,
     *  or a value that is neither form, throws rather than reading as an empty secret. */
    public byte[] opened(String stored) {
        if (isEnvelope(stored)) {
            return Base64.getDecoder().decode(decrypt(stored));
        }
        if (stored != null && stored.startsWith(PLAIN)) {
            return Base64.getDecoder().decode(stored.substring(PLAIN.length()));
        }
        throw new IllegalStateException("not a stored secret: neither an " + PREFIX + " envelope nor a " + PLAIN
                + " value");
    }

    /** Whether a {@link #sealed} value is an envelope rather than kept in the clear. */
    public static boolean isSealed(String stored) {
        return isEnvelope(stored);
    }

    /** Opens an envelope with the key its id names. Fail-closed: a malformed envelope, an unknown key id or a tag
     *  mismatch throws, so an undecryptable secret never reads as blank or as its ciphertext. */
    public String decrypt(String envelope) {
        if (!isEnvelope(envelope)) {
            throw new IllegalStateException("not an " + PREFIX + " secret envelope");
        }
        String body = envelope.substring(PREFIX.length());
        int separator = body.indexOf(':');
        if (separator <= 0 || separator == body.length() - 1) {
            throw new IllegalStateException("malformed secret envelope: expected enc:v1:<key-id>:<base64>");
        }
        String keyId = body.substring(0, separator);
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("no master key '" + keyId + "' is configured in " + ENV
                    + " to decrypt this secret (a rotation must keep every key still sealing a stored value)");
        }
        byte[] envelopeBytes;
        try {
            envelopeBytes = Base64.getDecoder().decode(body.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("malformed secret envelope: payload is not valid base64", e);
        }
        if (envelopeBytes.length <= IV_BYTES) {
            throw new IllegalStateException("malformed secret envelope: too short to hold an IV and ciphertext");
        }
        byte[] iv = Arrays.copyOfRange(envelopeBytes, 0, IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(envelopeBytes, IV_BYTES, envelopeBytes.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to decrypt secret sealed under master key '" + keyId
                    + "' (wrong key or tampered value)", e);
        }
    }
}
