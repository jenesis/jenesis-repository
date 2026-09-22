package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the RubyGems {@code gem push} streams end to end: a {@code .gem} whose body is larger than the maximum size of
 * a Java array is published without ever being held whole in memory. The gem's metadata ({@code metadata.gz}, the first
 * tar entry) is small and legitimately read, but the artifact itself flows straight through the content-addressed store
 * hash-on-write; were the publish to buffer the body into a {@code byte[]} - the {@code readAllBytes()} it used before
 * this pass - materialising more than {@link Integer#MAX_VALUE} bytes would blow the array limit and throw. That the
 * publish completes, having streamed every byte through in bounded chunks, is the bounded-heap guarantee (the 
 * {@code BoundedHeapPublishTest} mould, here for the format's own publish path). It also proves the compact-index
 * checksum the client verifies is exactly the SHA-256 the store computed while streaming - taken once, not by re-reading
 * and re-hashing the stored artifact.
 */
class GemPublishStreamingTest {

    private static final long CHUNK = 64 * 1024;

    @Test
    void a_gem_larger_than_the_array_limit_is_published_streamed() throws IOException {
        // A valid small .gem (metadata.gz first), then a synthetic tail so the whole body exceeds Integer.MAX_VALUE:
        // no single byte[] could hold it, so a completing publish must have streamed it. The tar's front is all the
        // gemspec parse needs; the trailing bytes are never pulled back into memory.
        byte[] front = gem("hello", "1.0.0");
        long size = (long) front.length + (long) Integer.MAX_VALUE + (1L << 20);
        InputStream body = new SequenceInputStream(
                new ByteArrayInputStream(front), new SyntheticInputStream(size - front.length));

        GemStreamStore store = new GemStreamStore();
        RepositoryFormat gems = discover("rubygems");
        PushExchange exchange = new PushExchange(body);

        gems.handle(exchange, store);

        assertThat(exchange.status).as("the gem was published").isEqualTo(200);
        assertThat(store.artifactStreamed()).as("every byte of the gem flowed through the store, beyond the array limit")
                .isEqualTo(size).isGreaterThan(Integer.MAX_VALUE);
        assertThat(store.largestChunk()).as("no single read ever held more than the copy buffer")
                .isLessThanOrEqualTo(CHUNK);
        assertThat(store.artifactWrites()).as("the artifact streamed through the store exactly once, never re-read")
                .isEqualTo(1);
        // The .gem pointer names the streamed blob, and the compact-index line's checksum is that same content address -
        // the SHA-256 taken as the body streamed in, reused rather than a second full-artifact hash pass.
        String hash = store.pointer("rubygemfiles/hello-1.0.0.gem");
        assertThat(hash).as("the .gem pointer resolves to the streamed blob").isEqualTo(store.artifactHash());
        assertThat(store.line("rubygems/hello/versions/1.0.0"))
                .as("the compact-index checksum is the streamed SHA-256").contains("checksum:" + hash);
    }

    @Test
    void a_runtime_dependency_name_with_a_control_char_is_refused_before_any_index_line_is_written() throws IOException {
        // A runtime-dependency name flows unescaped into the compact-index /info line (<version> <deps>|<reqs>, one
        // version per newline). A gemspec whose dependency name carries a newline would splice a spurious version line
        // into that gem's /info and skew the shared /versions md5 computed over it. The push must be refused (400)
        // before anything is stored - the gemspec is attacker-supplied YAML.
        String gemspec = "--- !ruby/object:Gem::Specification\n"
                + "name: victim\n"
                + "version: !ruby/object:Gem::Version\n  version: 1.0.0\n"
                + "licenses:\n- MIT\n"
                + "dependencies:\n"
                + "- !ruby/object:Gem::Dependency\n"
                + "  type: :runtime\n"
                + "  name: \"rack\\ninjected 9.9.9\"\n";   // double-quoted YAML: \n decodes to a real newline in the name
        GemStreamStore store = new GemStreamStore();
        RepositoryFormat gems = discover("rubygems");
        PushExchange exchange = new PushExchange(new ByteArrayInputStream(gem(gemspec)));

        gems.handle(exchange, store);

        assertThat(exchange.status).as("a control-char runtime-dependency name is refused").isEqualTo(400);
        assertThat(store.pointer("rubygemfiles/victim-1.0.0.gem"))
                .as("nothing was published - no .gem pointer and so no compact-index line").isNull();
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

    /** A minimal .gem: a tar carrying a metadata.gz whose gzipped YAML is the gemspec the format reads. */
    private static byte[] gem(String name, String version) throws IOException {
        return gem("--- !ruby/object:Gem::Specification\n"
                + "name: " + name + "\n"
                + "version: !ruby/object:Gem::Version\n  version: " + version + "\n"
                + "licenses:\n- MIT\n");
    }

    /** A .gem carrying an arbitrary gemspec YAML - the seam a test uses to drive a crafted dependency block. */
    private static byte[] gem(String gemspec) throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(gemspec.getBytes(StandardCharsets.UTF_8));
        }
        byte[] metadata = gzipped.toByteArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("metadata.gz");
            entry.setSize(metadata.length);
            tar.putArchiveEntry(entry);
            tar.write(metadata);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
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
     * parse its metadata - plus the small subsequent blobs (the compact-index line, the quick spec) whole. The
     * accounting records the artifact's total and largest chunk so the test demonstrates the heap stayed bounded.
     */
    private static final class GemStreamStore implements ArtifactStore {
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

        String line(String key) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            read("blobs/" + pointer(key), out);
            return out.toString(StandardCharsets.UTF_8);
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
                // The unbounded artifact: keep only its front (all a metadata reopen needs), never the whole body.
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

    /** A {@link FormatExchange} for a {@code POST /rubygems/api/v1/gems} push, streaming the body and capturing the
     *  response status. */
    private static final class PushExchange implements FormatExchange {

        private final InputStream body;
        private int status = -1;

        private PushExchange(InputStream body) {
            this.body = body;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String path() {
            return "/rubygems/api/v1/gems";
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
