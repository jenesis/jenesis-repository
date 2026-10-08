package build.jenesis.repository.store.azure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.azure.AzureArtifactStore;
import build.jenesis.repository.store.azure.AzureArtifactStoreProvider;
import build.jenesis.repository.store.azure.AzureTransport;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.UrlPathPattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Azure Blob store against a stubbed endpoint: a WireMock server answers the Blob service calls each method makes
 * with the service's own XML and headers, and the real {@code azure-storage-blob} client drives it through the
 * store's own transport - so what is asserted is how the store reads the service's answers.
 *
 * <p>The listings: List Blobs hands a page's blobs and its hierarchy prefixes back as one list, a container's prefix
 * sorting after a sibling that extends its name, so a page is re-ordered by child name before it is handed out; a
 * recursive scan seeks with {@code startFrom}, which is inclusive, and drops the boundary it resumes from. The
 * compare-and-set: {@code If-None-Match: *} for a create, {@code If-Match} for an update, a {@code 412} or
 * {@code 409} read as a lost race, and a missing container as the fault it is. The provider: a plaintext endpoint is
 * accepted only through its opt-out, the container is defaulted and created, and the store answers for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AzureStoreTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String BLOBS = "/" + ACCOUNT + "/repo/";
    private static final String MODIFIED = "Fri, 02 Jan 2026 03:04:05 GMT";

    private WireMockServer server;
    private ArtifactStore store;

    @BeforeAll
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
        BlobContainerClient container = new BlobServiceClientBuilder().httpClient(new AzureTransport())
                .connectionString(connectionString()).buildClient().getBlobContainerClient("repo");
        store = new AzureArtifactStore(container, true).scope("acme");
    }

    @AfterAll
    void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    /** Where the client addresses a blob of the {@code acme} scope: the name after the container is one path
     *  segment, its delimiters encoded. */
    private static UrlPathPattern blobPath(String name) {
        return urlPathEqualTo(BLOBS + ("acme/" + name).replace("/", "%2F"));
    }

    private String connectionString() {
        return "DefaultEndpointsProtocol=http;AccountName=" + ACCOUNT + ";AccountKey=" + KEY
                + ";BlobEndpoint=http://localhost:" + server.port() + "/" + ACCOUNT + ";";
    }

    /** One List Blobs page: blobs as {@code name=size}, hierarchy prefixes, and the marker of the next page. */
    private static String page(List<String> blobs, List<String> prefixes, String next) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><EnumerationResults "
                + "ServiceEndpoint=\"http://localhost/" + ACCOUNT + "\" ContainerName=\"repo\"><Blobs>");
        for (String blob : blobs) {
            String[] parts = blob.split("=");
            xml.append("<Blob><Name>").append(parts[0]).append("</Name><Properties><Last-Modified>").append(MODIFIED)
                    .append("</Last-Modified><Etag>0x1</Etag><Content-Length>").append(parts[1])
                    .append("</Content-Length><BlobType>BlockBlob</BlobType></Properties></Blob>");
        }
        for (String prefix : prefixes) {
            xml.append("<BlobPrefix><Name>").append(prefix).append("</Name></BlobPrefix>");
        }
        return xml.append("</Blobs><NextMarker>").append(next == null ? "" : next)
                .append("</NextMarker></EnumerationResults>").toString();
    }

    private void listing(String prefix, String body) {
        server.stubFor(get(urlPathEqualTo("/" + ACCOUNT + "/repo")).withQueryParam("comp", equalTo("list"))
                .withQueryParam("prefix", equalTo(prefix)).withQueryParam("marker", absent())
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(body)));
    }

    private void blob(String name, int length, String etag) {
        server.stubFor(head(blobPath(name)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Length", Integer.toString(length)).withHeader("Last-Modified", MODIFIED)
                .withHeader("ETag", etag).withHeader("x-ms-blob-type", "BlockBlob")));
    }

    private void missing(String name) {
        server.stubFor(head(blobPath(name)).willReturn(aResponse().withStatus(404)
                .withHeader("x-ms-error-code", "BlobNotFound")));
        server.stubFor(get(blobPath(name)).willReturn(aResponse().withStatus(404)
                .withHeader("x-ms-error-code", "BlobNotFound").withHeader("Content-Type", "application/xml")
                .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>BlobNotFound</Code>"
                        + "<Message>The specified blob does not exist.</Message></Error>")));
    }

    @Test
    void a_level_lists_blobs_and_containers_by_name_once_each() {
        listing("acme/docs/", page(List.of("acme/docs/b.txt=2", "acme/docs/a.txt=1"),
                List.of("acme/docs/sub/"), null));

        assertThat(store.list("docs")).containsExactly("a.txt", "b.txt", "sub");
    }

    @Test
    void a_page_hands_a_container_out_before_a_sibling_that_extends_its_name() throws IOException {
        listing("acme/", page(List.of("acme/app.txt=7", "acme/zeta=3"), List.of("acme/app/"), null));
        List<ArtifactStore.Listed> listed = new ArrayList<>();

        store.pageListed("", "", 10, listed::add);

        assertThat(listed).extracting(ArtifactStore.Listed::key).containsExactly("app", "app.txt", "zeta");
        assertThat(listed.get(0).size()).as("a container has no size of its own").isEmpty();
        assertThat(listed.get(1).size()).hasValue(7);
        assertThat(listed.get(1).modified()).hasValue(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void a_page_stops_at_its_limit_and_a_resumed_one_drops_its_inclusive_boundary() throws IOException {
        listing("acme/", page(List.of("acme/a=1", "acme/b=1", "acme/c=1"), List.of(), null));
        server.stubFor(get(urlPathEqualTo("/" + ACCOUNT + "/repo")).withQueryParam("startFrom", equalTo("acme/b"))
                .atPriority(1).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(page(List.of("acme/b=1", "acme/c=1"), List.of(), null))));
        List<String> first = new ArrayList<>();
        List<String> next = new ArrayList<>();

        store.pageListed("", "", 2, listed -> first.add(listed.key()));
        store.pageListed("", "b", 2, listed -> next.add(listed.key()));
        store.pageListed("", "", 0, listed -> first.add("never"));

        assertThat(first).containsExactly("a", "b");
        assertThat(next).containsExactly("c");
    }

    @Test
    void a_scan_delivers_every_blob_below_the_prefix_and_says_where_it_stopped() throws IOException {
        listing("acme/blobs/", page(List.of("acme/blobs/1=10", "acme/blobs/2=20", "acme/blobs/3=30"), List.of(),
                null));
        server.stubFor(get(urlPathEqualTo("/" + ACCOUNT + "/repo")).withQueryParam("startFrom",
                equalTo("acme/blobs/2")).atPriority(1).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody(page(List.of("acme/blobs/2=20", "acme/blobs/3=30"), List.of(), null))));
        List<ArtifactStore.Listed> scanned = new ArrayList<>();

        ArtifactStore.Scan truncated = store.scan("blobs", null, 2, scanned::add);
        List<String> resumed = new ArrayList<>();
        ArtifactStore.Scan exhausted = store.scan("blobs", "blobs/2", 10, listed -> resumed.add(listed.key()));

        assertThat(scanned).extracting(ArtifactStore.Listed::key).containsExactly("blobs/1", "blobs/2");
        assertThat(scanned.get(1).size()).hasValue(20);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.cursor()).contains("blobs/2");
        assertThat(resumed).as("the inclusive seek's boundary is not delivered twice").containsExactly("blobs/3");
        assertThat(exhausted.truncated()).isFalse();
        assertThatThrownBy(() -> store.scan("blobs", null, 0, _ -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_listing_follows_the_services_marker() {
        listing("acme/many/", page(List.of("acme/many/a=1"), List.of(), "marker-2"));
        server.stubFor(get(urlPathEqualTo("/" + ACCOUNT + "/repo")).withQueryParam("marker", equalTo("marker-2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(page(List.of("acme/many/b=1"), List.of("acme/many/c/"), null))));

        assertThat(store.list("many")).containsExactly("a", "b", "c");
    }

    @Test
    void a_blob_is_described_by_its_properties_and_an_absent_one_by_nothing() throws IOException {
        blob("present", 42, "\"0x2\"");
        missing("absent");

        assertThat(store.listed("present")).hasValueSatisfying(listed -> {
            assertThat(listed.size()).hasValue(42);
            assertThat(listed.modified()).hasValue(Instant.parse("2026-01-02T03:04:05Z"));
        });
        assertThat(store.exists("present")).isTrue();
        assertThat(store.size("present")).isEqualTo(42);
        assertThat(store.version("present")).as("the blob's ETag is its version")
                .hasValueSatisfying(token -> assertThat(token.toString()).contains("0x2"));
        assertThat(store.listed("absent")).isEmpty();
        assertThat(store.exists("absent")).isFalse();
        assertThat(store.size("absent")).isEqualTo(-1);
        assertThat(store.version("absent")).isEmpty();
        assertThatThrownBy(() -> store.open("absent")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void a_versioned_read_carries_the_blobs_etag() throws IOException {
        server.stubFor(get(blobPath("doc")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"0x7\"").withHeader("x-ms-blob-type", "BlockBlob")
                .withHeader("Last-Modified", MODIFIED).withHeader("Content-Length", "7").withBody("{\"a\":1}")));
        server.stubFor(get(blobPath("gone")).willReturn(aResponse().withStatus(404)
                .withHeader("x-ms-error-code", "BlobNotFound")));

        assertThat(store.readVersioned("doc")).hasValueSatisfying(versioned -> {
            assertThat(new String(versioned.content(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            assertThat(versioned.token().toString()).contains("0x7");
        });
        assertThat(store.readVersioned("gone")).isEmpty();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.read("doc", out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
        assertThatThrownBy(() -> store.read("gone", new ByteArrayOutputStream())).isInstanceOf(IOException.class);
    }

    @Test
    void a_create_is_conditional_on_absence_and_an_update_on_the_version_read() throws IOException {
        server.stubFor(put(blobPath("doc")).willReturn(aResponse().withStatus(201)
                .withHeader("ETag", "\"0x3\"")));

        assertThat(store.writeVersioned("doc", new byte[] {1}, null)).isTrue();
        assertThat(store.writeVersioned("doc", new ByteArrayInputStream(new byte[] {2}), 1, "\"0x2\"")).isTrue();

        server.verify(putRequestedFor(blobPath("doc")).withHeader("If-None-Match", equalTo("*")));
        server.verify(putRequestedFor(blobPath("doc")).withHeader("If-Match", equalTo("\"0x2\"")));
    }

    @Test
    void a_lost_race_is_false_and_a_missing_container_is_a_fault() {
        server.stubFor(put(blobPath("raced")).willReturn(aResponse().withStatus(412)
                .withHeader("x-ms-error-code", "ConditionNotMet")));
        server.stubFor(put(blobPath("exists")).willReturn(aResponse().withStatus(409)
                .withHeader("x-ms-error-code", "BlobAlreadyExists")));
        server.stubFor(put(blobPath("nocontainer")).willReturn(aResponse().withStatus(404)
                .withHeader("x-ms-error-code", "ContainerNotFound")));

        assertThat(outcome(() -> store.writeVersioned("raced", new byte[] {1}, "\"0x1\""))).isEqualTo(false);
        assertThat(outcome(() -> store.writeVersioned("exists", new byte[] {1}, null))).isEqualTo(false);
        assertThatThrownBy(() -> store.writeVersioned("nocontainer", new byte[] {1}, null))
                .isInstanceOf(IOException.class).hasMessageContaining("container does not exist");
    }

    @Test
    void a_content_addressed_write_lands_at_the_hash_of_its_bytes_once() throws IOException {
        String hash = HexFormat.of().formatHex(sha256("blob bytes"));
        missing("blobs/" + hash);
        server.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(201).withHeader("ETag", "\"0x1\"")));

        assertThat(store.writeBlob(new ByteArrayInputStream("blob bytes".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(hash);
        store.write("plain", new ByteArrayInputStream(new byte[] {1, 2, 3}));

        server.verify(putRequestedFor(blobPath("blobs/" + hash)));
        server.verify(putRequestedFor(blobPath("plain")));

        blob("blobs/" + hash, 10, "\"0x1\"");
        server.resetRequests();
        store.writeBlob(new ByteArrayInputStream("blob bytes".getBytes(StandardCharsets.UTF_8)));
        assertThat(server.findAll(putRequestedFor(anyUrl()))).as("content already held is not sent again")
                .isEmpty();
    }

    @Test
    void an_aborted_write_commits_no_block_list() {
        server.stubFor(put(anyUrl()).willReturn(aResponse().withStatus(201).withHeader("ETag", "\"0x1\"")));
        // Ten megabytes, so the client stages blocks before the source fails, and then the source fails: a committed
        // block list would land a truncated blob, which at blobs/<hash> the dedupe probe would keep for good.
        InputStream torn = new InputStream() {
            private long served;

            @Override
            public int read() throws IOException {
                if (served++ >= 10L << 20) {
                    throw new IOException("the client went away");
                }
                return 'x';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (served >= 10L << 20) {
                    throw new IOException("the client went away");
                }
                int count = (int) Math.min(length, (10L << 20) - served);
                Arrays.fill(buffer, offset, offset + count, (byte) 'x');
                served += count;
                return count;
            }
        };

        assertThatThrownBy(() -> store.write("torn", torn)).isInstanceOf(IOException.class);
        assertThat(server.findAll(putRequestedFor(blobPath("torn")))).as("no request commits the blob: neither a "
                        + "block list nor a single-shot upload")
                .noneMatch(request -> request.getUrl().contains("comp=blocklist"))
                .noneMatch(request -> !request.getUrl().contains("comp="));
    }

    @Test
    void a_ranged_read_asks_the_service_for_the_window_alone() throws IOException {
        byte[] whole = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        blob("ranged", whole.length, "\"0x1\"");
        server.stubFor(get(blobPath("ranged")).willReturn(aResponse().withStatus(206)
                .withHeader("Content-Range", "bytes 10-19/" + whole.length).withHeader("Content-Length", "10")
                .withHeader("ETag", "\"0x1\"").withHeader("Last-Modified", MODIFIED)
                .withHeader("x-ms-blob-type", "BlockBlob").withBody(Arrays.copyOfRange(whole, 10, 20))));
        ByteArrayOutputStream window = new ByteArrayOutputStream();

        store.read("ranged", new Window(10, 10, window));

        assertThat(window.toByteArray()).as("the window and nothing else").isEqualTo(Arrays.copyOfRange(whole, 10, 20));
        assertThat(server.findAll(getRequestedFor(blobPath("ranged")))).isNotEmpty().allSatisfy(request ->
                assertThat(Optional.ofNullable(request.getHeader("x-ms-range")).orElse(request.getHeader("Range")))
                        .as("each download asks for the window").isEqualTo("bytes=10-19"));
    }

    /** A sink asking the store for {@code length} bytes from {@code offset}, as a ranged download hands one over. */
    private static final class Window extends OutputStream implements ArtifactStore.RangedSink {

        private final long offset;
        private final long length;
        private final OutputStream sink;

        Window(long offset, long length, OutputStream sink) {
            this.offset = offset;
            this.length = length;
            this.sink = sink;
        }

        @Override
        public long offset() {
            return offset;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public OutputStream sink() {
            return sink;
        }

        @Override
        public void write(int value) throws IOException {
            sink.write(value);
        }
    }

    @Test
    void a_delete_reaches_the_service_and_a_refused_one_is_reported() throws IOException {
        server.stubFor(delete(blobPath("doc")).willReturn(aResponse().withStatus(202)));
        server.stubFor(delete(blobPath("gone")).willReturn(aResponse().withStatus(404)
                .withHeader("x-ms-error-code", "BlobNotFound")));
        server.stubFor(delete(blobPath("locked")).willReturn(aResponse().withStatus(403)
                .withHeader("x-ms-error-code", "AuthorizationFailure")));

        store.delete("doc");
        store.delete("gone");

        server.verify(deleteRequestedFor(blobPath("doc")));
        assertThatThrownBy(() -> store.delete("locked")).isInstanceOf(IOException.class)
                .hasMessage("Could not delete locked");
    }

    @Test
    void the_provider_builds_a_store_over_its_container_once_plaintext_is_opted_into() {
        server.stubFor(put(urlPathEqualTo("/" + ACCOUNT + "/jenesis-repository")).willReturn(aResponse()
                .withStatus(201)));
        AzureArtifactStoreProvider provider = new AzureArtifactStoreProvider();
        Map<String, String> config = new HashMap<>(Map.of(AzureArtifactStoreProvider.CONNECTION_STRING_KEY,
                connectionString(), AzureArtifactStoreProvider.PROBE_KEY, "false"));

        assertThat(provider.name()).isEqualTo("azure-blob");
        assertThat(provider.requiredConfig()).containsExactly(AzureArtifactStoreProvider.CONNECTION_STRING_KEY);
        assertThat(provider.config()).contains(AzureArtifactStoreProvider.CONTAINER_KEY);
        assertThatThrownBy(() -> provider.create(config::get)).as("a plaintext endpoint needs the opt-out")
                .isInstanceOf(IllegalStateException.class);

        config.put(AzureArtifactStoreProvider.ALLOW_INSECURE_KEY, "true");
        ArtifactStore created = provider.create(config::get);

        assertThat(created.identity().toString()).as("the default container")
                .contains("/" + ACCOUNT + "/jenesis-repository");
        server.verify(putRequestedFor(urlPathEqualTo("/" + ACCOUNT + "/jenesis-repository"))
                .withQueryParam("restype", equalTo("container")));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object outcome(Callable<Boolean> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e;
        }
    }
}
