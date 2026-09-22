package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the NuGet {@code dotnet nuget push} streams end to end: a multipart {@code .nupkg} whose body is larger than the
 * maximum size of a Java array is published without ever being held whole in memory. Before this pass the push
 * {@code readNBytes(512 MiB + 1)}'d the multipart body and copied the package again, capping the upload at 512 MiB and
 * {@code 413}ing (or OOMing) anything larger; now the multipart file part streams straight into the content-addressed
 * store hash-on-write, and only the stored blob's front (the {@code .nuspec}) is reopened to read the id and version -
 * so a multi-gigabyte package that no heap could hold still publishes {@code 201} (the 
 * {@code BoundedHeapPublishTest} mould, here for the NuGet publish path). Were any step to buffer the body into a
 * {@code byte[]}, materialising more than {@link Integer#MAX_VALUE} bytes would blow the array limit and throw.
 */
class NuGetPublishStreamingTest {

    private static final long CHUNK = 64 * 1024;
    private static final String BOUNDARY = "BoUnDaRyNuGetStreamTest";

    @Test
    void a_nupkg_larger_than_the_array_limit_is_published_streamed() throws IOException {
        // A valid small .nupkg (a zip whose first entry is the .nuspec) followed by a synthetic tail so the multipart
        // file part exceeds Integer.MAX_VALUE: no single byte[] could hold it, so a completing publish must have
        // streamed it. The zip's front is all the coordinate parse needs; the trailing bytes are never pulled back.
        byte[] nupkg = nupkg("StreamPkg", "1.0.0");
        long partSize = (long) nupkg.length + (long) Integer.MAX_VALUE + (1L << 20);
        InputStream body = multipart(nupkg, partSize - nupkg.length, "StreamPkg.1.0.0.nupkg");

        NuGetStreamStore store = new NuGetStreamStore();
        RepositoryFormat nuget = discover("nuget");
        PushExchange exchange = new PushExchange(body,
                "multipart/form-data; boundary=" + BOUNDARY, "/nuget/v3/package");

        nuget.handle(exchange, store);

        assertThat(exchange.status).as("the package was published, not 413'd by a size cap").isEqualTo(201);
        assertThat(store.artifactStreamed())
                .as("every byte of the .nupkg flowed through the store, beyond the array limit")
                .isEqualTo(partSize).isGreaterThan(Integer.MAX_VALUE);
        assertThat(store.largestChunk()).as("no single read ever held more than the copy buffer")
                .isLessThanOrEqualTo(CHUNK);
        assertThat(store.artifactWrites()).as("the package streamed through the store exactly once, never re-read")
                .isEqualTo(1);
        assertThat(store.pointer("nuget/streampkg/1.0.0/streampkg.1.0.0.nupkg"))
                .as("the .nupkg pointer resolves to the streamed blob").isEqualTo(store.artifactHash());
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

    /** A minimal .nupkg: a zip whose first entry is the {@code .nuspec} the push reads the id/version from. */
    private static byte[] nupkg(String id, String version) throws IOException {
        String nuspec = "<?xml version=\"1.0\"?>\n<package><metadata>"
                + "<id>" + id + "</id><version>" + version + "</version></metadata></package>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(id + ".nuspec"));
            zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** A multipart/form-data body whose single file part is {@code front} followed by {@code tail} synthetic bytes -
     *  streamed, never assembled into one array, so the whole exceeds the array limit. */
    private static InputStream multipart(byte[] front, long tail, String filename) {
        byte[] header = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"package\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] trailer = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(header),
                new ByteArrayInputStream(front),
                new SyntheticInputStream(tail),
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
     * it reads (never buffering the whole body), and keeps only the front of the first (artifact) blob so a reopen can
     * parse its {@code .nuspec} - plus the small subsequent blobs (the dependency sidecar) whole. The accounting records
     * the artifact's total and largest chunk so the test demonstrates the heap stayed bounded.
     */
    private static final class NuGetStreamStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, byte[]> pointers = new HashMap<>();
        private long artifactStreamed = -1;
        private long largestChunk;
        private int artifactWrites;
        private byte[] artifactFront;
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
                // The unbounded artifact: keep only its front (all a .nuspec reopen needs), never the whole body.
                artifactStreamed = total;
                artifactFront = captured.toByteArray();
                artifactHash = hash;
                artifactWrites++;
            } else {
                objects.put("blobs/" + hash, captured.toByteArray());
            }
            return hash;
        }

        @Override
        public InputStream open(String key) {
            if (key.equals("blobs/" + artifactHash)) {
                return new ByteArrayInputStream(artifactFront);
            }
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

    /** A {@link FormatExchange} for a {@code PUT} multipart push, streaming the body and capturing the response status. */
    private static final class PushExchange implements FormatExchange {

        private final InputStream body;
        private final String contentType;
        private final String path;
        private int status = -1;

        private PushExchange(InputStream body, String contentType, String path) {
            this.body = body;
            this.contentType = contentType;
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
