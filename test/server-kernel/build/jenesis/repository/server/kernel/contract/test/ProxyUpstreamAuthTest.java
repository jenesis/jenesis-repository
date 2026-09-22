package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.kernel.AuthFetcher;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.upstream.store.StoreUpstreamCredentials;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upstream-credential resolver and the fetcher that applies it: a credential is stored per host and resolved for
 * any URL on that host (and only the hosts, never the secret, are listed back), and the {@link AuthFetcher} fills in
 * the {@code Authorization} header for a configured host before delegating, while leaving an unconfigured host and a
 * caller's own header untouched. This is the seam the language-format proxies use to reach a private upstream.
 */
public class ProxyUpstreamAuthTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    /** A configured master key, so a credential write encrypts at rest rather than being refused (clean cutover);
     *  injected the same way {@code SecretSettingsStorageTest} injects it, without touching the process environment. */
    private SecretCipher keyed;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) 7);
        keyed = SecretCipher.of("k1:" + Base64.getEncoder().encodeToString(material));
    }

    @Test
    void a_credential_is_stored_per_host_resolved_by_url_and_cleared() throws IOException {
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        assertThat(credentials.headers(URI.create("https://nexus.internal/repo"))).isEmpty();

        credentials.set("nexus.internal", "Authorization", "Bearer secret");
        assertThat(credentials.headers(URI.create("https://nexus.internal/a/b/c.jar")))
                .hasSize(1).containsEntry("Authorization", "Bearer secret");
        assertThat(credentials.headers(URI.create("https://public.example/x"))).as("only the configured host").isEmpty();
        assertThat(credentials.hosts()).as("the hosts are listed, not the secret").containsExactly("nexus.internal");

        credentials.set("api.host", "X-Api-Key", "k3y");
        assertThat(credentials.headers(URI.create("https://api.host/feed"))).as("an arbitrary header name")
                .containsEntry("X-Api-Key", "k3y");

        credentials.remove("nexus.internal");
        assertThat(credentials.headers(URI.create("https://nexus.internal/repo"))).isEmpty();
        assertThat(credentials.hosts()).containsExactly("api.host");
    }

    @Test
    void the_fetcher_adds_the_header_for_a_configured_host_only() throws IOException {
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        credentials.set("nexus.internal", "Authorization", "Bearer secret");
        Map<String, String> seen = new LinkedHashMap<>();
        ProxyFormat.Fetcher.Buffered delegate = (_, headers) -> {
            seen.clear();
            seen.putAll(headers);
            return Optional.of(new ProxyFormat.Fetched(200, new byte[0], Map.of()));
        };
        AuthFetcher fetcher = new AuthFetcher(delegate, credentials);

        fetcher.fetch(URI.create("https://nexus.internal/repo/a.jar"), Map.of());
        assertThat(seen).containsEntry("Authorization", "Bearer secret");

        fetcher.fetch(URI.create("https://public.example/a.jar"), Map.of());
        assertThat(seen).as("unconfigured host gets no credential").doesNotContainKey("Authorization");

        fetcher.fetch(URI.create("https://nexus.internal/a.jar"), Map.of("Authorization", "Bearer caller"));
        assertThat(seen).as("a caller's own header wins").containsEntry("Authorization", "Bearer caller");
    }

    @Test
    void a_head_is_answered_by_the_transports_own_head_with_the_credential_and_opens_no_body() throws IOException {
        // The credential wrapper overrode fetch and download but not head, so it INHERITED the buffered derivation:
        // asking a private upstream for an artifact's size opened (though never read) its body, and the real HTTP HEAD
        // of the transport below was silently discarded. A decorator may never be a Fetcher.Buffered - it delegates all
        // three legs, and the credential rides the metadata leg exactly as it rides the other two, because a private
        // upstream answers a probe only when the probe is authorized.
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        credentials.set("nexus.internal", "Authorization", "Bearer secret");
        Map<String, String> seen = new LinkedHashMap<>();
        int[] heads = {0};
        ProxyFormat.Fetcher transport = new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                throw new AssertionError("a HEAD must not be answered by buffering the body: " + url);
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                throw new AssertionError("a HEAD must not open the artifact body: " + url);
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) {
                heads[0]++;
                seen.clear();
                seen.putAll(headers);
                return Optional.of(new ProxyFormat.Head(200, Map.of("Content-Length", "1073741824")));
            }
        };
        AuthFetcher fetcher = new AuthFetcher(transport, credentials);

        assertThat(fetcher).as("a decorator over a real transport is never a Fetcher.Buffered - that type carries the "
                + "very derivations this leg must not inherit").isNotInstanceOf(ProxyFormat.Fetcher.Buffered.class);

        Optional<ProxyFormat.Head> head = fetcher.head(URI.create("https://nexus.internal/repo/a.jar"), Map.of());

        assertThat(head).as("the wrapped transport's own HEAD is what answers").isPresent();
        assertThat(head.get().header("Content-Length")).as("a gigabyte was sized without a byte being transferred")
                .isEqualTo("1073741824");
        assertThat(heads[0]).as("exactly one real HEAD went upstream").isEqualTo(1);
        assertThat(seen).as("the credential rides the metadata leg too").containsEntry("Authorization", "Bearer secret");

        fetcher.head(URI.create("https://public.example/a.jar"), Map.of());
        assertThat(seen).as("an unconfigured host gets no credential on a HEAD either")
                .doesNotContainKey("Authorization");
    }

    @Test
    void a_concurrent_credential_write_is_merged_not_silently_overwritten() throws IOException {
        RacingStore racing = new RacingStore(store);
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(racing, Duration.ofSeconds(30), keyed);

        // While this node's read-modify-write is in flight, another node commits a different host's credential first,
        // invalidating this write's version token. The deployment-global document has no lock, so correctness rests on
        // the compare-and-set retry re-reading and re-applying rather than the later write blindly overwriting - and so
        // silently dropping - the concurrent credential, which would leave that private upstream returning 401.
        credentials.set("nexus.internal", "Authorization", "Bearer secret");

        assertThat(racing.writeAttempts).as("the conflict was seen and retried, not dropped").isGreaterThanOrEqualTo(2);
        StoreUpstreamCredentials reader = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        assertThat(reader.hosts()).as("both the concurrent write and this node's landed")
                .containsExactlyInAnyOrder("racer.host", "nexus.internal");
        assertThat(reader.headers(URI.create("https://nexus.internal/x")))
                .containsEntry("Authorization", "Bearer secret");
    }

    /** A store decorator whose first {@code writeVersioned} simulates another node committing a competing credential
     *  first (so this write's token is now stale) and then reports the conflict, exercising the credential document's
     *  compare-and-set retry; every later call delegates unchanged. */
    private static final class RacingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private boolean raced;
        int writeAttempts;

        private RacingStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            writeAttempts++;
            if (!raced) {
                raced = true;
                Properties competing = new Properties();
                competing.setProperty("racer.host", "Authorization\tBearer racer");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                competing.store(bytes, null);
                Object token = delegate.readVersioned(key).map(Versioned::token).orElse(null);
                delegate.writeVersioned(key, bytes.toByteArray(), token);
                return false;                                       // this node sees the conflict and must retry
            }
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
