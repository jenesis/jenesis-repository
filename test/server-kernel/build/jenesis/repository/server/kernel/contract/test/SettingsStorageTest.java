package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime settings are kept as one JSON document per contributing module ({@code config/settings/<module>.json}),
 * so a key lands in its owning module's document, a read merges every document into one view, and a write
 * compare-and-sets only the touched document - a concurrent edit to another module never contends and a lost race on
 * the same document re-reads and retries rather than clobbering.
 */
class SettingsStorageTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void keys_land_in_their_owning_module_document_and_round_trip() throws IOException {
        Settings settings = new Settings(store);
        settings.set("vulnerability-threshold", "HIGH");   // a core dial - the neutral document
        settings.set("repositories.mirror", "hosted");     // a map entry - the neutral document
        settings.set("demo", "true");                      // a contributed key - the contributing module's document

        assertThat(SettingsDocuments.moduleOf("vulnerability-threshold")).isEqualTo(SettingsDocuments.NEUTRAL);
        assertThat(SettingsDocuments.moduleOf("demo")).isEqualTo("build.jenesis.repository.server.kernel");
        assertThat(store.list(SettingsDocuments.ROOT))
                .contains("core.json", "build.jenesis.repository.server.kernel.json");

        Settings reopened = new Settings(store);
        assertThat(reopened.getOrDefault("vulnerability-threshold", "NONE")).isEqualTo("HIGH");
        assertThat(reopened.getOrDefault("demo", "false")).isEqualTo("true");
        assertThat(reopened.overrides())
                .containsEntry("vulnerability-threshold", "HIGH")
                .containsEntry("repositories.mirror", "hosted")
                .containsEntry("demo", "true");

        settings.set("demo", null);   // clearing removes it from its document
        assertThat(new Settings(store).getOrDefault("demo", "false")).isEqualTo("false");
    }

    @Test
    void a_write_to_one_module_leaves_another_modules_document_untouched() throws IOException {
        Settings first = new Settings(store);
        Settings second = new Settings(store);
        first.set("demo", "true");                      // the contributing module's document
        second.set("vulnerability-threshold", "HIGH");  // the neutral document; second's stale view of the other is moot

        Settings reopened = new Settings(store);
        assertThat(reopened.getOrDefault("demo", "false")).isEqualTo("true");
        assertThat(reopened.getOrDefault("vulnerability-threshold", "NONE")).isEqualTo("HIGH");
    }

    @Test
    void a_contended_write_re_reads_and_retries_rather_than_clobbering() throws IOException {
        Conflicting conflicting = new Conflicting(store);
        new Settings(conflicting).set("vulnerability-threshold", "HIGH");   // first CAS loses once, retries, wins

        assertThat(conflicting.conflicts).as("one compare-and-set lost before the retry won").isEqualTo(1);
        assertThat(new Settings(store).getOrDefault("vulnerability-threshold", "NONE")).isEqualTo("HIGH");
    }

    @Test
    void a_flood_of_unknown_tenants_resolves_empty_and_leaves_a_real_tenant_intact() throws IOException {
        Settings settings = new Settings(store);
        settings.set("acme", "vulnerability-threshold", "HIGH");   // a real tenant with an override

        // An anonymous deployment never validates that a request key names a real tenant, so a caller can drive
        // tenantConfigured with a flood of distinct invented tenant names (the vector the snapshot cache must not grow
        // an entry for). Each resolves to nothing and none disturbs the real tenant's resolution; the cache guard
        // (verified for the bound at the unit level) keeps a fake tenant out of the per-tenant map entirely.
        for (int index = 0; index < 10_000; index++) {
            assertThat(settings.tenantConfigured("attacker-" + index)).as("an invented tenant holds no settings")
                    .isFalse();
        }
        assertThat(settings.tenantConfigured("acme")).as("the real tenant still resolves").isTrue();
        assertThat(settings.overrides("acme")).containsEntry("vulnerability-threshold", "HIGH");
    }

    /** A store that fails the first compare-and-set to each key (as if another node won the race) and then heals. */
    private static final class Conflicting implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private final Set<String> lost = new HashSet<>();
        private int conflicts;

        Conflicting(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (lost.add(key)) {
                conflicts++;
                return false;
            }
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
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
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
