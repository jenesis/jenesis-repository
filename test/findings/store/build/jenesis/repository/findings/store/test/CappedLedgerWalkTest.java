package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A filtered read the filter index cannot answer walks the ledger live, up to an examined budget, and asks the
 * coordinate level for one page that covers the whole budget. A filesystem cannot seek a directory, so each page is a
 * scan of every coordinate the repository holds; paged at the drain width, a budget larger than it read a level of a
 * million names three times per read, which is what kept the vulnerability report over its bound before its index
 * was first built.
 */
class CappedLedgerWalkTest {

    /** More coordinates than the walk's examined budget, so the read is cut short rather than drained. */
    private static final int COORDINATES = 21_000;

    @TempDir
    Path root;

    @Test
    void a_capped_read_pages_the_coordinate_level_once_and_says_it_was_cut_short() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] document = MetadataDocument.empty().serialize();
        for (int each = 0; each < COORDINATES; each++) {
            store.writeVersioned(MetadataKey.PREFIX + "/maven/com.example%3Aartifact-" + String.format(Locale.ROOT,
                    "%05d", each) + "/1.0.0", document, null);
        }
        String level = MetadataKey.PREFIX + "/maven";
        AtomicInteger pages = new AtomicInteger();
        ArtifactStore counting = (ArtifactStore) java.lang.reflect.Proxy.newProxyInstance(
                ArtifactStore.class.getClassLoader(), new Class<?>[] {ArtifactStore.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("page") && args != null && level.equals(args[0])) {
                        pages.incrementAndGet();
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });

        Findings.Page page = new StoreFindings(counting).all(
                new Findings.Filter(null, Finding.Kind.VULNERABILITY, null, null, null, null), 0, 10);

        assertThat(page.located()).isEmpty();
        assertThat(page.more()).as("the budget ran out before the ledger did").isTrue();
        assertThat(pages).as("one page of the coordinate level covers the examined budget").hasValue(1);
    }
}
