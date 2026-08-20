package build.jenesis.repository.test;

import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.format.maven.ModuleViewRebuild;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Maven format's cross-publish sequence, its crash windows and its repair (D-059), driven with <em>both</em>
 * layout formats on the module path so the one required cross-publish really runs.
 *
 * <p>{@code MavenFormat.layout} links the {@code /maven/} coordinate, reads the module name back out of the stored
 * blob, and hands it to every discovered {@code ModuleView}. Each step can fail, and this suite drives a real store
 * failure at each of the three and asserts what is observable afterwards, rather than asserting the prose:
 *
 * <ol>
 *   <li>a failure linking the coordinate leaves <b>nothing servable</b> and an unreferenced blob;</li>
 *   <li>a failure reading the module name back leaves the artifact <b>serving by coordinate with no module view</b>;</li>
 *   <li>a failure inside a view leaves the same state.</li>
 * </ol>
 *
 * <p>The point of the ticket is that (2) and (3) are only acceptable because they <b>converge</b>: the module view is
 * derived from the coordinate, so the {@code module-view} {@code WalkConsumer} re-derives it from the durable store on
 * the next rebuild pass. Every crash check below therefore ends by running that pass and asserting the store reaches
 * the state a completed publish would have left - except for the "latest" pointer, which records which version was
 * published last and is deliberately outside the repair, and which is asserted to stay untouched rather than being
 * quietly re-invented by the walk.
 *
 * <p>There is no fourth window for a retraction: a proxied artifact that fails its upstream checksum is now refused
 * before anything is linked ({@code MavenProxyChecksumRefusalTest}), so the format has exactly one cross-view sequence.
 */
class MavenCrossPublishSequenceTest {

    private static final String PATH = "/maven/org/example/widget/1.0/widget-1.0.jar";
    private static final String MODULE = "test.widget";
    private static final String VERSIONED = "/module/" + MODULE + "/1.0/" + MODULE + ".jar";
    private static final String LATEST = "/module/" + MODULE + "/" + MODULE + ".jar";

    @TempDir
    Path root;

