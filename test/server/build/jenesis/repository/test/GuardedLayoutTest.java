package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A layout held to the pointer it replaces ({@link Publication#guarded}): what keeps a release immutable when two
 * first publishes race. The edge's check reads the pointer before the layout runs, so both publishes of one release
 * found it empty and both went on to link; the second pointer write replaced the first and both answered 201. The
 * decision now sits inside the link's own compare-and-set, and these cases pin that it does - including the one
 * where the rival lands between the link's read and its write, which a check made anywhere before the write would
 * miss.
 */
class GuardedLayoutTest {

    private static final String RELEASE = "/maven/org/acme/app/1.0/app-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore disk;

    @BeforeEach
    void setUp() {
        disk = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_guarded_link_meeting_other_bytes_is_refused_and_the_standing_pointer_stays() throws IOException {
        Publication publication = new Publication(disk);
        String winner = blob(publication, "the winner's jar");
        String loser = blob(publication, "the loser's jar");
        publication.link(RELEASE, winner);

        assertThatThrownBy(() -> Publication.guarded(RELEASE, () -> publication.link(RELEASE, loser)))
                .isInstanceOf(Publication.RepublishConflict.class)
                .hasMessageContaining(winner);
        assertThat(publication.blob(RELEASE)).as("the release still serves the bytes it was published with")
                .contains(winner);
    }

    @Test
    void a_rival_landing_between_the_links_read_and_its_write_is_met_rather_than_overwritten() throws IOException {
        Publication rival = new Publication(disk);
        String winner = blob(rival, "the winner's jar");
        String loser = blob(rival, "the loser's jar");
        // The rival's pointer lands at the one moment a check made before the write cannot see: after this link has
        // read the empty pointer, just before its own compare-and-set.
        boolean[] landed = {false};
        FaultInjectingStore store = FaultInjectingStore.wrap(disk).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.WRITE_VERSIONED && ("publish" + RELEASE).equals(key) && !landed[0]) {
                landed[0] = true;
                try {
                    rival.link(RELEASE, winner);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        });

        assertThatThrownBy(() -> Publication.guarded(RELEASE, () -> new Publication(store).link(RELEASE, loser)))
                .isInstanceOf(Publication.RepublishConflict.class);
        assertThat(landed[0]).as("the interleaving was really provoked").isTrue();
        assertThat(rival.blob(RELEASE)).contains(winner);
    }

    @Test
    void the_same_bytes_converge_and_an_empty_path_links() throws IOException {
        Publication publication = new Publication(disk);
        String jar = blob(publication, "the jar");

        Publication.guarded(RELEASE, () -> publication.link(RELEASE, jar));
        Publication.guarded(RELEASE, () -> publication.link(RELEASE, jar));

        assertThat(publication.blob(RELEASE)).as("a first publish links, and an identical re-publish converges")
                .contains(jar);
    }

    @Test
    void only_the_guarded_path_is_held_and_a_review_link_is_never() throws IOException {
        Publication publication = new Publication(disk);
        String first = blob(publication, "first");
        String second = blob(publication, "second");
        String pom = "/maven/org/acme/app/1.0/app-1.0.pom";
        publication.link(pom, first);
        publication.link("/quarantine" + RELEASE, first);

        Publication.guarded(RELEASE, () -> {
            publication.link(pom, second);
            publication.link("/quarantine" + RELEASE, second);
            return null;
        });

        assertThat(publication.blob(pom)).as("a path the layout was not guarded for re-points as it always did")
                .contains(second);
        assertThat(publication.blob("/quarantine" + RELEASE)).as("a review link is a hold, not a release")
                .contains(second);
    }

    private static String blob(Publication publication, String body) throws IOException {
        return publication.storeBlob(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
