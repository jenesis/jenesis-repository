package build.jenesis.repository.upstream.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.upstream.UpstreamCredentialSource;

/**
 * The store-backed {@link UpstreamCredentialSource}: each credential is the ready-to-send header value, keyed by
 * the upstream host, stored in its own object ({@code config/upstream-auth}) rather than in {@code config/settings}
 * - so a secret never appears in the readable settings catalogue; the management surface lists only which hosts
 * have a credential, never the credential itself. {@link #headers} is read on every proxied fetch, so it serves an
 * in-memory snapshot, reloaded lazily once its refresh window (default thirty seconds) lapses - one store read per
 * window, taken on a fetch that is already doing network I/O - and eagerly on a write through this node.
 *
 * <p><b>At-rest encryption (clean cutover).</b> An upstream credential is the ready-to-send {@code Authorization}
 * header value - a live secret - so, like a SECRET setting, the stored value is
 * envelope-encrypted with the shared {@link SecretCipher} (AES-256-GCM, {@value SecretCipher#ENV} master key) before
 * it reaches {@code config/upstream-auth}: only the {@code enc:v1:} ciphertext is ever persisted (the header
 * <em>name</em> is not a secret and stays in the clear beside it). A write with no master key configured is refused
 * (§9), so plaintext never reaches the store; a read on the proxy-fetch path decrypts and, failing closed (§9), throws
 * rather than send a value it could not decrypt - a stored value that is not an {@code enc:v1:} envelope (a legacy
 * plaintext credential) is invalid and must be re-entered, never sent as the literal header.
 */
public final class StoreUpstreamCredentials implements UpstreamCredentialSource {

    /** The single deployment-global document, under the superadmin {@code config/} root; declared shared in the
     *  module's {@link UpstreamCredentialsStorageNamespace storage manifest}. */
    static final String PATH = Scopes.space(Scopes.CONFIG) + "/upstream-auth";

    private final ArtifactStore root;
    private final long refreshNanos;
    /** The envelope cipher protecting the stored header value: a write encrypts (or is refused when no key is
     *  configured), a proxy-fetch read decrypts - so only {@code enc:v1:} ciphertext ever reaches the store. */
    private final SecretCipher cipher;
    private volatile Properties snapshot;
    private volatile long freshUntil;

    public StoreUpstreamCredentials(ArtifactStore root, Duration refresh) throws IOException {
        this(root, refresh, SecretCipher.fromEnvironment());
    }

    /** The store-backed source with an explicit cipher - the seam a test injects a fixed master key (or an
     *  unconfigured cipher) through without mutating the process environment. Production takes it too, its provider
     *  building the cipher from the {@code secrets-key} configuration key ({@value SecretCipher#ENV}). */
    public StoreUpstreamCredentials(ArtifactStore root, Duration refresh, SecretCipher cipher) throws IOException {
        this.root = root;
        this.refreshNanos = refresh.toNanos();
        this.cipher = cipher;
        this.snapshot = load();
        this.freshUntil = System.nanoTime() + refreshNanos;
    }

    @Override
    public Map<String, String> headers(URI url) {
        String host = url.getHost();
        String stored = host == null ? null : current().getProperty(host);
        if (stored == null) {
            return Map.of();
        }
        int tab = stored.indexOf('\t');
        String name = tab < 0 ? "Authorization" : stored.substring(0, tab);
        String value = tab < 0 ? stored : stored.substring(tab + 1);
        return Map.of(name, decrypted(value));
    }

    /** The usable header value of a stored credential: decrypted when it is an {@code enc:v1:} envelope (fail-closed
     *  through {@link SecretCipher#decrypt} - a wrong/absent key or tampered value throws rather than reading back the
     *  ciphertext); and refused when it is not an envelope, because a legacy/tampered plaintext credential is invalid
     *  and must be re-entered - it is never sent as a plaintext header (§9, clean cutover). Throwing here aborts the
     *  proxied fetch, so no upstream call is ever made with a credential this node could not decrypt. */
    private String decrypted(String value) {
        if (SecretCipher.isEnvelope(value)) {
            return cipher.decrypt(value);
        }
        throw new IllegalStateException("stored upstream credential is not an " + SecretCipher.ENV
                + " envelope (enc:v1:...); it is invalid and must be re-entered - it will not be sent as a plaintext"
                + " header");
    }

    @Override
    public SortedSet<String> hosts() {
        return new TreeSet<>(current().stringPropertyNames());
    }

    @Override
    public void set(String host, String name, String value) throws IOException {
        // Encrypt the credential before it reaches the store, refusing the write (§9) when no master key is
        // configured so plaintext is never persisted (the header name is not secret and stays in the clear).
        if (!cipher.configured()) {
            throw new IllegalStateException("setting an upstream credential for '" + host + "' requires "
                    + SecretCipher.ENV + " to be configured so the value can be encrypted at rest; set it (a "
                    + "<key-id>:<base64-32-byte-key> entry) - the plaintext credential was not stored");
        }
        String sealed = cipher.encrypt(value);
        snapshot = mutate(properties -> properties.setProperty(host, name + "\t" + sealed));
        freshUntil = System.nanoTime() + refreshNanos;
    }

    @Override
    public void remove(String host) throws IOException {
        snapshot = mutate(properties -> properties.remove(host));
        freshUntil = System.nanoTime() + refreshNanos;
    }

    /** The snapshot, reloaded when the refresh window has lapsed so a write on another node is picked up; on a
     *  failing reload the last good snapshot stands (a proxied fetch must not fail on a credential refresh). */
    private Properties current() {
        if (System.nanoTime() - freshUntil > 0) {
            freshUntil = System.nanoTime() + refreshNanos;
            try {
                snapshot = load();
            } catch (IOException _) {
                // keep serving the last good snapshot
            }
        }
        return snapshot;
    }

    private Properties load() throws IOException {
        Optional<ArtifactStore.Versioned> object = root.readVersioned(PATH);
        Properties properties = new Properties();
        if (object.isPresent()) {
            properties.load(new ByteArrayInputStream(object.get().content()));
        }
        return properties;
    }

    /** Apply a mutation to the deployment-global credential document under compare-and-set ({@link Retries#update}):
     *  the document is read with its version token, changed, and written only if the token still matches, re-read
     *  and re-applied on a conflict. Two nodes editing different hosts concurrently therefore merge rather than the
     *  later write blindly overwriting - and so silently dropping - the earlier one, which would leave a private
     *  upstream returning {@code 401}. Returns the document as written, to refresh this node's snapshot. */
    private Properties mutate(Change change) throws IOException {
        Properties[] written = new Properties[1];
        Retries.update(root, PATH, object -> {
            Properties properties = new Properties();
            if (object.isPresent()) {
                properties.load(new ByteArrayInputStream(object.get().content()));
            }
            change.apply(properties);
            written[0] = properties;
            return Documents.bytes(properties);
        });
        return written[0];
    }

    /** A single edit to the credential document, replayed by {@link #mutate} on a compare-and-set conflict. */
    private interface Change {
        void apply(Properties properties);
    }
}
