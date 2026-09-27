package build.jenesis.repository.store.s3.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.s3.S3ArtifactStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listing, describing and compare-and-set halves of the S3-compatible stores against a stubbed endpoint: a
 * WireMock server answers the few S3 calls each method makes with the service's own XML, and the real SDK client
 * drives it, so what is asserted is how the store reads the service's answers - never a mock of the store.
 *
 * <p>The listings are the part worth pinning here. {@code ListObjectsV2} returns a container as a grouped prefix
 * {@code name/}, which sorts <em>after</em> a sibling that extends the name past a character below {@code '/'}, so a
 * page is re-ordered by child name before it is handed out; a recursive scan asks for one more key than it delivers
 * and reports where it stopped; and a paged listing follows the service's continuation token. The compare-and-set
 * is the other: {@code If-None-Match: *} for a create, {@code If-Match} for an update, a {@code 412} read as a lost
 * race and a missing bucket as the fault it is.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ListingTest {

    private WireMockServer server;
    private S3Client s3;
    private ArtifactStore store;

    @BeforeAll
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + server.port()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")))
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(true)
                .build();
        store = new S3ArtifactStore(s3, "repo").scope("acme");
    }

    @AfterAll
    void stop() {
        s3.close();
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    /** One {@code ListObjectsV2} page: objects as {@code key=size}, grouped prefixes, and the next token if any. */
    private static String page(List<String> objects, List<String> prefixes, String next) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>repo</Name>"
                + "<IsTruncated>" + (next != null) + "</IsTruncated>");
        if (next != null) {
            xml.append("<NextContinuationToken>").append(next).append("</NextContinuationToken>");
        }
        for (String object : objects) {
            String[] parts = object.split("=");
            xml.append("<Contents><Key>").append(parts[0]).append("</Key>")
                    .append("<LastModified>2026-01-02T03:04:05.000Z</LastModified><ETag>\"e\"</ETag><Size>")
                    .append(parts[1]).append("</Size></Contents>");
        }
        for (String prefix : prefixes) {
            xml.append("<CommonPrefixes><Prefix>").append(prefix).append("</Prefix></CommonPrefixes>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    private void listing(String prefix, String body) {
        server.stubFor(get(urlPathEqualTo("/repo")).withQueryParam("list-type", equalTo("2"))
                .withQueryParam("prefix", equalTo(prefix)).withQueryParam("continuation-token", absent())
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(body)));
    }

    @Test
    void a_level_lists_objects_and_containers_by_name_once_each() {
        listing("acme/docs/", page(List.of("acme/docs/b.txt=2", "acme/docs/a.txt=1", "acme/docs/deep/x=1"),
                List.of("acme/docs/sub/", "acme/docs/a/"), null));

        assertThat(store.list("docs")).as("sorted, a container without its delimiter, nothing from deeper down")
                .containsExactly("a", "a.txt", "b.txt", "sub");
    }

    @Test
    void a_page_hands_a_container_out_before_a_sibling_that_extends_its_name() throws IOException {
        // The service's order: the object app.txt precedes the grouped prefix app/, yet the child app pages first.
        listing("acme/", page(List.of("acme/app.txt=7", "acme/zeta=3"), List.of("acme/app/"), null));
        List<ArtifactStore.Listed> listed = new ArrayList<>();

        store.pageListed("", "", 10, listed::add);

        assertThat(listed).extracting(ArtifactStore.Listed::key).containsExactly("app", "app.txt", "zeta");
        assertThat(listed.get(0).size()).as("a container has no size of its own").isEmpty();
        assertThat(listed.get(1).size()).hasValue(7);
        assertThat(listed.get(1).modified()).hasValue(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void a_page_stops_at_its_limit_and_resumes_after_its_boundary() throws IOException {
        listing("acme/", page(List.of("acme/a=1", "acme/b=1", "acme/c=1"), List.of(), null));
        server.stubFor(get(urlPathEqualTo("/repo")).withQueryParam("start-after", equalTo("acme/b"))
                .atPriority(1).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(page(List.of("acme/c=1"), List.of(), null))));
        List<String> first = new ArrayList<>();
        List<String> next = new ArrayList<>();

        store.pageListed("", "", 2, listed -> first.add(listed.key()));
        store.pageListed("", "b", 2, listed -> next.add(listed.key()));
        store.pageListed("", "", 0, listed -> first.add("never"));

        assertThat(first).containsExactly("a", "b");
        assertThat(next).containsExactly("c");
    }

    @Test
    void a_scan_delivers_every_key_below_the_prefix_and_says_where_it_stopped() throws IOException {
        listing("acme/blobs/", page(List.of("acme/blobs/1=10", "acme/blobs/2=20", "acme/blobs/3=30"), List.of(), null));
        List<ArtifactStore.Listed> scanned = new ArrayList<>();

        ArtifactStore.Scan truncated = store.scan("blobs", null, 2, scanned::add);

        assertThat(scanned).extracting(ArtifactStore.Listed::key).containsExactly("blobs/1", "blobs/2");
        assertThat(scanned.get(1).size()).hasValue(20);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.cursor()).contains("blobs/2");

        ArtifactStore.Scan exhausted = store.scan("blobs", null, 10, _ -> { });
        assertThat(exhausted.truncated()).isFalse();
        assertThat(exhausted.delivered()).isEqualTo(3);
        assertThatThrownBy(() -> store.scan("blobs", null, 0, _ -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_listing_follows_the_services_continuation_token() {
        listing("acme/many/", page(List.of("acme/many/a=1"), List.of(), "token-2"));
        server.stubFor(get(urlPathEqualTo("/repo")).withQueryParam("continuation-token", equalTo("token-2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(page(List.of("acme/many/b=1"), List.of("acme/many/c/"), null))));

        assertThat(store.list("many")).containsExactly("a", "b", "c");
    }

    @Test
    void a_key_is_described_by_its_head_and_an_absent_one_by_nothing() throws IOException {
        server.stubFor(head(urlPathEqualTo("/repo/acme/present")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Length", "42").withHeader("ETag", "\"v1\"")
                .withHeader("Last-Modified", "Fri, 02 Jan 2026 03:04:05 GMT")));
        server.stubFor(head(urlPathEqualTo("/repo/acme/absent")).willReturn(aResponse().withStatus(404)));
        server.stubFor(head(urlPathEqualTo("/repo/acme/forbidden")).willReturn(aResponse().withStatus(403)));

        assertThat(store.listed("present")).hasValueSatisfying(listed -> {
            assertThat(listed.size()).hasValue(42);
            assertThat(listed.modified()).hasValue(Instant.parse("2026-01-02T03:04:05Z"));
        });
        assertThat(store.exists("present")).isTrue();
        assertThat(store.size("present")).isEqualTo(42);
        assertThat(store.version("present")).contains("\"v1\"");
        assertThat(store.listed("absent")).isEmpty();
        assertThat(store.exists("absent")).isFalse();
        assertThat(store.size("absent")).isEqualTo(-1);
        assertThat(store.version("absent")).isEmpty();
        assertThatThrownBy(() -> store.listed("forbidden")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.version("forbidden")).isInstanceOf(IOException.class);
    }

    @Test
    void a_versioned_read_carries_the_objects_etag_and_a_missing_key_reads_as_absent() throws IOException {
        server.stubFor(get(urlPathEqualTo("/repo/acme/doc")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"v7\"").withBody("{\"a\":1}")));
        server.stubFor(get(urlPathEqualTo("/repo/acme/gone")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchKey</Code><Message>absent</Message></Error>")));

        assertThat(store.readVersioned("doc")).hasValueSatisfying(versioned -> {
            assertThat(new String(versioned.content(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            assertThat(versioned.token()).isEqualTo("\"v7\"");
        });
        assertThat(store.readVersioned("gone")).isEmpty();
        assertThatThrownBy(() -> store.open("gone")).isInstanceOf(NoSuchFileException.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("doc", out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void a_create_is_conditional_on_absence_and_an_update_on_the_version_read() throws IOException {
        server.stubFor(put(urlPathEqualTo("/repo/acme/doc")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"v2\"")));

        assertThat(store.writeVersioned("doc", new byte[] {1}, null)).isTrue();
        assertThat(store.writeVersioned("doc", new byte[] {2}, "\"v1\"")).isTrue();

        server.verify(putRequestedFor(urlPathEqualTo("/repo/acme/doc")).withHeader("If-None-Match", equalTo("*")));
        server.verify(putRequestedFor(urlPathEqualTo("/repo/acme/doc")).withHeader("If-Match", equalTo("\"v1\"")));
    }

    @Test
    void a_lost_race_is_false_and_a_missing_bucket_is_a_fault() {
        server.stubFor(put(urlPathEqualTo("/repo/acme/raced")).willReturn(aResponse().withStatus(412)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>PreconditionFailed</Code><Message>no</Message></Error>")));
        server.stubFor(put(urlPathEqualTo("/repo/acme/nobucket")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody("<Error><Code>NoSuchBucket</Code><Message>no bucket</Message></Error>")));

        assertThat(catching(() -> store.writeVersioned("raced", new byte[] {1}, null))).isEqualTo(false);
        assertThatThrownBy(() -> store.writeVersioned("nobucket", new byte[] {1}, null))
                .isInstanceOf(IOException.class).hasMessageContaining("bucket repo does not exist");
    }

    @Test
    void a_delete_reaches_the_service_and_a_refused_one_is_reported() throws IOException {
        server.stubFor(delete(urlPathEqualTo("/repo/acme/doc")).willReturn(aResponse().withStatus(204)));
        server.stubFor(delete(urlPathEqualTo("/repo/acme/locked")).willReturn(aResponse().withStatus(403)));
        server.stubFor(get(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(404)));

        store.delete("doc");

        server.verify(deleteRequestedFor(urlPathEqualTo("/repo/acme/doc")));
        assertThatThrownBy(() -> store.delete("locked")).isInstanceOf(IOException.class)
                .hasMessage("Could not delete locked");
    }

    private static Object catching(Callable<Boolean> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e;
        }
    }
}
