package build.jenesis.repository.store.gcs.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.gcs.GcsArtifactStoreProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the JSON API's conditional-write protocol without a network: the real API client is driven through
 * {@link ArtifactStoreProvider#resolve} against {@link JsonGcs}, a stub that stores objects with a monotonically
 * increasing generation and enforces {@code ifGenerationMatch} ({@code 0} = only if absent) with a 412. That pins
 * the wire contract the backend is written against: create-if-absent and update-if-unchanged both land once and are
 * refused once stale, the version token is the object generation and is read with the bytes in one round trip,
 * a token from a deleted incarnation is refused against the replacement, a document or a media response without
 * its generation fails fast rather than fabricating a token, and a missing bucket is an error and never a lost
 * compare-and-set. The plaintext-endpoint refusal and the missing-bucket setting round it off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GcsConditionalWriteTest {

    private WireMockServer server;
    private ArtifactStore store;
    private final JsonGcs gcs = new JsonGcs();

    @BeforeAll
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort().extensions(gcs));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        store = ArtifactStoreProvider.resolve("gcs", JsonGcs.settings(server.port(), "repo")::get).scope("acme");
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void write_versioned_is_a_create_if_absent_compare_and_set() throws IOException {
        String key = "config/create-if-absent";
        assertThat(store.readVersioned(key)).isEmpty();
        assertThat(store.writeVersioned(key, "one".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(store.writeVersioned(key, "two".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        ArtifactStore.Versioned stored = store.readVersioned(key).orElseThrow();
        assertThat(new String(stored.content(), StandardCharsets.UTF_8)).isEqualTo("one");
    }

    @Test
    public void write_versioned_is_an_update_if_unchanged_compare_and_set() throws IOException {
        String key = "config/update-if-unchanged";
        assertThat(store.writeVersioned(key, "v1".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object token = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "v2".getBytes(StandardCharsets.UTF_8), token)).isTrue();
        assertThat(store.writeVersioned(key, "v3".getBytes(StandardCharsets.UTF_8), token)).isFalse();
        assertThat(new String(store.readVersioned(key).orElseThrow().content(), StandardCharsets.UTF_8)).isEqualTo("v2");
    }

    @Test
    public void the_version_token_is_the_object_generation() throws IOException {
        String key = "config/generation";
        assertThat(store.writeVersioned(key, "a".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object first = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "b".getBytes(StandardCharsets.UTF_8), first)).isTrue();
        Object second = store.readVersioned(key).orElseThrow().token();
        assertThat(second).isNotEqualTo(first);
        assertThat(gcs.objects).containsKey("acme/" + key);
        assertThat(second).isEqualTo(Long.toString(gcs.objects.get("acme/" + key).generation()));
    }

    @Test
    public void the_version_token_is_read_without_downloading_the_body() throws IOException {
        // version() must answer the SAME token readVersioned pairs with the body, and must not fetch the body to do
        // it: a config revalidation asks this on every read, and a full download for it is the regression this
        // method exists to avoid.
        String key = "config/version-only";
        assertThat(store.writeVersioned(key, "a".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object expected = store.readVersioned(key).orElseThrow().token();

        server.resetRequests();
        assertThat(store.version(key)).contains(expected);

        List<LoggedRequest> requests = server.findAll(WireMock.getRequestedFor(WireMock.urlMatching(".*version-only.*")));
        assertThat(requests).as("one object-document request, no media").hasSize(1);
        assertThat(requests.get(0).getUrl()).doesNotContain("alt=media");
    }

    @Test
    public void an_absent_object_has_no_version_and_a_faulted_document_fails_loud() throws IOException {
        assertThat(store.version("config/never-written")).isEmpty();
        // Only a 404 is absence; a refusal must surface, or a published artifact reads as missing for as long as the
        // backend misbehaves.
        assertThatThrownBy(() -> store.version("config/faulted"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("faulted");
    }

    @Test
    public void version_fails_fast_when_the_document_omits_the_generation() throws IOException {
        String key = "config/no-generation-header";
        assertThat(store.writeVersioned(key, "x".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThatThrownBy(() -> store.version(key))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("generation");
    }

    @Test
    public void a_token_from_a_deleted_incarnation_no_longer_passes() throws IOException {
        // The token identifies the stored incarnation: a delete and re-create yields a new generation, so a
        // compare-and-set from before the delete is refused against the replacement.
        String key = "config/incarnation";
        assertThat(store.writeVersioned(key, "first".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        Object stale = store.readVersioned(key).orElseThrow().token();
        store.delete(key);
        assertThat(store.readVersioned(key)).as("the delete really removed the object").isEmpty();
        assertThat(store.writeVersioned(key, "second".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(store.writeVersioned(key, "stale".getBytes(StandardCharsets.UTF_8), stale))
                .as("a token naming the deleted incarnation is refused against its replacement").isFalse();
        assertThat(new String(store.readVersioned(key).orElseThrow().content(), StandardCharsets.UTF_8))
                .isEqualTo("second");
    }

    @Test
    public void read_versioned_fails_fast_when_the_media_omits_the_generation_header() throws IOException {
        String key = "config/media-no-generation-header";
        assertThat(store.writeVersioned(key, "x".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThatThrownBy(() -> store.readVersioned(key))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("x-goog-generation");
    }

    @Test
    public void open_streams_a_stored_blob_back_and_a_missing_key_throws() throws IOException {
        byte[] body = "opened".getBytes(StandardCharsets.UTF_8);
        assertThat(store.writeVersioned("blobs/opened", body, null)).isTrue();
        try (InputStream in = store.open("blobs/opened")) {
            assertThat(in.readAllBytes()).as("open() streams the stored bytes back").isEqualTo(body);
        }
        assertThatThrownBy(() -> store.open("blobs/absent")).isInstanceOf(IOException.class);
    }

    @Test
    public void an_absent_bucket_is_an_error_not_a_lost_compare_and_set() {
        // A missing or renamed bucket must surface, or the caller's retry loop turns an outage into silent exhaustion.
        ArtifactStore gone = ArtifactStoreProvider.resolve("gcs", JsonGcs.settings(server.port(), "gone")::get);
        assertThatThrownBy(() -> gone.writeVersioned("config/x", "x".getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gone");
    }

    @Test
    public void a_plaintext_endpoint_is_refused_unless_opted_in() {
        // The endpoint must be https by default so the bearer token is never sent over plaintext; a http emulator
        // endpoint is refused with a clear error unless gcs.allow-insecure-endpoint opts in.
        assertThatThrownBy(() -> GcsArtifactStoreProvider.secureEndpoint("http://localhost:9000", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https")
                .hasMessageContaining("jenreg.gcs.allow-insecure-endpoint");
        assertThat(GcsArtifactStoreProvider.secureEndpoint("http://localhost:9000", "true"))
                .as("the opt-out permits a plaintext emulator endpoint")
                .isEqualTo(URI.create("http://localhost:9000"));
        assertThat(GcsArtifactStoreProvider.secureEndpoint("https://storage.googleapis.com", null))
                .as("an https endpoint is always accepted")
                .isEqualTo(URI.create("https://storage.googleapis.com"));
    }

    @Test
    public void a_missing_bucket_setting_is_a_clear_configuration_error() {
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("gcs", Map.of("jenreg.gcs.credentials", GcsArtifactStoreProvider.ANONYMOUS)::get))
                .hasMessageContaining("jenreg.gcs.bucket");
    }
}
