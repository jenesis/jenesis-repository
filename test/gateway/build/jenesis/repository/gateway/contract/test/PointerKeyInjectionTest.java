package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves path injection cannot reach the object store through the content-addressed pointer namespace. A language
 * format splices caller-supplied coordinates (an npm version, a PyPI filename, a Debian architecture) into the pointer
 * key it writes through {@link Blobs}, so a forged {@code ..} traversal, an absolute path, a backslash or a control
 * character in one of those values would otherwise escape the intended coordinate and overwrite (or, on the filesystem
 * backend, traverse out of) another artifact's bytes. Two lines of defence are asserted here: the generic guard at the
 * {@link Blobs} write choke point ({@code write}/{@code link}/{@code delete}), which every blobs-namespace format
 * inherits so a format that forgot to validate still cannot smuggle a bad key past it; and a representative format's
 * own front-door rejection (npm), which turns the same attempt into a clean {@code 400} before any blob is stored.
 */
class PointerKeyInjectionTest {

    private static final List<String> UNSAFE_KEYS = List.of(
            "npm/pkg/../../../etc/passwd",
            "npm/pkg/versions/..",
            "npm/pkg/versions/.",
            "/absolute/pointer",
            "npm//pkg/versions/1.0.0",
            "npm/pkg/versions/a\\b",
            "npm/pkg/versions/a\nb",
            "");

    @Test
    void the_blobs_choke_point_rejects_every_unsafe_key() {
        Blobs blobs = new Blobs(new InMemoryStore());
        for (String key : UNSAFE_KEYS) {
            assertThatThrownBy(() -> blobs.write(key, "x".getBytes(StandardCharsets.UTF_8)))
                    .as("write rejects unsafe key <" + key + ">")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> blobs.link(key, "0".repeat(64)))
                    .as("link rejects unsafe key <" + key + ">")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> blobs.delete(key))
                    .as("delete rejects unsafe key <" + key + ">")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void the_blobs_choke_point_admits_and_round_trips_a_safe_key() throws IOException {
        InMemoryStore store = new InMemoryStore();
        Blobs blobs = new Blobs(store);
        byte[] payload = "the published bytes".getBytes(StandardCharsets.UTF_8);
        blobs.write("npm/pkg/versions/1.0.0", payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(blobs.read("npm/pkg/versions/1.0.0", out)).as("a safe key round-trips").isTrue();
        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void an_npm_publish_with_a_traversal_version_is_refused() throws IOException {
        RepositoryFormat npm = discover("npm");
        InMemoryStore store = new InMemoryStore();
        String body = "{\"versions\":{\"../../evil\":{\"name\":\"pkg\",\"version\":\"../../evil\"}}}";
        BodyExchange publish = new BodyExchange("PUT", "/npm/pkg", body.getBytes(StandardCharsets.UTF_8));
        npm.handle(publish, store);
        assertThat(publish.status).as("a body-forged version is refused at the front door").isEqualTo(400);
        assertThat(store.pointers.keySet()).as("no pointer is written for the forged coordinate").isEmpty();
    }

    /**
     * The enumeration-side twin ({@link Blobs#withheld}) must be fail-closed on a hostile pointer key, not just the
     * write choke point. The npm packument's dist-tags screen splices a STORED dist-tag target into a tarball pointer
     * key ({@code NpmFormat.distTags -> withheldVersion -> blobs.withheld}); a target carrying a NUL yields a key the
     * filesystem store's {@code resolve()} cannot even map ({@link java.nio.file.InvalidPathException}). The former
     * hand-rolled {@code hash(key)} did an UNWRAPPED {@code readVersioned(key)} ahead of the fail-closed marker probe,
     * so that {@link RuntimeException} escaped as an uncaught 500 on every later packument GET (a persistent
     * unauthenticated DoS). Routed through the servable-name seam's {@code disclosableKey}, a hostile key is treated as
     * withheld/undisclosable - never thrown - while every normal case (absent / servable / withheld) is unchanged.
     */
    @Test
    void withheld_is_fail_closed_on_a_hostile_pointer_key_and_preserves_the_normal_contract() throws IOException {
        InMemoryStore store = new InMemoryStore();
        Blobs blobs = new Blobs(store);

        String key = "npm/pkg/tarballs/pkg-1.0.0.tgz";
        String hash = blobs.store(new ByteArrayInputStream("tarball".getBytes(StandardCharsets.UTF_8)));
        blobs.link(key, hash);
        assertThat(blobs.withheld(key)).as("a servable pointer is not withheld").isFalse();
        assertThat(blobs.withheld("npm/pkg/tarballs/absent.tgz"))
                .as("an absent pointer lists nothing to screen").isFalse();
        store.writeVersioned("withheld/" + hash, new byte[0], null);   // mark the blob withheld (a withheld/<hash> pointer)
        assertThat(blobs.withheld(key)).as("a withheld-marked blob screens as withheld").isTrue();

        // A dist-tag target with a NUL -> a hostile tarball pointer key the store's resolve() cannot map
        // (InvalidPathException, as FilesystemArtifactStore.resolve throws on a real backend). It must fail-closed to
        // withheld, never propagate the RuntimeException as an uncaught 500 on the packument path.
        String hostile = "npm/pkg/tarballs/pkg-1.0\u0000.tgz";
        assertThatCode(() -> assertThat(blobs.withheld(hostile))
                .as("a hostile pointer key is fail-closed to withheld").isTrue())
                .as("the hostile key must not escape as an uncaught RuntimeException (a 500)")
                .doesNotThrowAnyException();
    }

    private static RepositoryFormat discover(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    /** A minimal in-memory content-addressed store: blobs keyed by hash, pointers written through compare-and-set. */
    private static final class InMemoryStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final Map<String, byte[]> pointers = new ConcurrentHashMap<>();

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

        @Override
        public long size(String key) {
            byte[] bytes = objects.get(key);
            return bytes == null ? -1L : bytes.length;
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes != null) {
                out.write(bytes);
            }
        }

        @Override
        public InputStream open(String key) {
            byte[] bytes = objects.get(key);
            return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            resolve(key);   // a real backend maps the key to a path here; a NUL/hostile key cannot be mapped
            byte[] value = pointers.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        /** Mirror {@code FilesystemArtifactStore.resolve}: a key a real path backend cannot map (a NUL or other
         *  encoding-hostile character) raises {@link java.nio.file.InvalidPathException} - a {@link RuntimeException}
         *  the fail-closed servable-name seam must contain rather than let escape as a 500. */
        private static void resolve(String key) {
            if (key.indexOf('\0') >= 0) {
                throw new java.nio.file.InvalidPathException(key, "Nul character not allowed");
            }
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

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** A {@link FormatExchange} that supplies a fixed request body and captures the response status and bytes. */
    private static final class BodyExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] request;
        private int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private BodyExchange(String method, String path, byte[] request) {
            this.method = method;
            this.path = path;
            this.request = request;
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
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(request);
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
