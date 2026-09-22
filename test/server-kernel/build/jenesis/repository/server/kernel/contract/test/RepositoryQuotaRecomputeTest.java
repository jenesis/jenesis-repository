package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant-wide quota recount enumerates each repository's flat {@code blobs/} namespace through the ordered
 * {@link ArtifactStore#page} primitive, never {@link ArtifactStore#list} - the WALK.5 enumerator migration, the
 * tenant-spanning twin of {@code QuotaArtifactStore.recompute} - so a millions-entry namespace never
 * materialises as one list. The store wrapper fails a {@code list("blobs")} outright (the small repository-name
 * listing at the tenant scope stays allowed) and records every page limit, pinning that the recount sums every
 * live blob across repositories at the drain width - one {@code DRAIN_PAGE} page per repository here; the resume
 * across page boundaries is {@code Listings}' own claim, tested there - from the sizes the listing carried, never a
 * request per blob, while a stale counter is overwritten with the recomputed truth.
 */
class RepositoryQuotaRecomputeTest {

    @TempDir
    Path root;

    @Test
    void the_recount_pages_each_repositorys_blob_namespace_and_never_lists_it() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore tenant = filesystem.scope("acme");
        ArtifactStore alpha = tenant.scope("alpha");
        for (int blob = 0; blob < 2001; blob++) {
            alpha.write("blobs/" + String.format("%04d", blob), content(1));
        }
        alpha.write("blobs/zzz-empty", content(0));
        ArtifactStore beta = tenant.scope("beta");
        beta.write("blobs/one", content(5));
        beta.write("blobs/two", content(5));
        new QuotaArtifactStore(tenant, 0).store(999);

        List<Integer> pages = new ArrayList<>();
        List<String> sized = new ArrayList<>();
        Repositories repositories = repositories(new PagingStore(filesystem, pages, sized));

        assertThat(repositories.recomputeQuota("acme"))
                .as("every live blob is summed across repositories and page boundaries, the empty one skipped")
                .isEqualTo(2001 + 10);
        assertThat(repositories.quotaUsed("acme"))
                .as("the stale counter is overwritten with the recomputed truth").isEqualTo(2011);
        assertThat(pages).as("one drain page per repository, at the drain width")
                .hasSize(2).containsOnly(ArtifactStore.DRAIN_PAGE);
        assertThat(sized).as("every size came from the listing that named the blob; no request per blob").isEmpty();
    }

    private Repositories repositories(ArtifactStore store) throws IOException {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        return new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
    }

    private static InputStream content(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return new ByteArrayInputStream(bytes);
    }

    /** Forwards to a real store but fails a {@code list} of the blob namespace outright and re-wraps every scope,
     *  so the recount provably streams each repository's blobs through the ordered {@link ArtifactStore#page}
     *  primitive at every scoping depth, while the recorded page limits pin that every page stays bounded. The
     *  scope-free {@code list} of the repository names themselves (a small, human-sized set) stays permitted. */
    private record PagingStore(ArtifactStore delegate, List<Integer> pages, List<String> sized) implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        @Override
        public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
            pages.add(limit);
            delegate.pageListed(prefix, startAfter, limit, consumer);
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.startsWith("blobs")) {
                throw new AssertionError("the recount must page through '" + prefix + "', never list it");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            pages.add(limit);
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new PagingStore(delegate.scope(tenant), pages, sized);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            sized.add(key);
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
