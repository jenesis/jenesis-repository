package build.jenesis.repository.staging.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.staging.Staging;
import build.jenesis.repository.staging.store.StoreStaging;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staging list is a window, and the reap pages: over fifteen hundred open stagings - a build farm that opens one
 * per build and never closes them - the list answers the first {@code limit} ids and says more exist, and the reap
 * removes every abandoned one by paged reads, never by listing the staging root whole. A whole listing is what a
 * screen or a pass could not afford on the store that needs it most.
 */
class StagingWindowTest {

    private static final int OPEN = 1_500;
    private static final Duration TTL = Duration.ofHours(1);

    @TempDir
    Path root;

    @Test
    void the_list_is_a_window_that_says_more_and_the_reap_pages_through_every_id() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        StoreStaging seeding = new StoreStaging(filesystem);
        for (int i = 0; i < OPEN; i++) {
            seeding.stage(String.format(Locale.ROOT, "build-%04d", i),
                    "/maven/org/example/lib/" + i + "/lib-" + i + ".jar", ("jar " + i).getBytes(StandardCharsets.UTF_8));
        }

        List<String> wholeListings = new CopyOnWriteArrayList<>();
        FaultInjectingStore store = FaultInjectingStore.wrap(filesystem).tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.LIST && key != null && key.contains("staging")
                    && !key.contains("/build-")) {
                wholeListings.add(key);
            }
        });
        StoreStaging staging = new StoreStaging(store);

        Staging.Window window = staging.ids(200);
        assertThat(window.ids()).as("the first page of ids").hasSize(200);
        assertThat(window.more()).as("and that more exist").isTrue();
        Staging.Window all = staging.ids(OPEN + 1);
        assertThat(all.ids()).hasSize(OPEN);
        assertThat(all.more()).as("a window holding every id says there are no more").isFalse();

        assertThat(staging.reap(Instant.now().plus(TTL).plusSeconds(1), TTL))
                .as("every abandoned staging is reaped, past any one page of ids").isEqualTo(OPEN);
        assertThat(staging.ids(200).ids()).isEmpty();
        assertThat(wholeListings).as("no staging root was listed whole").isEmpty();
    }
}
