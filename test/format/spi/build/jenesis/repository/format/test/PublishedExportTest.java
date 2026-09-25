package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter.Exported;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The export of a put-at-its-path format: files go in the order a client sends them, a file the target already holds
 * is not sent again, a withheld file is never sent, and a refusal is a failure only when the target does not hold the
 * same bytes.
 */
class PublishedExportTest {

    private static final String VERSION = "/maven/org/acme/lib/1.0";

    @TempDir
    Path root;

    private ArtifactStore store;
    private final Map<String, String> hashes = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        for (String name : List.of("maven-metadata.xml", "lib-1.0.jar.sha1", "lib-1.0.jar.asc", "lib-1.0.pom",
                "lib-1.0.jar")) {
            publish(VERSION + "/" + name, name);
        }
    }

    @Test
    void files_go_artifacts_then_signatures_then_checksums_then_metadata() throws IOException {
        Recording target = new Recording();
        assertThat(PublishedExport.putAll(store, List.of(VERSION), "/maven/", target)).isEqualTo(Exported.PUBLISHED);
        assertThat(target.sent).containsExactly("org/acme/lib/1.0/lib-1.0.jar", "org/acme/lib/1.0/lib-1.0.pom",
                "org/acme/lib/1.0/lib-1.0.jar.asc", "org/acme/lib/1.0/lib-1.0.jar.sha1",
                "org/acme/lib/1.0/maven-metadata.xml");
        assertThat(target.held.get("org/acme/lib/1.0/lib-1.0.jar")).isEqualTo(hashes.get(VERSION + "/lib-1.0.jar"));
    }

    @Test
    void a_file_the_target_holds_with_the_same_bytes_is_not_sent_again() throws IOException {
        Recording target = new Recording();
        PublishedExport.putAll(store, List.of(VERSION), "/maven/", target);
        target.sent.clear();

        assertThat(PublishedExport.putAll(store, List.of(VERSION), "/maven/", target))
                .isEqualTo(Exported.ALREADY_PRESENT);
        assertThat(target.sent).isEmpty();
    }

    @Test
    void a_withheld_file_is_never_sent() throws IOException {
        Withheld.mark(store, hashes.get(VERSION + "/lib-1.0.jar"));
        Recording target = new Recording();
        PublishedExport.putAll(store, List.of(VERSION), "/maven/", target);
        assertThat(target.sent).doesNotContain("org/acme/lib/1.0/lib-1.0.jar").isNotEmpty();
    }

    @Test
    void a_version_every_file_of_which_is_withheld_exports_as_withheld() throws IOException {
        for (String hash : hashes.values()) {
            Withheld.mark(store, hash);
        }
        Recording target = new Recording();
        assertThat(PublishedExport.putAll(store, List.of(VERSION), "/maven/", target)).isEqualTo(Exported.WITHHELD);
        assertThat(target.sent).isEmpty();
    }

    @Test
    void a_refusal_over_the_same_bytes_is_present_and_over_different_bytes_is_a_failure() throws IOException {
        Recording same = new Recording();
        same.refusing = true;
        // A target that refuses a redeploy but serves every file byte for byte: nothing moved, nothing lost.
        hashes.forEach((path, hash) -> same.later.put(path.substring("/maven/".length()), hash));
        assertThat(PublishedExport.putAll(store, List.of(VERSION), "/maven/", same)).isEqualTo(Exported.ALREADY_PRESENT);

        Recording different = new Recording();
        different.refusing = true;
        different.later.put("org/acme/lib/1.0/lib-1.0.jar", "0".repeat(64));
        assertThatThrownBy(() -> PublishedExport.putAll(store, List.of(VERSION), "/maven/", different))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("org/acme/lib/1.0/lib-1.0.jar")
                .hasMessageContaining("409");
    }

    private void publish(String path, String content) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        hashes.put(path, hash);
    }

    /**
     * A target that records the paths it was sent and the hash of what it holds at each. A refusing one answers every
     * put with {@code 409} and, from then on, serves whatever {@code later} says it held all along - a check made
     * before the put sees nothing, which is how a release repository that allows no redeploy looks to an export.
     */
    private static final class Recording implements ExportTarget {

        final List<String> sent = new ArrayList<>();
        final Map<String, String> held = new HashMap<>();
        final Map<String, String> later = new HashMap<>();
        boolean refusing;

        @Override
        public Response send(Request request) throws IOException {
            sent.add(request.path());
            if (refusing) {
                held.put(request.path(), later.getOrDefault(request.path(), ""));
                return new Response(409, "already deployed");
            }
            try (InputStream in = request.body().open()) {
                held.put(request.path(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            return new Response(201, "");
        }

        @Override
        public Optional<String> sha256(String path) {
            return Optional.ofNullable(held.get(path)).filter(hash -> !hash.isEmpty());
        }

        @Override
        public Optional<Credential> credential() {
            return Optional.empty();
        }
    }
}
