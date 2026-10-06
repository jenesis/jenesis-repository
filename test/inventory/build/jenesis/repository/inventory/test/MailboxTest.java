package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.Mailbox;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Mailbox}: one row per version however often it is asked about, removed before it is handed over, and
 *  forgotten with its version. */
class MailboxTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    private final Mailbox mailbox = new Mailbox("test/box");

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default").scope("repo");
    }

    @Test
    void a_version_asked_about_twice_is_one_row_handed_over_once() throws IOException {
        mailbox.post(store, "npm", "lodash", "4.17.21");
        mailbox.post(store, "npm", "lodash", "4.17.21");
        mailbox.post(store, "npm", "left-pad", "1.3.0");

        List<StoreRepositoryInventory.Coordinate> drained = new ArrayList<>();
        assertThat(mailbox.drain(store, 10, drained::add)).isEqualTo(2);

        assertThat(drained).extracting(StoreRepositoryInventory.Coordinate::coordinate)
                .containsExactlyInAnyOrder("lodash", "left-pad");
        assertThat(store.isEmpty(mailbox.root())).as("drained, so nothing is handed over twice").isTrue();
    }

    @Test
    void a_row_is_gone_before_its_version_is_handed_over_so_a_request_made_meanwhile_waits_for_the_next_drain()
            throws IOException {
        mailbox.post(store, "npm", "lodash", "4.17.21");

        mailbox.drain(store, 10, version -> mailbox.post(store, version.ecosystem(), version.coordinate(),
                version.version()));

        assertThat(mailbox.drain(store, 10, _ -> { })).as("the request the visitor made").isEqualTo(1);
    }

    @Test
    void a_drain_stops_at_its_limit_and_says_so() throws IOException {
        for (int minor = 0; minor < 5; minor++) {
            mailbox.post(store, "npm", "lodash", "4." + minor + ".0");
        }

        assertThat(mailbox.drain(store, 2, _ -> { })).isEqualTo(2);
        assertThat(mailbox.drain(store, 10, _ -> { })).isEqualTo(3);
    }

    @Test
    void a_row_that_does_not_parse_is_removed_and_passed_over() throws IOException {
        store.write(mailbox.root() + "/torn",
                new ByteArrayInputStream("{\"ecosystem\":".getBytes(StandardCharsets.UTF_8)));

        List<StoreRepositoryInventory.Coordinate> drained = new ArrayList<>();
        assertThat(mailbox.drain(store, 10, drained::add)).isEqualTo(1);

        assertThat(drained).isEmpty();
        assertThat(store.isEmpty(mailbox.root())).isTrue();
    }

    @Test
    void a_forgotten_version_leaves_no_row_and_forgetting_one_never_asked_about_is_harmless() throws IOException {
        mailbox.post(store, "npm", "lodash", "4.17.21");

        mailbox.forget(store, "npm", "lodash", "4.17.21");
        mailbox.forget(store, "npm", "never", "1.0.0");

        assertThat(store.isEmpty(mailbox.root())).isTrue();
    }

    @Test
    void two_mailboxes_in_one_store_never_see_each_others_rows() throws IOException {
        Mailbox other = new Mailbox("test/other");
        mailbox.post(store, "npm", "lodash", "4.17.21");

        assertThat(other.drain(store, 10, _ -> { })).isZero();
        assertThat(mailbox.drain(store, 10, _ -> { })).isEqualTo(1);
    }
}
