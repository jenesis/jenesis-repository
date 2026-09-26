package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Withheld;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;
import static build.jenesis.repository.gateway.testkit.FormatDrive.sha256;

/**
 * The denial-of-service fix for {@code RubyGemsFormat.versions} (the hottest bundler read): the compact-index
 * {@code /versions} endpoint must decide "is any gem withheld?" - and fold the withheld set into its withheld-aware
 * cache fingerprint - by <em>paging</em> the flat, content-addressed {@code withheld/<hash>} namespace, never by
 * materialising the whole namespace as one {@code List} the way the old {@code blobs.list("withheld")} + {@code
 * isEmpty()} did. Under a broad KEV sweep that grows {@code withheld} to millions of markers, the former per-request
 * whole-namespace materialisation was a heap/time blowup - the exact anti-pattern #203 paged out of conda/rpm/debian
 * {@code withheldStamp}.
 *
 * <p>Drives the real {@code RubyGemsFormat} over a spy store that DELEGATES everything to a filesystem store (whose
 * {@code page} is a native bounded scan, so the proof is real - a {@code MemStore}'s default {@code page} would
 * re-list) but <b>throws</b> if anything calls {@code list("withheld")} and <b>counts</b> {@code page("withheld", ...)}.
 * Two gems are published, then {@code /versions} is served three times:
 * <ol>
 *   <li>with nothing held: both gems list, served via the fast path, and the spy saw {@code page("withheld")} but never
 *       {@code list("withheld")} - the emptiness question is a bounded probe;</li>
 *   <li>after a retroactive withhold of one gem's blob: that gem is SCREENED OUT of {@code /versions} (the filter still
 *       works), and still no {@code list("withheld")} - the withheld-aware fingerprint folds the namespace a page at a
 *       time;</li>
 *   <li>after releasing it: the gem re-lists - the paged stamp changed and self-invalidated the cache.</li>
 * </ol>
 * Needs no Ruby toolchain or network.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RubyGemsWithheldPagingTest {

    @TempDir
    static Path root;

    private ForbidWithheldListStore store;
    private final RepositoryFormat gems = format("rubygems");
    private byte[] betaGem;

    @BeforeAll
    public void seed() throws IOException {
        System.setProperty("jenreg.filesystem.root", root.toString());
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem", System::getProperty)
                .scope("default").scope("myrepo");
        store = new ForbidWithheldListStore(filesystem);

        pushBytes(gem("alpha", "1.0.0"));
        betaGem = gem("beta", "2.1.0");
        pushBytes(betaGem);
    }

    @AfterAll
    public void teardown() {
        System.clearProperty("jenreg.filesystem.root");
    }

    @Test
    public void versions_never_touches_the_withheld_namespace_and_a_hold_is_applied_on_its_own_write()
            throws IOException {
        // 1. Nothing held: both gems list, and the read neither lists nor pages the withheld namespace - the document
        //    is stored, and the holds are applied to it when they are written.
        String open = versionsBody();
        assertThat(open).as("both gems list while nothing is held")
                .contains("alpha 1.0.0").contains("beta 2.1.0");
        assertThat(store.listWithheld)
                .as("the /versions read NEVER materialises the whole withheld namespace via list(\"withheld\")")
                .isZero();
        assertThat(store.pageWithheld).as("nor does it page it").isZero();

        // 2. Retroactively withhold beta's gem blob. The hold's write screens beta out of the stored documents; the
        //    next /versions read serves them as stored.
        ArtifactDescriptor beta = ((BlobLayout) gems).describe("/rubygems/gems/beta-2.1.0.gem").orElseThrow();
        Withheld.mark(store, sha256(betaGem), beta);
        String held = versionsBody();
        assertThat(held).as("a held gem version is screened out of /versions (the filter still works)")
                .contains("alpha 1.0.0").doesNotContain("beta 2.1.0");
        assertThat(store.listWithheld).as("across the held read the whole withheld namespace is never listed")
                .isZero();
        assertThat(store.pageWithheld).as("nor paged").isZero();

        // 3. Releasing beta restores it, on the release's own write. The proof the clear seam takes: this fixture
        //    seeded exactly one holder and is releasing it, so "no other live holder claims these bytes" is an
        //    observed answer rather than an assumption.
        Withheld.clear(store, sha256(betaGem), Known.absent(), beta);
        assertThat(versionsBody()).as("releasing the hold re-lists the gem").contains("beta 2.1.0");
        assertThat(store.listWithheld)
                .as("no whole-namespace withheld list on ANY /versions read across the whole exercise")
                .isZero();
        assertThat(store.pageWithheld).as("and no page of it either").isZero();
    }

    private String versionsBody() throws IOException {
        Call read = new Call("GET", "/rubygems/versions");
        gems.handle(read, store);
        assertThat(read.status).as("/versions is served").isEqualTo(200);
        return new String(read.body(), StandardCharsets.UTF_8);
    }

    private void pushBytes(byte[] gemBytes) throws IOException {
        Call push = new Call("POST", "/rubygems/api/v1/gems", gemBytes);
        gems.handle(push, store);
        assertThat(push.status).as("gem push").isEqualTo(200);
    }

    /** A minimal .gem: a tar carrying a metadata.gz whose gzipped YAML is the gemspec the format reads. */
    private static byte[] gem(String name, String version) throws IOException {
        String gemspec = "--- !ruby/object:Gem::Specification\n"
                + "name: " + name + "\n"
                + "version: !ruby/object:Gem::Version\n  version: " + version + "\n"
                + "licenses:\n- MIT\n";
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(gemspec.getBytes(StandardCharsets.UTF_8));
        }
        byte[] metadata = gzipped.toByteArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("metadata.gz");
            entry.setSize(metadata.length);
            tar.putArchiveEntry(entry);
            tar.write(metadata);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * A delegating {@link ArtifactStore} that FORBIDS the whole-namespace {@code list("withheld")} (throwing, and
     * counting) while COUNTING and delegating {@code page("withheld", ...)}, so the test proves the /versions read
     * probes the withheld namespace a page at a time rather than materialising it whole. Every other operation
     * delegates untouched. Ported from {@code WithheldStampPagingTest}.
     */
    private static final class ForbidWithheldListStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        private final ArtifactStore delegate;
        private int listWithheld;
        private int pageWithheld;

        private ForbidWithheldListStore(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.equals("withheld") || prefix.startsWith("withheld/")) {
                listWithheld++;
                throw new AssertionError("the /versions read must not materialise the whole withheld namespace via "
                        + "list(\"" + prefix + "\") - it must page it (the denial-of-service fix)");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            if (prefix.equals("withheld") || prefix.startsWith("withheld/")) {
                pageWithheld++;
            }
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override public ArtifactStore scope(String tenant) { return delegate.scope(tenant); }
        @Override public boolean exists(String key) { return delegate.exists(key); }
        @Override public void read(String key, OutputStream out) throws IOException { delegate.read(key, out); }
        @Override public InputStream open(String key) throws IOException { return delegate.open(key); }
        @Override public void write(String key, InputStream in) throws IOException { delegate.write(key, in); }
        @Override public String writeBlob(InputStream in) throws IOException { return delegate.writeBlob(in); }
        @Override public long size(String key) throws IOException { return delegate.size(key); }
        @Override public void delete(String key) throws IOException { delegate.delete(key); }
        @Override public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }
        @Override public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
}
