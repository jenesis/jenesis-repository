package build.jenesis.repository.gateway.testkit;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The shared in-process driver the format regression cells use: a {@link ServiceLoader}-discovered format is handed a
 * {@link Call} (a capturing {@link FormatExchange}) over a {@link MemStore} (a minimal in-memory content-addressed
 * store), so a test drives a format's protocol directly - the same seam {@code PointerRetryTest} and
 * {@code PointerKeyInjectionTest} use, factored out here since several cells need it. A test double, never a backend.
 */
public final class FormatDrive {

    private FormatDrive() {
    }

    /** The discovered {@link RepositoryFormat} of the given name - the formats are not exported, so a test
     *  reaches them through the same {@link ServiceLoader} seam the server uses (the module declares {@code uses}). */
    public static RepositoryFormat format(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A {@link FormatExchange} that supplies a fixed request (method, path, headers, body) and captures the response
     *  status, headers and body bytes. */
    public static final class Call implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] request;
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        /** The deployment settings this exchange answers {@link #setting(String)} from - the seam a format reads a
         *  runtime dial through ({@code maven-metadata-compute}, {@code proxy-allow-internal}). Empty by default, so a
         *  cell that says nothing sees the shipped default of every dial, which for a guard means the guard on. */
        private final Map<String, String> settings = new LinkedHashMap<>();
        public final Map<String, String> responseHeaders = new LinkedHashMap<>();
        public int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        public Call(String method, String path, byte[] request) {
            this.method = method;
            this.path = path;
            this.request = request;
        }

        public Call(String method, String path) {
            this(method, path, new byte[0]);
        }

        public Call header(String name, String value) {
            requestHeaders.put(name, value);
            return this;
        }

        /** Set the deployment value of one runtime setting for this exchange. */
        public Call setting(String key, String value) {
            settings.put(key, value);
            return this;
        }

        @Override
        public String setting(String key) {
            return settings.get(key);
        }

        public byte[] body() {
            return captured.toByteArray();
        }

        public String responseHeader(String name) {
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public String method() {
            return method;
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
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(request);
        }

        @Override
        public void setResponseHeader(String name, String value) {
            responseHeaders.put(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return captured;
        }
    }

    /** A minimal in-memory content-addressed store: blobs keyed by hash, raw objects and pointers by key, pointers
     *  written through compare-and-set. Mirrors the doubles in {@code PointerRetryTest}/{@code PointerKeyInjectionTest},
     *  shared here so several cells reuse it. */
    public static class MemStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }

        public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        public final Map<String, byte[]> pointers = new ConcurrentHashMap<>();

        @Override
        public String writeBlob(InputStream in) throws IOException {
            byte[] bytes = in.readAllBytes();
            String hash = sha256(bytes);
            objects.put("blobs/" + hash, bytes);
            return hash;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            objects.put(key, in.readAllBytes());
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key) || pointers.containsKey(key);
        }

        /** One key space, as every real backend has: a versioned write is readable as a stream and vice versa. */
        private byte[] stored(String key) {
            byte[] bytes = objects.get(key);
            return bytes != null ? bytes : pointers.get(key);
        }

        @Override
        public long size(String key) {
            byte[] bytes = stored(key);
            return bytes == null ? -1L : bytes.length;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            byte[] bytes = stored(key);
            if (bytes != null) {
                out.write(bytes);
            }
        }

        @Override
        public InputStream open(String key) {
            byte[] bytes = stored(key);
            return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] value = pointers.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public synchronized boolean writeVersioned(String key, byte[] content, Object expected) {
            if (pointers.get(key) != expected) {
                return false;
            }
            pointers.put(key, content);
            return true;
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            pointers.remove(key);
        }

        @Override
        public List<String> list(String prefix) {
            String scoped = prefix.endsWith("/") ? prefix : prefix + "/";
            Set<String> children = new TreeSet<>();
            for (String key : objects.keySet()) {
                child(scoped, key, children);
            }
            for (String key : pointers.keySet()) {
                child(scoped, key, children);
            }
            return new ArrayList<>(children);
        }

        private static void child(String scoped, String key, Set<String> children) {
            if (key.startsWith(scoped)) {
                String tail = key.substring(scoped.length());
                int slash = tail.indexOf('/');
                children.add(slash < 0 ? tail : tail.substring(0, slash));
            }
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
