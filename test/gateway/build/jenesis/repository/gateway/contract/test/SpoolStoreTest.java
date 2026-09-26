package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.gateway.SpoolStore;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The budgeted {@link SpoolStore} - the hardening proxy's pre-verdict staging store. Proves its contract:
 * a body within budget spools to a temp file, streams through the {@code nocache} leg and is reclaimed afterward (no
 * orphan spool, gauges back to zero); a body over the size budget, or one spool past the concurrency budget, is
 * refused with a 503 whose reason names the exhausted budget and increments the exhaustion counter; a mid-stream fetch
 * error deletes the partial temp file and releases its budget; and the bounded gauges + exhaustion counter it reports
 * through the {@link build.jenesis.repository.observation.ObservabilitySource} seam read live state.
 */
class SpoolStoreTest {

    @Test
    void a_body_within_budget_spools_streams_through_and_the_temp_file_is_gone_afterward(@TempDir Path spoolDir)
            throws IOException {
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(1L << 20, 4), spoolDir);
        RepositoryRouter router = passThroughRouter(spool, "hello from the untrusted upstream");

        RecordingExchange exchange = new RecordingExchange("/t/artifact.bin");
        router.serve("acme", "passthru", new DownloadProxyFormat(), exchange);

        assertThat(exchange.status()).as("the screened body is served through").isEqualTo(200);
        assertThat(exchange.body()).isEqualTo("hello from the untrusted upstream");
        assertThat(spoolDir).as("every spool temp file was reclaimed once the request was served").isEmptyDirectory();
        assertThat(spool.bytesInFlight()).as("the in-flight byte gauge is back to zero").isZero();
        assertThat(spool.activeSpools()).as("the concurrency slot was released").isZero();
        assertThat(spool.exhaustionEvents()).as("nothing was refused").isZero();
    }

    @Test
    void a_body_over_the_size_budget_is_a_503_naming_the_budget_and_leaves_no_spool(@TempDir Path spoolDir)
            throws IOException {
        // A budget smaller than the body: the size budget trips while the untrusted body streams into the scratch,
        // before any response byte is written, so the router refuses cleanly with 503 rather than spooling unbounded.
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(4, 4), spoolDir);
        RepositoryRouter router = passThroughRouter(spool, "a body far larger than the four-byte budget");

        RecordingExchange exchange = new RecordingExchange("/t/artifact.bin");
        router.serve("acme", "passthru", new DownloadProxyFormat(), exchange);

        assertThat(exchange.status()).as("budget exhaustion is a 503 Insufficient resources").isEqualTo(503);
        assertThat(exchange.body()).as("no byte of the refused body reached the client").isEmpty();
        assertThat(spool.exhaustionEvents()).as("the exhaustion was counted").isEqualTo(1);
        assertThat(spoolDir).as("the partial spool was cleaned up on refusal").isEmptyDirectory();
        assertThat(spool.bytesInFlight()).isZero();
        assertThat(spool.activeSpools()).isZero();
    }

    @Test
    void the_size_budget_names_itself_in_the_refusal() throws Exception {
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(4, 4));
        ArtifactStore scratch = spool.acquire();
        try {
            assertThatThrownBy(() -> scratch.writeBlob(bytes(64)))
                    .isInstanceOf(SpoolStore.BudgetExhausted.class)
                    .hasMessageContaining("size budget")
                    .hasMessageContaining("4 bytes");
        } finally {
            ((AutoCloseable) scratch).close();
        }
    }

    @Test
    void a_second_spool_past_the_concurrency_budget_is_refused_naming_the_budget() throws Exception {
        // One concurrent spool allowed: the first holds its slot after writing a blob; a second spool's first write is
        // refused with a 503-mapped BudgetExhausted naming the concurrency budget.
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(1L << 20, 1));
        ArtifactStore first = spool.acquire();
        ArtifactStore second = spool.acquire();
        try {
            first.writeBlob(bytes(16));
            assertThat(spool.activeSpools()).as("the first spool holds the only slot").isEqualTo(1);

            assertThatThrownBy(() -> second.writeBlob(bytes(16)))
                    .isInstanceOf(SpoolStore.BudgetExhausted.class)
                    .hasMessageContaining("concurrency budget")
                    .hasMessageContaining("1 concurrent");
            assertThat(spool.exhaustionEvents()).isEqualTo(1);
            assertThat(spool.activeSpools()).as("the refused spool took no slot").isEqualTo(1);
        } finally {
            ((AutoCloseable) first).close();
            ((AutoCloseable) second).close();
        }
        assertThat(spool.activeSpools()).as("both spools released on close").isZero();
    }

    @Test
    void a_mid_stream_error_deletes_the_partial_spool_and_releases_its_budget(@TempDir Path spoolDir)
            throws Exception {
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(1L << 20, 4), spoolDir);
        ArtifactStore scratch = spool.acquire();
        try {
            assertThatThrownBy(() -> scratch.writeBlob(failingAfter(1024)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("upstream connection lost");
            assertThat(spoolDir).as("the partial temp file was deleted on the mid-stream error").isEmptyDirectory();
            assertThat(spool.bytesInFlight()).as("the reserved bytes were released").isZero();
        } finally {
            ((AutoCloseable) scratch).close();
        }
        assertThat(spool.activeSpools()).as("the slot was released on close").isZero();
    }

    @Test
    void the_metrics_are_bounded_gauges_and_an_exhaustion_counter_read_live() throws Exception {
        SpoolStore spool = new SpoolStore(new SpoolStore.Budget(64, 2));

        Map<String, Metric> before = byName(spool.metrics());
        assertThat(before).containsKeys("jenreg.gateway.spool.bytes", "jenreg.gateway.spool.count",
                "jenreg.gateway.spool.exhausted");
        assertThat(before.get("jenreg.gateway.spool.bytes").limit()).hasValue(64d);
        assertThat(before.get("jenreg.gateway.spool.bytes").kind()).isEqualTo(Metric.Kind.GAUGE);
        assertThat(before.get("jenreg.gateway.spool.count").limit()).hasValue(2d);
        assertThat(before.get("jenreg.gateway.spool.exhausted").kind()).isEqualTo(Metric.Kind.COUNTER);
        assertThat(before.get("jenreg.gateway.spool.exhausted").value()).isZero();

        ArtifactStore scratch = spool.acquire();
        try {
            assertThatThrownBy(() -> scratch.writeBlob(bytes(128))).isInstanceOf(SpoolStore.BudgetExhausted.class);
        } finally {
            ((AutoCloseable) scratch).close();
        }
        assertThat(byName(spool.metrics()).get("jenreg.gateway.spool.exhausted").value())
                .as("the exhaustion counter moved").isEqualTo(1d);

        // The store is its own source: a context that built it reports its signals.
        ObservabilityReport report = ObservabilityReport.of(List.of(spool));
        assertThat(report.metrics()).extracting(Metric::name).contains("jenreg.gateway.spool.bytes",
                "jenreg.gateway.spool.count", "jenreg.gateway.spool.exhausted");
        assertThat(report.healthChecks()).extracting("name").contains("jenreg.gateway.spool");
    }

    private static RepositoryRouter passThroughRouter(SpoolStore spool, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ProxyFormat.Fetcher fetcher = new ProxyFormat.Fetcher.Buffered() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                throw new AssertionError("a pass-through artifact streams via download, not the buffered fetch");
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(payload), Map.of()));
            }
        };
        Map<String, RepositoryDefinition> definitions = Map.of("passthru", RepositoryDefinition.parse("proxy http://up/ nocache"));
        return new RepositoryRouter(definitions::get,
                (_, _) -> {
                    throw new AssertionError("a pass-through never touches the repository store");
                }, fetcher).passingThrough(spool::acquire);
    }

    private static Map<String, Metric> byName(List<Metric> metrics) {
        Map<String, Metric> byName = new LinkedHashMap<>();
        for (Metric metric : metrics) {
            byName.put(metric.name(), metric);
        }
        return byName;
    }

    private static InputStream bytes(int count) {
        return new ByteArrayInputStream(new byte[count]);
    }

    /** A stream that yields {@code good} zero bytes then throws, to prove a mid-stream fetch failure cleans up. */
    private static InputStream failingAfter(int good) {
        return new InputStream() {
            private int remaining = good;

            @Override
            public int read() throws IOException {
                if (remaining-- <= 0) {
                    throw new IOException("upstream connection lost");
                }
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (remaining <= 0) {
                    throw new IOException("upstream connection lost");
                }
                int read = Math.min(length, remaining);
                remaining -= read;
                return read;
            }
        };
    }

    /** A minimal proxy format: a miss streams the upstream download into the store via {@code writeBlob} and serves it
     *  back - the two places the budgeted spool sees the untrusted body. */
    private static final class DownloadProxyFormat implements RepositoryFormat, ProxyFormat {

        @Override
        public String name() {
            return "t";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/t/");
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            Publication publication = new Publication(store, List.of());
            Optional<String> key = publication.located(exchange.path());
            if (key.isEmpty()) {
                exchange.respond(404);
                return;
            }
            try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
                store.read(key.get(), out);
            }
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
                throws IOException {
            Optional<ProxyFormat.Download> fetched = fetcher.download(upstream, Map.of());
            if (fetched.isEmpty()) {
                return false;
            }
            try (ProxyFormat.Download download = fetched.get()) {
                if (download.status() != 200) {
                    return false;
                }
                Publication publication = new Publication(store, List.of());
                publication.link(exchange.path(), publication.storeBlob(download.body()));
            }
            handle(exchange, store);
            return true;
        }
    }

    /** A {@link FormatExchange} that records the response status and buffers the body so a test can assert them. */
    private static final class RecordingExchange implements FormatExchange {

        private final String path;
        private int status = -1;
        private ByteArrayOutputStream response;

        private RecordingExchange(String path) {
            this.path = path;
        }

        private int status() {
            return status;
        }

        private String body() {
            return response == null ? "" : response.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            this.response = new ByteArrayOutputStream();
            return response;
        }
    }
}