    private FaultInjectingStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = FaultInjectingStore.wrap(ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));
        publication = new Publication(store);
    }

    @Test
    void a_completed_publish_links_the_coordinate_and_both_module_views() throws IOException {
        // The control: without it the crash checks below could pass by never cross-publishing at all.
        MavenFormat.layout(store, PATH, new ByteArrayInputStream(modularJar()));

        assertThat(publication.located(PATH)).as("serving by coordinate").isPresent();
        assertThat(publication.located(VERSIONED)).as("and by module name and version").isPresent();
        assertThat(publication.located(LATEST)).as("and by module name alone").isPresent();
    }

    @Test
    void a_failure_at_the_commit_point_leaves_nothing_servable() throws IOException {
        // Step 1. The /maven/ pointer is the commit point: before it the stored blob is an unreferenced object and
        // nothing is reachable. A publish that dies here is the one window that needs no repair.
        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, key -> key.startsWith("publish/maven/"));

        assertThatThrownBy(() -> MavenFormat.layout(store, PATH, new ByteArrayInputStream(modularJar())))
                .isInstanceOf(IOException.class);

        assertThat(publication.located(PATH)).as("nothing serves by coordinate").isEmpty();
        assertThat(publication.located(VERSIONED)).as("and no module view was derived").isEmpty();
        assertThat(publication.located(LATEST)).isEmpty();
        assertThat(store.list("blobs")).as("only the unreferenced blob garbage collection reclaims").hasSize(1);
    }

    @Test
    void a_failure_reading_the_module_name_back_leaves_a_coordinate_a_pass_can_finish() throws IOException {
        // Step 2. The coordinate is linked; the read that derives the module name from the stored blob fails. The
        // publish is reported failed while the artifact serves - the state the ticket calls out - and it converges.
        store.failNextOn(FaultInjectingStore.Op.OPEN, key -> key.startsWith("blobs/"));

        assertThatThrownBy(() -> MavenFormat.layout(store, PATH, new ByteArrayInputStream(modularJar())))
                .isInstanceOf(IOException.class);

        assertThat(publication.located(PATH)).as("the artifact serves under its coordinate").isPresent();
        assertThat(publication.located(VERSIONED)).as("with no module view derived yet").isEmpty();

        assertThat(rebuild()).as("the pass enumerated the store").isTrue();

        assertThat(publication.located(VERSIONED))
                .as("the rebuild pass derived the module view from the coordinate that survived")
                .isEqualTo(publication.located(PATH));
        assertThat(publication.located(LATEST))
                .as("the latest pointer is publication-order truth, so the walk does not invent one").isEmpty();
    }

    @Test
    void a_failure_inside_a_module_view_leaves_a_coordinate_a_pass_can_finish() throws IOException {
        // Step 3. The coordinate is linked and the module name read; the view's own pointer write fails. Same residue,
        // same repair - and the same reason it is repairable: the coordinate names the blob the module name comes from.
        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, key -> key.startsWith("publish/module/"));

        assertThatThrownBy(() -> MavenFormat.layout(store, PATH, new ByteArrayInputStream(modularJar())))
                .isInstanceOf(IOException.class);

        assertThat(publication.located(PATH)).as("the artifact serves under its coordinate").isPresent();
        assertThat(publication.located(VERSIONED)).as("its module view never landed").isEmpty();

        assertThat(rebuild()).isTrue();

        assertThat(publication.located(VERSIONED)).as("re-derived from the durable store")
                .isEqualTo(publication.located(PATH));
    }

    @Test
    void the_repair_is_idempotent_and_leaves_a_completed_publish_alone() throws IOException {
        // Clause 2, and the reason the repair may run on a cadence: a pass over a store that needs no repair writes
        // nothing new, and a second pass leaves the same objects with the same bodies - including the latest pointer a
        // completed publish owns, which the walk must not move.
        MavenFormat.layout(store, PATH, new ByteArrayInputStream(modularJar()));
        Map<String, String> before = pointers();

        rebuild();
        rebuild();

        assertThat(pointers()).as("two further passes over unchanged state changed nothing").isEqualTo(before);
    }

    @Test
    void a_jar_published_before_any_module_view_provider_existed_gains_its_view_on_the_first_pass() throws IOException {
        // The back-fill direction (§5): the same repair adopts a repository whose jars were published while no
        // ModuleView provider was on the module path at all. Simulated by linking the coordinate exactly as a publish
        // without a cross-publish would have left it - a stored blob and a /maven/ pointer, nothing else.
        String hash = publication.storeBlob(new ByteArrayInputStream(modularJar()));
        publication.link(PATH, hash);

        rebuild();

        assertThat(publication.located(VERSIONED)).as("adopted from the durable store, with no re-import")
                .contains("blobs/" + hash);
    }

    @Test
    void the_repair_never_derives_a_view_over_content_the_store_no_longer_holds() throws IOException {
        // A torn pointer (its blob gone) is delivered to the pass rather than dropped, so that a reconcile consumer
        // sees it. This one is not that consumer: with no jar to read a module name out of, guessing one from the
        // coordinate would link a module view over content that cannot be served.
        String hash = publication.storeBlob(new ByteArrayInputStream(modularJar()));
        publication.link(PATH, hash);
        store.delete("blobs/" + hash);

        rebuild();

        assertThat(store.list("publish/module")).as("no module view was invented for a torn pointer").isEmpty();
    }

    @Test
    void the_repair_leaves_a_first_hand_module_publish_alone() throws IOException {
        // The other half of "it never removes anything": /module/ is the Jenesis format's own namespace, so a pointer
        // there with no Maven jar behind it is an artifact somebody published, not the debris of a failed
        // cross-publish. A pass that treated it as an orphan would delete a live artifact.
        String hash = publication.storeBlob(new ByteArrayInputStream("a first-hand module publish".getBytes(
                StandardCharsets.UTF_8)));
        publication.link("/module/direct.publish/1.0/direct.publish.jar", hash);

        rebuild();

        assertThat(publication.located("/module/direct.publish/1.0/direct.publish.jar"))
                .as("a module published first-hand is untouched by the Maven repair").contains("blobs/" + hash);
    }

    /** Run one full rebuild pass driving the module-view repair, and answer whether it completed. */
    private boolean rebuild() throws IOException {
        WalkConsumer consumer = new ModuleViewRebuild();
        return RebuildPass.run(new StoreArtifactWalk(64, 1, Duration.ofMinutes(10), Clock.systemUTC()),
                store, List.of("publish"), List.of(consumer))
                .orElseThrow(() -> new AssertionError("a pass with a consumer must run"))
                .complete();
    }

    /** Every serving pointer in the store, by request path - the comparable view a repair must converge on. */
    private Map<String, String> pointers() throws IOException {
        Map<String, String> pointers = new TreeMap<>();
        collect("publish", pointers);
        return pointers;
    }

    private void collect(String prefix, Map<String, String> pointers) throws IOException {
        List<String> children = store.list(prefix);
        if (children.isEmpty()) {
            store.readVersioned(prefix).ifPresent(pointer -> pointers.put(prefix,
                    new String(pointer.content(), StandardCharsets.UTF_8)));
            return;
        }
        for (String child : children) {
            collect(prefix + "/" + child, pointers);
        }
    }

    /** A jar that declares a module name the way a real modular artifact does, read back by the layout sequence. */
    private static byte[] modularJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", MODULE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }
}
