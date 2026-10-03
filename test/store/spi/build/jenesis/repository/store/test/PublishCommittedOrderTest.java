package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An accepted publish tells the interceptors it committed once its layout is visible, so what they record of it is what
 * serves: a publish whose layout declines, or whose link is refused, is never recorded, while a held upload is told
 * as soon as it is routed, since nothing is laid out for it.
 */
class PublishCommittedOrderTest {

    private static final String PATH = "/raw/release/notes.txt";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void an_accepted_publish_is_told_once_it_serves() throws IOException {
        Hearing hearing = new Hearing(PublishInterceptor.Disposition.ACCEPT);

        commit(hearing, _ -> Publication.Visibility.at(PATH));

        assertThat(hearing.heard).containsExactly("ACCEPT, serving");
    }

    @Test
    void a_publish_the_layout_declines_is_never_told() throws IOException {
        Hearing hearing = new Hearing(PublishInterceptor.Disposition.ACCEPT);

        commit(hearing, _ -> Publication.Visibility.declined());

        assertThat(hearing.heard).as("a refused re-point records nothing").isEmpty();
    }

    @Test
    void a_publish_whose_link_is_refused_is_never_told() {
        Hearing hearing = new Hearing(PublishInterceptor.Disposition.ACCEPT);

        assertThatThrownBy(() -> commit(hearing, _ -> Publication.Visibility.through((hash, _, _) -> {
            throw new Publication.RepublishConflict(PATH, "an earlier release", hash);
        }))).isInstanceOf(Publication.RepublishConflict.class);

        assertThat(hearing.heard).isEmpty();
    }

    @Test
    void a_held_upload_is_told_as_soon_as_it_is_routed() throws IOException {
        Hearing hearing = new Hearing(PublishInterceptor.Disposition.QUARANTINE);

        commit(hearing, _ -> {
            throw new AssertionError("a held upload is not laid out");
        });

        assertThat(hearing.heard).containsExactly("QUARANTINE, not serving");
    }

    private void commit(Hearing hearing, Publication.AcceptedLayout layout) throws IOException {
        new Publication(store, List.of(hearing), List.of()).commit(ArtifactDescriptor.at("raw", PATH),
                new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)), Publication.Republish.overwrite(),
                layout);
    }

    /** A screen answering one verdict, noting each {@code committed} it hears and whether the path served then. */
    private final class Hearing implements PublishInterceptor {

        private final Disposition verdict;
        private final List<String> heard = new ArrayList<>();

        private Hearing(Disposition verdict) {
            this.verdict = verdict;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) {
            return verdict;
        }

        @Override
        public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore routed)
                throws IOException {
            boolean serving = new Publication(store, List.of(), List.of()).located(PATH).isPresent();
            heard.add(disposition + ", " + (serving ? "serving" : "not serving"));
        }

        @Override
        public void onPublished(ArtifactDescriptor artifact, ArtifactStore routed) {
        }
    }
}
