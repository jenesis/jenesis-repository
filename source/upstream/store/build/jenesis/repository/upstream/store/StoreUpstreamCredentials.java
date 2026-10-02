package build.jenesis.repository.upstream.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.upstream.UpstreamCredential;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamTokenIssuer;

/**
 * The store-backed {@link UpstreamCredentialSource}: each credential is the ready-to-send header, keyed by the upstream
 * host, in its own document ({@link #PATH}) rather than among the settings, so a secret never appears in the readable
 * settings catalogue; the management surface lists only which hosts have one. {@link #headers} is read on every
 * proxied fetch, so it serves an in-memory snapshot, reloaded once its refresh window lapses - one store read per
 * window, on a fetch already doing network I/O - and at once on a write through this node.
 *
 * <p><b>At-rest encryption.</b> The header value is a live secret, so like a SECRET setting it is envelope-encrypted
 * with the shared {@link SecretCipher} ({@value SecretCipher#ENV} master key) and only the {@code enc:v1:} ciphertext
 * is stored; the header name stays in the clear. A write with no master key is refused. A read decrypts and fails
 * closed, so a value it cannot decrypt, or one that is not an envelope, fails the fetch and must be re-entered rather
 * than being sent as the literal header.
 */
public final class StoreUpstreamCredentials implements UpstreamCredentialSource {

    /** The single deployment-global document, declared shared in {@link UpstreamCredentialsStorageNamespace}. */
    static final String PATH = Scopes.space(Scopes.CONFIG) + "/upstream-auth";

    /** The mark of an issued credential in the document: {@code @<issuer>}. A header name cannot begin with it, so a
     *  stored header is never read as one. */
    static final String ISSUED = "@";

    /** How long before its expiry a minted token is renewed, so a fetch never goes out with a token that lapses
     *  on the way. */
    static final Duration MARGIN = Duration.ofMinutes(15);

    private final ArtifactStore root;
    private final long refreshNanos;
    /** The envelope cipher protecting the stored header value: a write encrypts (or is refused when no key is
     *  configured), a proxy-fetch read decrypts - so only {@code enc:v1:} ciphertext ever reaches the store. */
    private final SecretCipher cipher;
    private final Clock clock;
    /** The tokens this node minted for issued credentials, by host. Node-local by design: a token is issued to
     *  the node's own cloud identity and never written to the store, so no secret is at rest for an issued host. */
    private final ConcurrentHashMap<String, UpstreamTokenIssuer.Token> minted = new ConcurrentHashMap<>();
    /** One lock per host, so concurrent fetches of one upstream mint its token once. */
    private final ConcurrentHashMap<String, Object> minting = new ConcurrentHashMap<>();
    private volatile Properties snapshot;
    private volatile long freshUntil;

    public StoreUpstreamCredentials(ArtifactStore root, Duration refresh) throws IOException {
        this(root, refresh, SecretCipher.fromEnvironment());
    }

    /** The store-backed source with an explicit cipher, which the provider builds from the {@code secrets-key}
     *  configuration key ({@value SecretCipher#ENV}) and a test injects without touching the environment. */
    public StoreUpstreamCredentials(ArtifactStore root, Duration refresh, SecretCipher cipher) throws IOException {
        this(root, refresh, cipher, Clock.systemUTC());
    }

    /** The store-backed source on an explicit clock - the seam a test crosses a token's expiry through. */
    public StoreUpstreamCredentials(ArtifactStore root, Duration refresh, SecretCipher cipher, Clock clock)
            throws IOException {
        this.root = root;
        this.refreshNanos = refresh.toNanos();
        this.cipher = cipher;
        this.clock = clock;
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
        if (stored.startsWith(ISSUED)) {
            UpstreamTokenIssuer.Token token = token(host, stored.substring(ISSUED.length()));
            return Map.of(token.header(), token.value());
        }
        int tab = stored.indexOf('\t');
        String name = tab < 0 ? "Authorization" : stored.substring(0, tab);
        String value = tab < 0 ? stored : stored.substring(tab + 1);
        return Map.of(name, decrypted(value));
    }

    /** The usable header value of a stored credential, decrypted through {@link SecretCipher#decrypt}. A wrong or
     *  absent key, a tampered value or one that is not an envelope throws, aborting the proxied fetch, so no upstream
     *  call goes out with a credential this node could not decrypt. */
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
        // Refused without a master key, so plaintext is never persisted.
        if (!cipher.configured()) {
            throw new IllegalStateException("setting an upstream credential for '" + host + "' requires "
                    + SecretCipher.ENV + " to be configured so the value can be encrypted at rest; set it (a "
                    + "<key-id>:<base64-32-byte-key> entry) - the plaintext credential was not stored");
        }
        String sealed = cipher.encrypt(value);
        snapshot = mutate(properties -> properties.setProperty(host, name + "\t" + sealed));
        freshUntil = System.nanoTime() + refreshNanos;
    }

    /** Store an issued credential - the issuer's name, never a secret - after minting a first token, so an identity
     *  the cloud provider refuses is reported to whoever set the credential rather than to the next fetch. A header
     *  credential is stored as {@link #set(String, String, String)} stores it. */
    @Override
    public void set(String host, UpstreamCredential credential) throws IOException {
        if (!(credential instanceof UpstreamCredential.Issued issued)) {
            UpstreamCredentialSource.super.set(host, credential);
            return;
        }
        UpstreamTokenIssuer issuer = UpstreamTokenIssuer.serving(host)
                .filter(serving -> serving.name().equals(issued.issuer()))
                .orElseThrow(() -> new IllegalArgumentException("No installed " + issued.issuer()
                        + " token issuer serves '" + host + "'."));
        minted.put(host, issuer.issue(host));
        snapshot = mutate(properties -> properties.setProperty(host, ISSUED + issuer.name()));
        freshUntil = System.nanoTime() + refreshNanos;
    }

    /**
     * The token for an issued host: the one this node holds while it has more than {@link #MARGIN} left, else a
     * fresh one from the issuer. This is the one place a fetch waits on the wire - once per token lifetime per node
     * and host, the fetch that finds the token missing or nearly expired - and the per-host lock makes concurrent
     * fetches of one upstream wait on a single issue rather than each asking for its own. An issuer that cannot
     * mint fails the fetch naming the host, rather than letting it go out without its credential.
     */
    private UpstreamTokenIssuer.Token token(String host, String issuerName) {
        UpstreamTokenIssuer.Token token = minted.get(host);
        if (usable(token)) {
            return token;
        }
        synchronized (minting.computeIfAbsent(host, _ -> new Object())) {
            token = minted.get(host);
            if (usable(token)) {
                return token;
            }
            UpstreamTokenIssuer issuer = UpstreamTokenIssuer.named(issuerName).orElseThrow(() ->
                    new IllegalStateException("The upstream credential for '" + host + "' is issued by "
                            + issuerName + ", which is not installed on this node."));
            try {
                token = issuer.issue(host);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not issue the " + issuerName + " token for '" + host + "'", e);
            }
            minted.put(host, token);
            return token;
        }
    }

    private boolean usable(UpstreamTokenIssuer.Token token) {
        return token != null && token.expires().isAfter(clock.instant().plus(MARGIN));
    }

    @Override
    public void remove(String host) throws IOException {
        minted.remove(host);
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

    /** Applies a change to the credential document under compare-and-set ({@link Retries#update}), re-applied on a
     *  conflict, so two nodes editing different hosts at once merge rather than one dropping the other's credential.
     *  Returns the document as written, to refresh this node's snapshot. */
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
