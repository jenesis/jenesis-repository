package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.kernel.AuthFetcher;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.upstream.store.StoreUpstreamCredentials;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The at-rest encryption of an upstream registry credential through the real {@link StoreUpstreamCredentials}
 * write/read paths (clean cutover, the same idiom as {@code SecretSettingsStorageTest} for SECRET settings): a keyed
 * write persists only an {@code enc:v1:} envelope to {@code config/upstream-auth} - never the plaintext {@code
 * Authorization} value (the proof the cipher is on the real write path, not dead code) - a proxy-fetch read decrypts it
 * back to the exact header, a write with no master key is refused rather than stored, an undecryptable stored envelope
 * (wrong/absent key) fails closed on the fetch path so no upstream call is ever made with a bogus header, a legacy
 * plaintext value is invalid and never sent as the literal header, and a rotation key still opens a credential sealed
 * under an older key.
 */
class UpstreamCredentialEncryptionTest {

    /** The single deployment-global credential document (the package-private {@code StoreUpstreamCredentials.PATH}). */
    private static final String PATH = Scopes.space(Scopes.CONFIG) + "/upstream-auth";

    @TempDir
    Path root;

    private ArtifactStore store;
    private SecretCipher keyed;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        keyed = SecretCipher.of("k1:" + key((byte) 9));
    }

    private static String key(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The raw stored bytes of the credential document, as text - what an attacker with store-read access would see.
     *  (The {@code java.util.Properties} store form escapes a {@code :}, so the envelope reads {@code enc\:v1\:...} in
     *  the raw text; the plaintext token, sealed inside the ciphertext, never appears whatever the escaping.) */
    private String storedBytes() throws IOException {
        Optional<ArtifactStore.Versioned> object = store.readVersioned(PATH);
        assertThat(object).as("the credential document was written").isPresent();
        return new String(object.get().content(), StandardCharsets.UTF_8);
    }

    /** The {@code name\tvalue} entry stored for one host, parsed back out of the credential document. */
    private String storedEntry(String host) throws IOException {
        Properties parsed = new Properties();
        parsed.load(new ByteArrayInputStream(store.readVersioned(PATH).orElseThrow().content()));
        return parsed.getProperty(host);
    }

    @Test
    void a_keyed_credential_write_stores_ciphertext_and_reads_back_the_header() throws IOException {
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        credentials.set("nexus.internal", "Authorization", "Bearer top-secret-token");

        // The proof the cipher is on the write path: the value at rest is the enc:v1: envelope, NOT the plaintext an
        // attacker with store-read access could otherwise lift (the header NAME stays in the clear, it is not secret).
        String entry = storedEntry("nexus.internal");
        assertThat(entry).startsWith("Authorization\t");
        String storedValue = entry.substring(entry.indexOf('\t') + 1);
        assertThat(SecretCipher.isEnvelope(storedValue)).as("the stored value is an enc:v1: envelope").isTrue();
        assertThat(storedBytes()).as("the plaintext token never reaches the store").doesNotContain("top-secret-token");

        // ...and a fresh source over the same store and key decrypts it transparently for the proxy fetch.
        StoreUpstreamCredentials reader = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        assertThat(reader.headers(URI.create("https://nexus.internal/a/b/c.jar")))
                .containsExactly(Map.entry("Authorization", "Bearer top-secret-token"));
    }

    @Test
    void an_arbitrary_api_key_header_is_also_encrypted_and_round_trips() throws IOException {
        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        credentials.set("api.host", "X-Api-Key", "k3y-value");

        String entry = storedEntry("api.host");
        assertThat(entry).startsWith("X-Api-Key\t");
        assertThat(SecretCipher.isEnvelope(entry.substring(entry.indexOf('\t') + 1))).isTrue();
        assertThat(storedBytes()).doesNotContain("k3y-value");
        assertThat(new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed)
                .headers(URI.create("https://api.host/feed")))
                .containsExactly(Map.entry("X-Api-Key", "k3y-value"));
    }

    @Test
    void a_credential_write_is_refused_and_persists_nothing_without_a_master_key() throws IOException {
        StoreUpstreamCredentials unkeyed = new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of(null));
        assertThatThrownBy(() -> unkeyed.set("nexus.internal", "Authorization", "Bearer would-be-plaintext"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENREG_SECRETS_KEY");

        // Nothing reached the store - no plaintext credential, no document at all.
        assertThat(store.readVersioned(PATH)).as("the refused write persisted nothing").isEmpty();
    }

    @Test
    void a_read_fails_closed_when_a_stored_envelope_cannot_be_decrypted() throws IOException {
        new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed)
                .set("nexus.internal", "Authorization", "Bearer top-secret-token");
        URI url = URI.create("https://nexus.internal/a.jar");

        // Wrong key bytes under the same id: the envelope is present but the GCM tag fails - fail closed, never the
        // ciphertext-as-header and never blank.
        StoreUpstreamCredentials wrongKey = new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of("k1:" + key((byte) 10)));
        assertThatThrownBy(() -> wrongKey.headers(url)).isInstanceOf(IllegalStateException.class);

        // Absent key entirely (deployment lost its master key): still fail closed rather than reading the ciphertext.
        StoreUpstreamCredentials noKey = new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of(null));
        assertThatThrownBy(() -> noKey.headers(url)).isInstanceOf(IllegalStateException.class);

        // ...and through the fetcher the upstream call is never made with a bogus header: the credential lookup throws
        // before the delegate fetcher is ever invoked.
        boolean[] delegated = {false};
        ProxyFormat.Fetcher.Buffered delegate = (_, _) -> {
            delegated[0] = true;
            return Optional.of(new ProxyFormat.Fetched(200, new byte[0], Map.of()));
        };
        AuthFetcher fetcher = new AuthFetcher(delegate, noKey);
        assertThatThrownBy(() -> fetcher.fetch(url, Map.of())).isInstanceOf(IllegalStateException.class);
        assertThat(delegated[0]).as("no upstream call is made with a credential that could not be decrypted").isFalse();
    }

    @Test
    void a_stored_credential_that_is_not_an_envelope_is_invalid_and_never_sent_as_plaintext() throws IOException {
        // Simulate a legacy/tampered plaintext credential planted directly in the store (bypassing the encrypting
        // write) - the pre-cutover on-disk form.
        Properties legacy = new Properties();
        legacy.setProperty("nexus.internal", "Authorization\tBearer legacy-plaintext");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        legacy.store(bytes, null);
        store.writeVersioned(PATH, bytes.toByteArray(), null);

        StoreUpstreamCredentials credentials = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed);
        assertThatThrownBy(() -> credentials.headers(URI.create("https://nexus.internal/x")))
                .as("a non-enc:v1 credential is invalid/needs re-entry, never sent as a plaintext header")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_rotation_key_still_decrypts_a_credential_sealed_under_an_older_key() throws IOException {
        new StoreUpstreamCredentials(store, Duration.ofSeconds(30), keyed)
                .set("nexus.internal", "Authorization", "Bearer top-secret-token");

        // A rotation prepends a fresh active writer (k2) but keeps the old k1 as a candidate decryptor, so a credential
        // sealed under k1 still opens by the key-id its envelope carries.
        SecretCipher rotated = SecretCipher.of("k2:" + key((byte) 11) + ",k1:" + key((byte) 9));
        StoreUpstreamCredentials reader = new StoreUpstreamCredentials(store, Duration.ofSeconds(30), rotated);
        assertThat(reader.headers(URI.create("https://nexus.internal/x")))
                .containsExactly(Map.entry("Authorization", "Bearer top-secret-token"));
    }
}
