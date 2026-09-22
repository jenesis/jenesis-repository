package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.ui.store.TenantLimits;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Setting a tenant's quota from the console: the limit is stored, and the usage total is <em>not</em> recomputed.
 *
 * <p><b>This class used to assert the opposite, and the change is deliberate.</b> Setting a limit walked every blob
 * of every repository the tenant owns while the operator waited, so the cost of the action grew with the tenant -
 * which is the one thing a request must not do (&sect;10). The cleanup pass already recomputes usage for any tenant
 * that has a limit, so deferring costs a window rather than the number: enforcement runs on the previous total until
 * the next pass, and a limit lowered mid-window can be briefly over-admitted against. That trade was taken
 * deliberately by the owner on 2026-08-31.
 *
 * <p><b>The paging guarantee did not move here, it moved away.</b> This suite used to pin that the recount streams
 * each repository's blobs through {@code page} and never {@code list}s them - and so does
 * {@code RepositoryQuotaRecomputeTest}, over {@code Repositories.recomputeQuota}, which is the walk the pass runs.
 * They were two tests of two copies of one walk; now there is one walk and one test, and this one asserts only what
 * the console still does.
 */
class ConsoleQuotaSettingTest {

    @TempDir
    Path root;

    @Test
    void setting_a_quota_stores_the_limit_without_recounting_the_tenants_blobs() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore tenant = filesystem.scope("acme");
        tenant.scope("alpha").write("blobs/one", content(11));
        // A stale counter, deliberately wrong: if setting a quota still recounted, this would be corrected to 11.
        new QuotaArtifactStore(tenant, 0).store(999);

        TenantLimits limits = new TenantLimits(new RefusingStore(filesystem), Authorization.enforcing(filesystem),
                () -> "acme", ObservationRegistry.NOOP, AuditTrail.none(), () -> "tester");

        limits.setQuota(4096);

        assertThat(new QuotaArtifactStore(filesystem.scope("acme"), 0).used())
                .as("the stale total is left for the pass; setting a limit does not walk the tenant")
                .isEqualTo(999);
    }

    private static InputStream content(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return new ByteArrayInputStream(bytes);
    }

    /**
     * A store that fails any attempt to enumerate a blob namespace, by {@code list} or by {@code page}.
     *
     * <p>The assertion above could be satisfied by a recount that happened to produce 999, so the guarantee is made
     * structural instead: if setting a quota touches the blobs at all this throws, and the test fails for the right
     * reason rather than on an arithmetic coincidence. The method set is the one the previous suite's double
     * carried, kept rather than rewritten - {@code ArtifactStore} is wide, and a hand-rolled subset compiles only
     * by accident of which methods happen to have defaults.
     */
    private record RefusingStore(ArtifactStore delegate) implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.startsWith("blobs")) {
                throw new AssertionError("setting a quota listed '" + prefix + "'; it must not walk the tenant");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            if (prefix.startsWith("blobs")) {
                throw new AssertionError("setting a quota paged '" + prefix + "'; it must not walk the tenant");
            }
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new RefusingStore(delegate.scope(tenant));
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
