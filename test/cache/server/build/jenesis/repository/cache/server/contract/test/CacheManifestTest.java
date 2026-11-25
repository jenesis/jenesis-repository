package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.Cache;
import build.jenesis.repository.cache.server.CacheController;
import build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The requests a build of this tool makes, on the real request path - {@link CacheController} over a {@link Cache} over
 * the delegating storage: a step's manifest is stored only while the project holds every file it names, the files go
 * up and come down in batches named by their digests, and neither a wrongly named file nor another project's file
 * passes.
 */
class CacheManifestTest {

    private static final String KEY = Authorization.mint("acme");
    private static final String STEP = "0123456789abcdef0123456789abcdef";
    private static final String INPUTS = "fedcba9876543210fedcba9876543210";

    @TempDir
    private Path root;

    private CacheController node;

    @BeforeEach
    void arrange() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        Authorization.enforcing(store).setGrant("acme", Authorization.hash(KEY), "*", "cache:read,cache:write");
        node = new CacheController(new Cache(new DelegatingCacheStorage(store.scope(Scopes.SYSTEM).scope(Scopes.CACHE)),
                Authorization.enforcing(store), 1L << 31, 256, null, 0, 0, "default", false, null, "default",
                new SimpleMeterRegistry())
                .policies((tenant, project) -> StoredSettings.projectChain(store, tenant, project)));
    }

    @Test
    void a_manifest_is_stored_once_the_project_holds_every_file_it_names() throws IOException {
        byte[] manifest = (sha256("one") + " 3 classes/One.class\n- 0 empty\n" + sha256("two") + " 3 two.jar\n").getBytes(UTF_8);
        Answer lacking = call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", manifest);
        assertThat(lacking.status()).isEqualTo(409);
        assertThat(lacking.text()).as("the files to upload, one digest per line").isEqualTo(sha256("one") + "\n" + sha256("two"));
        assertThat(call("GET", "/step/" + STEP + "/" + INPUTS, "demo", null).status())
                .as("a manifest whose files are not all held is not stored, so no build restores half a step")
                .isEqualTo(404);

        assertThat(call("PUT", "/blobs", "demo", zip(Map.of(sha256("one"), "one"))).status()).isEqualTo(204);
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", manifest).text())
                .as("only what is still lacking is asked for")
                .isEqualTo(sha256("two"));
        assertThat(call("PUT", "/blobs", "demo", zip(Map.of(sha256("two"), "two"))).status()).isEqualTo(204);
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", manifest).status()).isEqualTo(201);
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", manifest).status())
                .as("a manifest the project holds, with its files, is not written again")
                .isEqualTo(204);

        Answer stored = call("GET", "/step/" + STEP + "/" + INPUTS, "demo", null);
        assertThat(stored.status()).isEqualTo(200);
        assertThat(stored.body()).isEqualTo(manifest);
        assertThat(call("HEAD", "/step/" + STEP + "/" + INPUTS, "demo", null).status()).isEqualTo(200);
    }

    @Test
    void the_files_a_build_asks_for_come_as_one_zip_without_those_the_project_lacks() throws IOException {
        call("PUT", "/blobs", "demo", zip(Map.of(sha256("one"), "one", sha256("two"), "two")));
        Answer answer = call("POST", "/blobs", "demo",
                (sha256("two") + "\n" + sha256("absent") + "\n" + sha256("one") + "\nnot a digest\n").getBytes(UTF_8));
        assertThat(answer.status()).isEqualTo(200);
        assertThat(unzip(answer.body())).containsExactly(
                Map.entry(sha256("two"), "two"),
                Map.entry(sha256("one"), "one"));
    }

    @Test
    void a_file_uploaded_under_another_name_ends_the_upload_and_is_not_stored() throws IOException {
        SequencedMap<String, String> files = new LinkedHashMap<>();
        files.put(sha256("good"), "good");
        files.put(sha256("trusted"), "planted");
        files.put(sha256("after"), "after");
        assertThat(call("PUT", "/blobs", "demo", zip(files)).status()).isEqualTo(400);
        assertThat(unzip(call("POST", "/blobs", "demo",
                (sha256("good") + "\n" + sha256("trusted") + "\n" + sha256("after")).getBytes(UTF_8)).body()))
                .as("what came before the wrongly named file is what its name says and stays")
                .containsOnlyKeys(sha256("good"));
        assertThat(call("PUT", "/blobs", "demo", zip(Map.of("../escape", "x"))).status()).isEqualTo(400);
    }

    @Test
    void a_project_is_neither_served_nor_confirmed_another_projects_files() throws IOException {
        byte[] manifest = (sha256("one") + " 3 file\n").getBytes(UTF_8);
        call("PUT", "/blobs", "demo", zip(Map.of(sha256("one"), "one")));
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", manifest).status()).isEqualTo(201);
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "other", manifest).status())
                .as("a build that knows a digest does not learn that another project built the file")
                .isEqualTo(409);
        assertThat(unzip(call("POST", "/blobs", "other", sha256("one").getBytes(UTF_8)).body())).isEmpty();
        assertThat(call("GET", "/step/" + STEP + "/" + INPUTS, "other", null).status()).isEqualTo(404);
    }

    @Test
    void a_body_that_is_no_manifest_and_a_digest_of_no_known_algorithm_are_refused() throws IOException {
        assertThat(call("PUT", "/step/" + STEP + "/" + INPUTS, "demo", "PK not a manifest".getBytes(UTF_8)).status())
                .isEqualTo(400);
        HttpServletRequest request = request("PUT", "/step/" + STEP + "/" + INPUTS, "demo", "- 0 empty\n".getBytes(UTF_8));
        when(request.getHeader("Jenesis-Cache-Digest")).thenReturn("CRC32");
        int[] status = {200};
        node.dispatch(request, response(status, new ByteArrayOutputStream()));
        assertThat(status[0]).isEqualTo(400);
    }

    @Test
    void a_step_is_not_one_entry_and_each_shape_takes_only_its_methods() throws IOException {
        assertThat(call("GET", "/" + STEP + "/" + INPUTS, "demo", null).status()).isEqualTo(404);
        assertThat(call("POST", "/step/" + STEP + "/" + INPUTS, "demo", new byte[0]).status()).isEqualTo(405);
        assertThat(call("GET", "/blobs", "demo", null).status()).isEqualTo(405);
    }

    private Answer call(String method, String path, String project, byte[] body) throws IOException {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        int[] status = {200};
        node.dispatch(request(method, path, project, body), response(status, answer));
        return new Answer(status[0], answer.toByteArray());
    }

    private record Answer(int status, byte[] body) {

        String text() {
            return new String(body, UTF_8);
        }
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static SequencedMap<String, String> unzip(byte[] bytes) throws IOException {
        SequencedMap<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), new String(zip.readAllBytes(), UTF_8));
            }
        }
        return files;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static HttpServletRequest request(String method, String path, String project, byte[] body) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn("/build/acme" + path);
        when(request.getContextPath()).thenReturn("");
        when(request.getHeader("Jenesis-Cache-Key")).thenReturn(KEY);
        when(request.getHeader("Jenesis-Cache-Project")).thenReturn(project);
        when(request.getContentLengthLong()).thenReturn(body == null ? -1L : body.length);
        if (body != null) {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            when(request.getInputStream()).thenReturn(new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }
            });
        }
        return request;
    }

    private static HttpServletResponse response(int[] status, ByteArrayOutputStream body) throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        doAnswer(call -> status[0] = call.getArgument(0)).when(response).setStatus(anyInt());
        doAnswer(call -> {
            body.reset();
            return null;
        }).when(response).reset();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public void write(int b) {
                body.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }
        });
        return response;
    }
}
