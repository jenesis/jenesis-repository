package build.jenesis.repository.upstream.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.upstream.UpstreamCredential;
import build.jenesis.repository.upstream.store.StoreUpstreamCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A credential a cloud issues rather than one an operator pastes: set by naming the scheme, minted from the node's
 * identity, and kept current by the source itself.
 */
class IssuedCredentialsTest {

    private static final String HOST = "registry.issued.test";
    private static final URI FETCH = URI.create("https://" + HOST + "/v2/app/manifests/latest");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreUpstreamCredentials credentials;

    @BeforeEach
    void source() throws IOException {
        CountingIssuer.ISSUED.set(0);
        CountingIssuer.NOW.set(Instant.parse("2026-09-24T12:00:00Z"));
        CountingIssuer.failing = false;
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // No master key: an issued credential stores no secret, so it needs none.
        credentials = new StoreUpstreamCredentials(store, Duration.ofMinutes(1), SecretCipher.of(null),
                clock());
    }

    private static Clock clock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return CountingIssuer.NOW.get();
            }
        };
    }

    @Test
    void setting_the_credential_mints_once_and_fetches_are_served_from_memory() throws IOException {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));

        assertThat(CountingIssuer.ISSUED).as("minted when set, so a refused identity is reported there").hasValue(1);
        for (int fetch = 0; fetch < 5; fetch++) {
            assertThat(credentials.headers(FETCH)).containsEntry("Authorization", "Bearer minted-1");
        }
        assertThat(CountingIssuer.ISSUED).as("and not again while it is valid").hasValue(1);
        assertThat(credentials.hosts()).containsExactly(HOST);
    }

    @Test
    void no_secret_reaches_the_store() throws IOException {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));

        String stored = new String(store.readVersioned(Scopes.space(Scopes.CONFIG) + "/upstream-auth").orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(stored).contains("@counting").doesNotContain("minted");
    }

    @Test
    void a_token_is_renewed_before_it_lapses_and_not_before() throws IOException {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));

        CountingIssuer.NOW.set(CountingIssuer.NOW.get().plus(Duration.ofMinutes(40)));
        assertThat(credentials.headers(FETCH)).containsEntry("Authorization", "Bearer minted-1");
        CountingIssuer.NOW.set(CountingIssuer.NOW.get().plus(Duration.ofMinutes(10)));
        assertThat(credentials.headers(FETCH))
                .as("inside the renewal margin a fresh token goes out, never one about to lapse")
                .containsEntry("Authorization", "Bearer minted-2");
    }

    @Test
    void another_node_mints_its_own_on_first_use_and_many_fetches_mint_once() throws Exception {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));
        StoreUpstreamCredentials other = new StoreUpstreamCredentials(store, Duration.ofMinutes(1),
                SecretCipher.of(null), clock());

        try (ExecutorService fetches = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, String>>> answers = new ArrayList<>();
            for (int fetch = 0; fetch < 32; fetch++) {
                answers.add(fetches.submit(() -> other.headers(FETCH)));
            }
            for (Future<Map<String, String>> answer : answers) {
                assertThat(answer.get()).containsEntry("Authorization", "Bearer minted-2");
            }
        }
        assertThat(CountingIssuer.ISSUED).as("one for the node that set it, one for the other").hasValue(2);
    }

    @Test
    void a_fetch_whose_token_cannot_be_issued_fails_rather_than_going_out_bare() throws IOException {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));
        CountingIssuer.NOW.set(CountingIssuer.NOW.get().plus(Duration.ofHours(2)));
        CountingIssuer.failing = true;

        assertThatThrownBy(() -> credentials.headers(FETCH)).hasMessageContaining(HOST);
    }

    @Test
    void a_host_no_installed_issuer_serves_is_refused_and_nothing_is_stored() throws IOException {
        assertThatThrownBy(() -> credentials.set("registry.example.com", new UpstreamCredential.Issued("counting")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("registry.example.com");
        assertThat(credentials.hosts()).isEmpty();
        assertThat(CountingIssuer.ISSUED).hasValue(0);
    }

    @Test
    void removing_the_credential_forgets_the_token() throws IOException {
        credentials.set(HOST, new UpstreamCredential.Issued("counting"));
        credentials.remove(HOST);

        assertThat(credentials.headers(FETCH)).isEmpty();
    }

    @Test
    void every_surface_asks_for_an_aws_credential_by_scheme_alone() {
        assertThat(UpstreamCredential.of("aws", null, null, null, null))
                .contains(new UpstreamCredential.Issued("aws"));
        assertThat(UpstreamCredential.of("bearer", null, null, "t", null))
                .contains(new UpstreamCredential.Header("Authorization", "Bearer t"));
        assertThat(UpstreamCredential.of("bearer", null, null, " ", null)).isEmpty();
    }
}
