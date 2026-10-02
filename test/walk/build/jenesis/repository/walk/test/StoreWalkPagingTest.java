package build.jenesis.repository.walk.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.store.StoreArtifactWalk;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A walk of the store drains every level it descends, so it pages a level at the drain width. A filesystem cannot seek
 * a directory: every page is a scan of the whole level, and a level of a million names paged a thousand at a time is a
 * thousand scans of a million entries - over twenty minutes at a second and a third each - where the drain width makes
 * it a hundred. This holds the walk to one page of a level per drain-width of names.
 */
class StoreWalkPagingTest {

    /** More names under one level than the narrow page, and fewer than three drain pages. */
    private static final int NAMES = 25_000;

    @TempDir
    Path root;

    @Test
    void a_walk_pages_a_wide_level_at_the_drain_width() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        for (int each = 0; each < NAMES; each++) {
            store.writeVersioned("publish/p-" + String.format(Locale.ROOT, "%05d", each), body, null);
        }
        AtomicInteger pages = new AtomicInteger();
        ArtifactStore counting = (ArtifactStore) java.lang.reflect.Proxy.newProxyInstance(
                ArtifactStore.class.getClassLoader(), new Class<?>[] {ArtifactStore.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("page") && args != null && "publish".equals(args[0])) {
                        pages.incrementAndGet();
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        AtomicInteger visited = new AtomicInteger();

        WalkPass pass = new StoreArtifactWalk(1_000, 1, Duration.ofMinutes(10), Clock.systemUTC())
                .walk(counting, "paging", List.of("publish"), _ -> visited.incrementAndGet());

        assertThat(pass.complete()).isTrue();
        assertThat(visited).hasValue(NAMES);
        assertThat(pages).as("the planning page and three drain-width pages, not a page per thousand names")
                .hasValueLessThanOrEqualTo(5);
    }
}
