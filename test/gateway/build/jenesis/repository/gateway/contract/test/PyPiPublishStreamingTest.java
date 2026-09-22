package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the PyPI {@code twine upload} streams end to end: a multipart distribution whose {@code content} part is larger
 * than the maximum size of a Java array is published without ever being held whole in memory. Before this pass the
 * upload {@code readNBytes(512 MiB + 1)}'d the whole multipart body and copied the distribution again, capping the
 * upload at 512 MiB and {@code 413}ing (or OOMing) anything larger; now the {@code content} file part streams straight
 * into the content-addressed store hash-on-write while the small text fields ({@code name}, digests) are read whole - so
 * a multi-gigabyte wheel/sdist that no heap could hold still publishes {@code 200} (the 
 * {@code BoundedHeapPublishTest} mould, here for the PyPI publish path). Were any step to buffer the body into a
 * {@code byte[]}, materialising more than {@link Integer#MAX_VALUE} bytes would blow the array limit and throw.
 */
class PyPiPublishStreamingTest {

    private static final long CHUNK = 64 * 1024;
    private static final String BOUNDARY = "BoUnDaRyPyPiStreamTest";

    @Test
    void a_distribution_larger_than_the_array_limit_is_published_streamed() throws IOException {
        // The content part is a synthetic body exceeding Integer.MAX_VALUE: no single byte[] could hold it, so a
        // completing upload must have streamed it (PyPI never cracks the distribution - the project name comes from a
        // form field, not the archive - so the bytes need not be a real wheel).
        long size = (long) Integer.MAX_VALUE + (1L << 20);
        InputStream body = multipart("StreamPkg", "streampkg-1.0.0-py3-none-any.whl", size);

        PyPiStreamStore store = new PyPiStreamStore();
        RepositoryFormat pypi = discover("pypi");
        UploadExchange exchange = new UploadExchange(body, "multipart/form-data; boundary=" + BOUNDARY);

        pypi.handle(exchange, store);

        assertThat(exchange.status).as("the distribution was published, not 413'd by a size cap").isEqualTo(200);
        assertThat(store.artifactStreamed())
                .as("every byte of the distribution flowed through the store, beyond the array limit")
                .isEqualTo(size).isGreaterThan(Integer.MAX_VALUE);
        assertThat(store.largestChunk()).as("no single read ever held more than the copy buffer")
                .isLessThanOrEqualTo(CHUNK);
        assertThat(store.artifactWrites()).as("the distribution streamed through the store exactly once, never re-read")
                .isEqualTo(1);
        assertThat(store.pointer("pypi/streampkg/files/streampkg-1.0.0-py3-none-any.whl"))
                .as("the distribution pointer resolves to the streamed blob").isEqualTo(store.artifactHash());
    }

    /** The discovered {@link RepositoryFormat} of the given name (the modules are not exported, so a test
     *  reaches them through the same {@link ServiceLoader} seam the server uses). */
    private static RepositoryFormat discover(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    /** A twine-shaped multipart body: a small {@code name} field, then a {@code content} file part of {@code contentSize}
     *  synthetic bytes - streamed, never assembled into one array, so the whole exceeds the array limit. */
    private static InputStream multipart(String project, String filename, long contentSize) {
        byte[] name = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + project + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] contentHeader = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] trailer = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(name),
                new ByteArrayInputStream(contentHeader),
                new SyntheticInputStream(contentSize),
                new ByteArrayInputStream(trailer))));
    }

    /** An input stream that yields {@code size} synthetic zero bytes in bounded chunks, never backed by an array of the
     *  whole length - feeding it proves the consumer streams rather than allocating the full size. */
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

    /**
     * An artifact store that streams a blob through in bounded chunks, content-addressing it by the SHA-256 computed as
     * it reads (never buffering the whole body), and keeps the small pointer objects in memory. The accounting records
     * the artifact's total and largest chunk so the test demonstrates the heap stayed bounded.
     */
    private static final class PyPiStreamStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> pointers = new HashMap<>();
        private long artifactStreamed = -1;
        private long largestChunk;
        private int artifactWrites;
        private String artifactHash;

        long artifactStreamed() {
            return artifactStreamed;
        }

        long largestChunk() {
            return largestChunk;
        }

        int artifactWrites() {
            return artifactWrites;
        }

        String artifactHash() {
            return artifactHash;
        }

        String pointer(String key) {
            byte[] value = pointers.get(key);
            return value == null ? null : ServableNames.hash(value);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[(int) CHUNK];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                largestChunk = Math.max(largestChunk, read);
                total += read;
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            artifactStreamed = total;
            artifactHash = hash;
            artifactWrites++;
            return hash;
        }

        @Override
        public boolean exists(String key) {
            return key.equals("blobs/" + artifactHash) || pointers.containsKey(key);
        }

        @Override
        public long size(String key) {
            return key.equals("blobs/" + artifactHash) ? artifactStreamed : -1L;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] value = pointers.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            if (pointers.get(key) != expected) {
                return false;
            }
            pointers.put(key, content);
            return true;
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        /** The discovered publish-interceptor chain re-opens the stored blob to assess it ((a) moved the screen
         *  onto the distribution itself), so this double must answer rather than throw. It answers with an empty
         *  stream, never the multi-gigabyte body: a screen reads a bounded window, and materialising the artifact here
         *  would defeat the very property this test proves. The write accounting above is untouched, so
         *  {@code artifactWrites() == 1} still pins "streamed through the store exactly once, never re-read". */
        @Override
        public InputStream open(String key) {
            return InputStream.nullInputStream();
        }

        @Override
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** A {@link FormatExchange} for a {@code POST /pypi/} twine upload, streaming the body and capturing the status. */
    private static final class UploadExchange implements FormatExchange {

        private final InputStream body;
        private final String contentType;
        private int status = -1;

        private UploadExchange(InputStream body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String path() {
            return "/pypi/";
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return name.equalsIgnoreCase("Content-Type") ? contentType : null;
        }

        @Override
        public InputStream requestStream() {
            return body;
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return OutputStream.nullOutputStream();
        }
    }
}
