package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code nocache} pass-through proxy streams end to end: an upstream artifact larger than the maximum size
 * of a Java array flows from the network through the scratch store to the response without ever being held whole in
 * memory. A pass-through claims to "stream without storing", but a heap-backed scratch store would materialise every
 * in-flight artifact - a {@code readAllBytes()} on the way in and a whole {@code byte[]} on the way out - and blow the
 * array limit on a body over {@link Integer#MAX_VALUE} bytes. That this proxy serves every byte of such a body is the
 * bounded-heap guarantee: the fetched artifact streamed straight through, spooled at most to a scratch file, never a
 * heap buffer. The scratch store here counts and discards, so the test needs no 2 GB of disk to make the point.
 */
class PassThroughStreamingTest {

    private static final long SIZE = (long) Integer.MAX_VALUE + (1L << 20);
    private static final int CHUNK = 64 * 1024;

    @Test
    void a_nocache_proxy_streams_an_artifact_larger_than_the_array_limit() throws IOException {
        CountingPassStore store = new CountingPassStore();
        ProxyFormat.Fetcher fetcher = new ProxyFormat.Fetcher.Buffered() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) {
                throw new AssertionError("a pass-through artifact must stream via download, not the buffered fetch");
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) {
                return Optional.of(new ProxyFormat.Download(200, new SyntheticInputStream(SIZE), Map.of()));
            }
        };
        Map<String, RepositoryDefinition> definitions = Map.of("passthru", RepositoryDefinition.parse("fallback http://up/ nocache"));
        RepositoryRouter router = new RepositoryRouter(definitions::get,
                (_, _) -> {
                    throw new AssertionError("a pass-through never touches the repository store");
                }, fetcher).passingThrough(() -> store);

        CountingExchange exchange = new CountingExchange("/t/big.bin");
        router.serve("acme", "passthru", new StreamingProxyFormat(), exchange);

        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.written()).as("every byte of the artifact streamed to the response").isEqualTo(SIZE);
        assertThat(store.written()).as("every byte streamed through the scratch store, none buffered whole")
                .isEqualTo(SIZE);
        assertThat(store.largestChunk()).as("no single read ever held more than the copy buffer")
                .isLessThanOrEqualTo(CHUNK * 4L);
    }

    /** A minimal proxy format mirroring the real ones: a local miss streams the upstream download into the store and
     *  serves it back from there, so the whole fetch flows through {@link ArtifactStore#writeBlob} and {@link
     *  ArtifactStore#read} - the two places a heap-backed scratch store would materialise it. */
    private static final class StreamingProxyFormat implements RepositoryFormat, ProxyFormat {

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

    /** A scratch store that streams a blob through in bounded chunks and discards it, then regenerates it on read, so
     *  the pass-through's write-then-serve round trip runs end to end without a 2 GB heap buffer or a 2 GB temp file.
     *  It records the total it saw and the largest single chunk; the small pointer objects stay in memory. */
    private static final class CountingPassStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> pointers = new ConcurrentHashMap<>();
        private long written = -1;
        private long largestChunk;

        long written() {
            return written;
        }

        long largestChunk() {
            return largestChunk;
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            long total = 0;
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                largestChunk = Math.max(largestChunk, read);
            }
            written = total;
            return "big";
        }

        @Override
        public boolean exists(String key) {
            return key.equals("blobs/big") || pointers.containsKey(key);
        }

        @Override
        public long size(String key) {
            return key.equals("blobs/big") ? written : -1L;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            if (!key.equals("blobs/big")) {
                return;
            }
            byte[] buffer = new byte[CHUNK];
            long remaining = written;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                out.write(buffer, 0, chunk);
                remaining -= chunk;
            }
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] value = pointers.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            pointers.put(key, content);
            return true;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        // A pass-through serves a single blob write-then-read; the rest is never exercised.

        @Override
        public InputStream open(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** An input stream yielding {@code size} synthetic bytes in bounded chunks, never backed by an array of the whole
     *  length, so feeding it proves the consumer streams rather than allocating the full size. */
    private static final class SyntheticInputStream extends InputStream {

        private long remaining;

        private SyntheticInputStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int read = (int) Math.min(length, remaining);
            remaining -= read;
            return read;
        }
    }

    /** A {@link FormatExchange} that records the status and counts the response bytes, discarding them, so it can be
     *  fed a body far larger than memory. */
    private static final class CountingExchange implements FormatExchange {

        private final String path;
        private int status = -1;
        private long written;

        private CountingExchange(String path) {
            this.path = path;
        }

        int status() {
            return status;
        }

        long written() {
            return written;
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
            return new OutputStream() {
                @Override
                public void write(int b) {
                    written++;
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    written += len;
                }
            };
        }
    }
}
