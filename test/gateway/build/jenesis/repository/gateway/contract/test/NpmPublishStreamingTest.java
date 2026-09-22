package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the npm {@code npm publish} streams end to end: a publish document whose {@code _attachments} tarball
 * (base64 under {@code data}) decodes to more than the maximum size of a Java array is published without ever being
 * held whole in memory. Before this pass the publish {@code readNBytes(512 MiB + 1)}'d the whole body, built a Jackson
 * tree over it, pulled the tarball out as a base64 {@code String} and decoded that to another {@code byte[]} - four
 * copies, capping the upload at 512 MiB and {@code 413}ing (or OOMing) anything larger; now the body is parsed with
 * the streaming parser and each attachment's {@code data} is base64-decoded straight into the content-addressed store
 * hash-on-write, so a multi-gigabyte tarball that no heap could hold still publishes {@code 201} (the {@code }
 * {@code BoundedHeapPublishTest} mould, here for the npm publish path). Were any step to buffer the tarball (or its
 * base64) into a {@code byte[]}, materialising more than {@link Integer#MAX_VALUE} bytes would blow the array limit
 * and throw.
 */
class NpmPublishStreamingTest {

    private static final long CHUNK = 64 * 1024;

    @Test
    void a_tarball_larger_than_the_array_limit_is_published_streamed() throws IOException {
        // A base64 run of 'A' (each 'A' is six zero bits, so "AAAA" decodes to three zero bytes) long enough that both
        // the base64 text and the decoded tarball exceed Integer.MAX_VALUE: no single byte[] could hold either, so a
        // completing publish must have streamed the decode straight into the store.
        long base64Length = 3_000_000_000L;                 // a multiple of 4, so it decodes to whole base64 groups
        long decodedLength = base64Length / 4 * 3;          // 2_250_000_000 - past the array limit
        InputStream body = publishBody("streampkg", "1.0.0", base64Length);

        NpmStreamStore store = new NpmStreamStore();
        RepositoryFormat npm = discover("npm");
        PublishExchange exchange = new PublishExchange(body, "/npm/streampkg");

        npm.handle(exchange, store);

        assertThat(exchange.status).as("the package was published, not 413'd by a size cap").isEqualTo(201);
        assertThat(store.artifactStreamed())
                .as("every decoded byte of the tarball flowed through the store, beyond the array limit")
                .isEqualTo(decodedLength).isGreaterThan(Integer.MAX_VALUE);
        assertThat(store.largestChunk()).as("no single read ever held more than the copy buffer")
                .isLessThanOrEqualTo(CHUNK);
        assertThat(store.artifactWrites()).as("the tarball streamed through the store exactly once, never re-read")
                .isEqualTo(1);
        assertThat(store.pointer("npm/streampkg/tarballs/streampkg-1.0.0.tgz"))
                .as("the tarball pointer resolves to the streamed blob").isEqualTo(store.artifactHash());
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

    /** An npm publish document (one JSON object) whose {@code _attachments} tarball's {@code data} is {@code base64Len}
     *  synthetic base64 characters - streamed, never assembled into one string, so the whole exceeds the array limit.
     *  The small {@code versions}/{@code dist-tags} metadata is inline before it. */
    private static InputStream publishBody(String shortName, String version, long base64Len) {
        String prefix = "{\"_id\":\"" + shortName + "\",\"name\":\"" + shortName + "\","
                + "\"versions\":{\"" + version + "\":{\"name\":\"" + shortName + "\",\"version\":\"" + version + "\"}},"
                + "\"dist-tags\":{\"latest\":\"" + version + "\"},"
                + "\"_attachments\":{\"" + shortName + "-" + version + ".tgz\":"
                + "{\"content_type\":\"application/octet-stream\",\"data\":\"";
        String suffix = "\",\"length\":1}}}";
        return new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(prefix.getBytes(StandardCharsets.UTF_8)),
                new SyntheticInputStream(base64Len, (byte) 'A'),
                new ByteArrayInputStream(suffix.getBytes(StandardCharsets.UTF_8)))));
    }

    /** An input stream that yields {@code size} copies of {@code fill} in bounded chunks, never backed by an array of
     *  the whole length - feeding it proves the consumer streams rather than allocating the full size. */
    private static final class SyntheticInputStream extends InputStream {

        private long remaining;
        private final byte fill;

        private SyntheticInputStream(long size, byte fill) {
            this.remaining = size;
            this.fill = fill;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return fill & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int read = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + read, fill);
            remaining -= read;
            return read;
        }
    }

    /**
     * An artifact store that streams a blob through in bounded chunks, content-addressing it by the SHA-256 computed as
     * it reads (never buffering the whole body), and keeps only small blobs (the per-version metadata pointers) whole
     * while measuring the one unbounded (tarball) blob's total and largest chunk, so the test demonstrates the heap
     * stayed bounded.
     */
    private static final class NpmStreamStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> objects = new HashMap<>();
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
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[(int) CHUNK];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                largestChunk = Math.max(largestChunk, read);
                if (captured.size() < CHUNK) {
                    captured.write(buffer, 0, (int) Math.min(read, CHUNK - captured.size()));
                }
                total += read;
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (total > CHUNK) {
                // The unbounded tarball: keep only its front, never the whole body.
                artifactStreamed = total;
                artifactHash = hash;
                artifactWrites++;
            } else {
                objects.put("blobs/" + hash, captured.toByteArray());
            }
            return hash;
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes != null) {
                out.write(bytes);
            }
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key) || key.equals("blobs/" + artifactHash) || pointers.containsKey(key);
        }

        @Override
        public long size(String key) {
            if (key.equals("blobs/" + artifactHash)) {
                return artifactStreamed;
            }
            byte[] bytes = objects.get(key);
            return bytes == null ? -1L : bytes.length;
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
        public void write(String key, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            pointers.remove(key);
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

    /** A {@link FormatExchange} for a {@code PUT} publish, streaming the body and capturing the response status. */
    private static final class PublishExchange implements FormatExchange {

        private final InputStream body;
        private final String path;
        private int status = -1;

        private PublishExchange(InputStream body, String path) {
            this.body = body;
            this.path = path;
        }

        @Override
        public String method() {
            return "PUT";
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
