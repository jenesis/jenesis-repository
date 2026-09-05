package build.jenesis.repository.store.gcs.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listing, the ranged read and the retry, over the same {@link JsonGcs} stub and the real client. Paging is
 * native - one request per page of {@code limit + 1}, resumed by the service's page token - and a scan resumes
 * from its cursor without re-delivering the cursor's own object, which the JSON API's inclusive start offset
 * re-lists; this is the one path Google's testbench cannot exercise, since it returns every match in one page. A
 * ranged read is one {@code Range} request. A 429 on an upload is retried by the client's backoff and lands.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GcsListingAndRetryTest {

    private WireMockServer server;
    private ArtifactStore store;
    private final JsonGcs gcs = new JsonGcs();

    @BeforeAll
    public void start() throws IOException {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort().extensions(gcs));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        store = ArtifactStoreProvider.resolve("gcs", JsonGcs.settings(server.port(), "repo")::get).scope("acme");
        for (String name : List.of("a", "b", "c", "d", "e")) {
            assertThat(store.writeVersioned("list/" + name, name.getBytes(StandardCharsets.UTF_8), null)).isTrue();
        }
        assertThat(store.writeVersioned("list/sub/x", "x".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(store.writeVersioned("list/sub/y", "y".getBytes(StandardCharsets.UTF_8), null)).isTrue();
    }

    @AfterAll
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void paging_is_native_and_resumes_after_a_boundary() {
        server.resetRequests();
        assertThat(page("list", "", 2)).containsExactly("a", "b");
        assertThat(page("list", "b", 2)).containsExactly("c", "d");
        assertThat(page("list", "d", 2)).containsExactly("e", "sub");
        assertThat(page("list", "sub", 2)).isEmpty();
        assertThat(server.findAll(WireMock.getRequestedFor(WireMock.urlMatching(".*maxResults=3.*"))))
                .as("a page asks the service for limit + 1, never for the container whole").isNotEmpty();
    }

    @Test
    public void a_scan_resumes_from_its_cursor_without_redelivering_it() throws IOException {
        List<String> first = new ArrayList<>();
        ArtifactStore.Scan scan = store.scan("list", null, 2, listed -> first.add(listed.key()));
        assertThat(first).containsExactly("list/a", "list/b");
        assertThat(scan.cursor()).contains("list/b");
        List<String> rest = new ArrayList<>();
        ArtifactStore.Scan more = store.scan("list", scan.cursor().orElseThrow(), 10, listed -> rest.add(listed.key()));
        assertThat(rest).as("the cursor's own object is not delivered again").containsExactly("list/c", "list/d", "list/e", "list/sub/x", "list/sub/y");
        assertThat(more.cursor()).isEmpty();
    }

    @Test
    public void list_names_immediate_children_once() {
        assertThat(store.list("list")).containsExactly("a", "b", "c", "d", "e", "sub");
        assertThat(store.list("list/sub")).containsExactly("x", "y");
        assertThat(store.list("list/nowhere")).isEmpty();
    }

    @Test
    public void a_ranged_read_is_one_range_request() throws IOException {
        store.write("blobs/ranged", new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)));
        server.resetRequests();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        store.read("blobs/ranged", new Ranged(2, 4, sink));
        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("2345");
        assertThat(server.findAll(WireMock.getRequestedFor(WireMock.urlMatching(".*alt=media.*"))
                .withHeader("Range", WireMock.equalTo("bytes=2-5")))).hasSize(1);
    }

    @Test
    public void a_throttled_upload_is_retried_by_the_backoff_and_lands() throws IOException {
        int before = gcs.uploads.get();
        assertThat(store.writeVersioned("throttled/key", "t".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(gcs.uploads.get() - before).as("one refused with a 429, one landed").isEqualTo(2);
        assertThat(new String(store.readVersioned("throttled/key").orElseThrow().content(), StandardCharsets.UTF_8)).isEqualTo("t");
    }

    @Test
    public void deleting_what_is_absent_is_not_an_error() throws IOException {
        store.delete("blobs/never-there");
        store.delete("blobs/never-there");
    }

    private List<String> page(String prefix, String startAfter, int limit) {
        List<String> names = new ArrayList<>();
        store.page(prefix, startAfter, limit, names::add);
        return names;
    }

    /** A sink asking for {@code length} bytes from {@code offset}: the store writes the slice to {@link #sink()},
     *  never to the stream itself. */
    private static final class Ranged extends OutputStream implements ArtifactStore.RangedSink {
        private final long offset;
        private final long length;
        private final OutputStream sink;

        private Ranged(long offset, long length, OutputStream sink) {
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
        public void write(int b) {
            throw new UnsupportedOperationException("a ranged read writes to the sink");
        }
    }
}
