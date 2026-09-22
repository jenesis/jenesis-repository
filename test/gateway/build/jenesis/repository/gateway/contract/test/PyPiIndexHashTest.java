package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the PyPI Simple index serve is read-first and bounded: the {@code #sha256} fragment pip verifies each
 * distribution against is read straight off the small content-addressed pointer the store already keyed the blob under,
 * so the index is generated without pulling a single wheel body back into memory to re-hash it. Before this pass the
 * index re-read and re-hashed every distribution file on every {@code pip install} - an O(files x file-size) read+digest
 * on the hottest PyPI path; the counting store here demonstrates the serve touches no blob body at all, while the served
 * page still carries the correct SHA-256.
 */
class PyPiIndexHashTest {

    @Test
    void the_simple_index_reads_the_sha256_off_the_pointer_without_reading_any_wheel() throws IOException {
        byte[] wheel = new byte[512 * 1024];
        for (int index = 0; index < wheel.length; index++) {
            wheel[index] = (byte) (index * 31 + 7);
        }
        String expected = sha256(wheel);

        CountingReadStore store = new CountingReadStore();
        new Blobs(store).write("pypi/demo/files/demo-1.0.0-py3-none-any.whl", wheel);
        // The project index now serves only for a hosted project (the index-shadowing fix): stamp the
        // hosted-publish marker a real upload/import would, so this hosted repo's index is served rather than missed.
        store.writeVersioned("pypi/demo/.hosted", "1".getBytes(StandardCharsets.UTF_8), null);
        store.resetReads();

        RepositoryFormat pypi = discover("pypi");
        CapturingIndex exchange = new CapturingIndex("/pypi/simple/demo/");
        pypi.handle(exchange, store);

        assertThat(exchange.status).as("the index was served").isEqualTo(200);
        assertThat(exchange.body()).as("the page carries the wheel's real SHA-256, read off the pointer")
                .contains("demo-1.0.0-py3-none-any.whl#sha256=" + expected);
        assertThat(store.blobReads()).as("no wheel body was read or reopened to build the index")
                .isZero();
    }

    private static RepositoryFormat discover(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An in-memory {@link ArtifactStore} that counts the blob-body reads ({@code read} / {@code open} on a {@code
     *  blobs/} key) made through it, so a serve can assert it touched no artifact body. */
    private static final class CountingReadStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, byte[]> pointers = new HashMap<>();
        private int blobReads;

        int blobReads() {
            return blobReads;
        }

        void resetReads() {
            blobReads = 0;
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            byte[] bytes = in.readAllBytes();
            String hash = sha256(bytes);
            objects.put("blobs/" + hash, bytes);
            return hash;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            if (key.startsWith("blobs/")) {
                blobReads++;
            }
            byte[] bytes = objects.get(key);
            if (bytes != null) {
                out.write(bytes);
            }
        }

        @Override
        public InputStream open(String key) {
            if (key.startsWith("blobs/")) {
                blobReads++;
            }
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key) || pointers.containsKey(key);
        }

        @Override
        public long size(String key) {
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
            String scoped = prefix.endsWith("/") ? prefix : prefix + "/";
            Set<String> children = new TreeSet<>();
            for (String key : pointers.keySet()) {
                if (key.startsWith(scoped)) {
                    String tail = key.substring(scoped.length());
                    int slash = tail.indexOf('/');
                    children.add(slash < 0 ? tail : tail.substring(0, slash));
                }
            }
            return new ArrayList<>(children);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            objects.put(key, in.readAllBytes());
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            pointers.remove(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** A {@link FormatExchange} for a {@code GET} that captures the response status and body. */
    private static final class CapturingIndex implements FormatExchange {

        private final String path;
        private int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private CapturingIndex(String path) {
            this.path = path;
        }

        String body() {
            return captured.toString(StandardCharsets.UTF_8);
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
            return captured;
        }
    }
}
