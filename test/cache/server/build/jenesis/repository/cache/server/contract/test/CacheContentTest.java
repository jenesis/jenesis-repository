package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.Cache;
import build.jenesis.repository.cache.server.Cache.Admission;
import build.jenesis.repository.cache.server.Cache.Allowed;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the cache promises about a build's files once a step's result is a manifest of files rather than one opaque
 * entry: a file is taken only under the digest of its own bytes, a manifest is complete or names what it lacks, and
 * the files of one project are neither served to nor confirmed for another.
 *
 * <p>The cache is driven directly, as {@link build.jenesis.repository.cache.server.CacheController} drives it.
 */
public class CacheContentTest {

    private static final String ACME = Authorization.mint("acme");

    @TempDir
    private Path root;

    @BeforeEach
    void refuseHonestly() {
        UnaryOperator<String> deployment = Features.lookup();
        Features.configure(key -> ("jenrepo." + AccessDenial.KEY).equals(key)
                ? AccessDenial.FORBIDDEN_VALUE : deployment.apply(key));
    }

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    public void a_file_is_stored_under_the_digest_of_its_bytes_and_read_back() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        assertThat(cache.admit(project, "SHA-256", sha256("payload"), stream("payload"))).isEqualTo(Admission.STORED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cache.read(cache.content(project, "SHA-256", sha256("payload")), out);
        assertThat(out.toString(UTF_8)).isEqualTo("payload");
    }

    @Test
    public void a_file_uploaded_under_a_name_its_bytes_do_not_digest_to_is_not_stored() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        assertThat(cache.admit(project, "SHA-256", sha256("trusted"), stream("planted"))).isEqualTo(Admission.MISMATCHED);
        assertThat(cache.exists(cache.content(project, "SHA-256", sha256("trusted")))).isFalse();
    }

    @Test
    public void a_second_upload_of_a_file_is_read_and_dropped() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        assertThat(cache.admit(project, "SHA-256", sha256("payload"), stream("payload"))).isEqualTo(Admission.STORED);
        InputStream second = stream("payload");
        assertThat(cache.admit(project, "SHA-256", sha256("payload"), second)).isEqualTo(Admission.PRESENT);
        assertThat(second.read()).as("the entry of a batch is read to its end, so that the next one follows").isEqualTo(-1);
    }

    @Test
    public void a_file_over_the_entry_cap_is_not_stored() throws Exception {
        Cache cache = cache(4);
        Allowed project = project(cache, "demo", true);
        assertThat(cache.admit(project, "SHA-256", sha256("payload"), stream("payload"))).isEqualTo(Admission.TOO_LARGE);
        assertThat(cache.exists(cache.content(project, "SHA-256", sha256("payload")))).isFalse();
    }

    @Test
    public void the_files_of_one_project_are_not_those_of_another() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed demo = project(cache, "demo", true), other = project(cache, "other", true);
        cache.admit(demo, "SHA-256", sha256("payload"), stream("payload"));
        assertThat(cache.exists(cache.content(other, "SHA-256", sha256("payload"))))
                .as("a build that knows a digest learns nothing about what another project built")
                .isFalse();
        assertThat(cache.lacking(other, "SHA-256", manifest("payload"))).containsExactly(sha256("payload"));
        assertThat(cache.lacking(demo, "SHA-256", manifest("payload"))).isEmpty();
    }

    @Test
    public void the_content_of_two_digest_algorithms_and_a_manifest_never_share_an_entry() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        assertThat(cache.content(project, "SHA-256", sha256("payload")).entry())
                .isNotEqualTo(cache.content(project, "SHA-512", sha256("payload")).entry())
                .isNotEqualTo(project.entry());
        assertThat(cache.content(project, "sha-256", sha256("payload")).entry())
                .as("an algorithm is one however it is cased")
                .isEqualTo(cache.content(project, "SHA-256", sha256("payload")).entry());
    }

    @Test
    public void a_manifest_names_the_files_its_project_lacks_each_once_and_in_its_order() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        cache.admit(project, "SHA-256", sha256("held"), stream("held"));
        byte[] manifest = (sha256("second") + " 6 b/second\n"
                + sha256("held") + " 4 held\n"
                + "- 0 empty\n"
                + sha256("first") + " 5 a path with spaces\n"
                + sha256("second") + " 6 copy\n").getBytes(UTF_8);
        assertThat(cache.lacking(project, "SHA-256", manifest)).containsExactly(sha256("second"), sha256("first"));
        assertThat(cache.lacking(project, "SHA-256", new byte[0])).as("a step that wrote nothing lacks nothing").isEmpty();
    }

    @Test
    public void a_body_that_is_no_manifest_is_refused_naming_the_line() throws Exception {
        Cache cache = cache(1L << 31);
        Allowed project = project(cache, "demo", true);
        assertThatThrownBy(() -> cache.lacking(project, "SHA-256", "PK not a manifest".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PK not a manifest");
        assertThatThrownBy(() -> cache.lacking(project, "SHA-256", (sha256("x").toUpperCase(Locale.ROOT) + " 1 x").getBytes(UTF_8)))
                .as("a digest is lower case, so that one file has one name")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.lacking(project, "SHA-256", "../../etc 1 x".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void only_a_digest_of_a_known_algorithm_in_lower_case_names_content() {
        assertThat(Cache.isContent("SHA-256", sha256("payload"))).isTrue();
        assertThat(Cache.isContent("sha-256", sha256("payload"))).isTrue();
        assertThat(Cache.isContent("SHA-256", sha256("payload").substring(1))).isFalse();
        assertThat(Cache.isContent("SHA-256", sha256("payload").toUpperCase(Locale.ROOT))).isFalse();
        assertThat(Cache.isContent("SHA-512", sha256("payload"))).isFalse();
        assertThat(Cache.isContent("CRC32", "00000000")).isFalse();
        assertThat(Cache.isContent(null, sha256("payload"))).isFalse();
        assertThat(Cache.isDigest("SHA-256")).isTrue();
        assertThat(Cache.isDigest("CRC32")).isFalse();
    }

    private Cache cache(long max) {
        return new Cache(CacheStorages.filesystem(root), authorization(), max, 256, null, 0, 0, "default", false, null,
                "default", new SimpleMeterRegistry())
                .policies((tenant, project) -> StoredSettings.projectChain(
                        ArtifactStoreProvider.resolve("filesystem",
                                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null),
                        tenant, project));
    }

    private Authorization authorization() {
        return Authorization.enforcing(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null));
    }

    private Allowed project(Cache cache, String name, boolean write) throws IOException {
        authorization().setGrant("acme", Authorization.hash(ACME), "*", "cache:read,cache:write");
        return (Allowed) cache.resolve("acme", "jenesis", name, ACME, "00", "00", write);
    }

    private static byte[] manifest(String content) {
        return (sha256(content) + " " + content.length() + " file\n").getBytes(UTF_8);
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(UTF_8));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
