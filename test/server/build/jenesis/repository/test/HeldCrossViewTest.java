package build.jenesis.repository.test;

import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hold placed on a cross-published modular jar must retract EVERY alias it is served under, including the aliases no
 * hold writer can enumerate (D-251).
 *
 * <p>A hold has two halves. The path half is a {@code publish/quarantine<path>} review pointer, which an interceptor
 * reads and which the retroactive sweeps link once per served path they can name - they name them through
 * {@code ArtifactLayout.paths(coordinate, version, store)}, so the Maven format's version-addressed
 * {@code /module/<name>/<version>/<name>.jar} mirror IS among them. The content half is the {@code withheld/<hash>}
 * marker, which is keyed by content rather than by path precisely so that one hold retracts the bytes wherever they
 * are served.
 *
 * <p>The alias that only the content half can reach is the cross-publish's <b>"latest" view</b>,
 * {@code /module/<name>/<name>.jar}. It is not version-addressed, so no {@code paths} overload reports it for a
 * version and no hold writer ever links a review pointer at it - yet it points straight at the held blob. Before
 * D-251 the {@code publish/}-namespace read ({@code ServableNames.state}, which {@code Publication.located} and every
 * Maven/raw serve run through) consulted only the chain and never the marker, so a held modular jar kept serving under
 * that name: driven here end-to-end, and asserted the other way round below.
 *
 * <p>The last check is the reason the fix is the content half rather than a wider path enumeration: after a republish
 * re-aims "latest" at an unheld version, the same name serves again. A path-keyed hold could not express that - it
 * would either keep holding a name that now points at innocent bytes, or (if the sweeps had claimed the pointer as a
 * path of the held version) let an eviction of that version delete a pointer belonging to another one.
 */
class HeldCrossViewTest {

    private static final String MODULE = "test.widget";
    private static final String JAR_1 = "/maven/org/example/widget/1.0/widget-1.0.jar";
    private static final String JAR_2 = "/maven/org/example/widget/2.0/widget-2.0.jar";
    private static final String VERSIONED = "/module/" + MODULE + "/1.0/" + MODULE + ".jar";
    private static final String LATEST = "/module/" + MODULE + "/" + MODULE + ".jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null);
        // The read side a screened deployment runs: the quarantine-pointer interceptor the downstream compliance
        // screen contributes, so the path half of a hold is honoured here exactly as it is in a running server.
        publication = new Publication(store, List.of(new QuarantinePointer()));
    }

    @Test
    void a_held_modular_jar_stops_serving_under_every_module_alias_including_the_one_no_path_enumeration_names()
            throws IOException {
        MavenFormat.layout(store, JAR_1, new ByteArrayInputStream(modularJar("1.0")));
        String hash = publication.blob(JAR_1).orElseThrow();

        assertThat(publication.located(JAR_1)).as("published by coordinate").isPresent();
        assertThat(publication.located(VERSIONED)).as("and by module name and version").isPresent();
        assertThat(publication.located(LATEST)).as("and by module name alone").isPresent();

        // The paths a retroactive sweep can name for this version - the store overload, the one the hold writers
        // reach through the inventory. It reports the /module/ mirror's version folder, so the versioned view is
        // covered by the path half; it cannot report the "latest" view, which belongs to no single version.
        List<String> prefixes = new MavenFormat().paths("org.example:widget", "1.0", store);
        assertThat(prefixes).contains("/module/" + MODULE + "/1.0");
        assertThat(prefixes).as("no path enumeration can claim the latest view for one version")
                .noneMatch(prefix -> LATEST.startsWith(prefix + "/"));

        hold(hash, prefixes);

        assertThat(publication.located(JAR_1)).as("the held coordinate no longer serves").isEmpty();
        assertThat(publication.located(VERSIONED)).as("nor the version-addressed module view").isEmpty();
        assertThat(publication.located(LATEST))
                .as("nor the latest module view, which only the content-addressed half of the hold reaches").isEmpty();

        ServableNames names = new ServableNames(store, publication);
        assertThat(names.state(LATEST)).isEqualTo(ServableNames.State.WITHHELD);
        assertThat(names.disclosable(LATEST, ServableNames.Policy.HIDE_WITHHELD))
                .as("and the listing agrees with the download, which is what this seam exists for").isFalse();

        assertThat(new MavenFormat().paths("org.example:widget", "1.0", store))
                .as("and the version still OCCUPIES its mirror once held - the store overload answers where a version "
                        + "lives, not what would serve, or a hold's own converge pass, an eviction and the release "
                        + "path's cross-alias exclusion set all stop seeing the path the hold just covered")
                .isEqualTo(prefixes);
    }

    @Test
    void a_republish_re_aims_the_latest_view_at_unheld_bytes_and_it_serves_again() throws IOException {
        MavenFormat.layout(store, JAR_1, new ByteArrayInputStream(modularJar("1.0")));
        hold(publication.blob(JAR_1).orElseThrow(), new MavenFormat().paths("org.example:widget", "1.0", store));
        assertThat(publication.located(LATEST)).isEmpty();

        MavenFormat.layout(store, JAR_2, new ByteArrayInputStream(modularJar("2.0")));

        assertThat(publication.located(LATEST))
                .as("latest now names 2.0's bytes, which no hold covers - the screen is keyed by content, not by name")
                .isPresent();
        assertThat(publication.located(JAR_1)).as("1.0 stays held").isEmpty();
        assertThat(publication.located(VERSIONED)).as("and so does its own version-addressed view").isEmpty();
    }

    /** Both halves of the hold a retroactive sweep writes: the content-addressed marker, and a review pointer at every
     *  served path the version's layout prefixes enumerate. */
    private void hold(String hash, List<String> prefixes) throws IOException {
        Withheld.mark(store, hash);
        for (String prefix : prefixes) {
            for (String child : store.list("publish" + prefix)) {
                publication.link("/quarantine" + prefix + "/" + child, hash);
            }
        }
    }

    /** The downstream compliance screen's read side, in one line: a path with a live review pointer does not serve. */
    private static final class QuarantinePointer implements PublishInterceptor {
        @Override
        public boolean withheld(String path, ArtifactStore store) throws IOException {
            return store.readVersioned("publish/quarantine" + path).isPresent();
        }
    }

    /** A jar that declares a module name the way a real modular artifact does, read back by the layout sequence. */
    private static byte[] modularJar(String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", MODULE);
        manifest.getMainAttributes().putValue("Implementation-Version", version);   // distinct bytes per version
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }
}
