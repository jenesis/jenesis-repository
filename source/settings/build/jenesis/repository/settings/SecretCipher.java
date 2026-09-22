package build.jenesis.repository.settings;

import module java.base;

/**
 * The app-layer envelope cipher for {@link Setting.Kind#SECRET SECRET} settings, sitting beside the
 * {@link SettingsSecrets} classifier so the "which keys are secret" and "how a secret is protected at rest" concerns
 * live together. A SECRET value written through the console/API is encrypted before it reaches the store, so the store
 * holds only ciphertext (the store-read threat: an attacker who can read the store but not the key); it is decrypted transparently
 * when read for use.
 *
 * <p><b>Algorithm (§8, prefer libraries).</b> JDK {@code AES/GCM/NoPadding} from {@code java.base} - a maintained,
 * native-image-safe AEAD with no added dependency (Tink would add reflection metadata for no gain at this scale). A
 * fresh {@link SecureRandom} 12-byte IV per encryption, a 256-bit key, and the 128-bit GCM tag authenticate the
 * ciphertext so a tampered value fails loud rather than decrypting to garbage.
 *
 * <p><b>Master keys and rotation.</b> The keys come from the {@value #ENV} environment variable (the §12
 * env-&gt;Spring-Boot bootstrap path, like the store credentials themselves): one or more comma-separated
 * {@code <key-id>:<base64(32-byte key)>} entries. The <em>first</em> entry is the active writer (every new envelope is
 * sealed under it); <em>all</em> entries are candidate decryptors, addressed by the {@code <key-id>} an envelope
 * carries, so a rotation prepends a new key, restarts, and later drops the old key once nothing is sealed under it.
 *
 * <p><b>Envelope format.</b> {@code enc:v1:<key-id>:<base64(iv || ciphertext || tag)>}. The {@code v1} gives algorithm
 * agility and the {@code <key-id>} gives rotation addressing; the literal {@code enc:v1:} prefix is unambiguous because
 * no legitimate secret value collides with it after the clean cutover (a stored SECRET value that is <em>not</em> an
 * envelope is treated as invalid/needs-re-entry, never as plaintext).
 *
 * <p><b>Fail-fast (§9).</b> A malformed {@value #ENV} - an entry that is not {@code <id>:<base64>}, base64 that does
 * not decode, or a key that is not 32 bytes - throws at construction (i.e. at boot), naming the environment variable,
 * rather than silently disabling encryption. {@code java.base} only, like every settings contract, so any surface can
 * hold a cipher without a heavier dependency.
 */
public final class SecretCipher {

    /** The environment variable carrying the master key(s): comma-separated {@code <key-id>:<base64(32-byte key)>}. */
    public static final String ENV = "JENREG_SECRETS_KEY";

    /** The versioned envelope prefix a stored ciphertext carries: {@code enc:v1:<key-id>:<base64(iv||ct||tag)>}. */
    private static final String PREFIX = "enc:v1:";

    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM standard nonce
    private static final int TAG_BITS = 128;      // GCM authentication tag

    /** The candidate decryptors by key-id (insertion-ordered), the first of which is the active writer; empty when the
     *  env var is unset (the deployment then persists no secret and refuses a secret write). */
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

    /** The cipher configured from a raw key specification ({@code <key-id>:<base64>[,<key-id>:<base64>]...}), the same
     *  shape {@value #ENV} carries; a {@code null}/blank spec yields an unconfigured cipher. Package-and-test entry
     *  point so a suite injects keys without mutating the process environment. A malformed spec throws, naming the
     *  environment variable (§9). */
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

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("environment variable " + ENV + " is malformed: " + detail
                + " (expected one or more comma-separated <key-id>:<base64-encoded-32-byte-key> entries)");
    }

    /** Whether at least one master key is configured - i.e. whether this deployment can persist a secret at all. */
    public boolean configured() {
        return !keys.isEmpty();
    }

    /** Whether {@code value} is an {@code enc:v1:} envelope this cipher produces - a cheap prefix check, so a
     *  non-secret read never touches the cipher. */
    public static boolean isEnvelope(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Seal {@code plaintext} under the active (first) master key into an {@code enc:v1:<key-id>:<base64>} envelope.
     *  A fresh random IV per call. Throws when no key is configured - callers must gate on {@link #configured()} and
     *  refuse a secret write with the operator remedy first (§9). */
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

    /** Open an {@code enc:v1:<key-id>:<base64>} envelope with the candidate key its {@code <key-id>} names. Fail-closed
     *  (§9): a malformed envelope, a key-id no configured key matches (wrong/absent key, e.g. after a botched rotation),
     *  or a GCM tag mismatch (tamper) throws - a secret that cannot be decrypted must never read as blank or as its
     *  ciphertext. */
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
