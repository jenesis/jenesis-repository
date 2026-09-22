package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.store.ServableNames;

import static org.assertj.core.api.Assertions.assertThat;
import static build.jenesis.repository.gateway.testkit.FormatDrive.Call;
import static build.jenesis.repository.gateway.testkit.FormatDrive.MemStore;
import static build.jenesis.repository.gateway.testkit.FormatDrive.format;

/**
 * Pins the RubyGems compact-index {@code /versions} read-first fix: the whole-index scan (every gem, every
 * version blob, an MD5 per gem) is done once by the push and cached content-addressed, so the hot read serves the
 * stored blob with an {@code ETag} rather than re-scanning on every request. Three load-bearing cells, all hermetic
 * (no Ruby toolchain, no network):
 *
 * <ul>
 *   <li>the {@code /versions} list is served from the precomputed cache, not recomputed - proven by a counting store
 *       that records every {@code list} of the {@code rubygems/} tree and sees none during the read;</li>
 *   <li>a matching {@code If-None-Match} is answered {@code 304} with no body and no scan;</li>
 *   <li>the {@code ETag} the read emits is the content address of the served body, so it is stable across reads and
 *       changes exactly when a new push changes the document.</li>
 * </ul>
 */
class RubyGemsVersionsCacheTest {

    @Test
    void the_versions_list_is_served_from_the_precomputed_cache_never_re_scanned() throws IOException {
        CountingStore store = new CountingStore();
        RepositoryFormat gems = format("rubygems");
        push(gems, store, "alpha", "1.0.0");
        push(gems, store, "beta", "2.1.0");

        // A read after the pushes: the body lists both gems and carries an ETag - and, load-bearing, it touched not a
        // single listing of the rubygems/ tree (the O(gems x versions) walk the writer already paid).
        store.listsOfGemTree.set(0);
        Call read = new Call("GET", "/rubygems/versions");
        gems.handle(read, store);

        assertThat(read.status).isEqualTo(200);
        String body = new String(read.body(), StandardCharsets.UTF_8);
        assertThat(body).as("the compact index lists both pushed gems")
                .contains("alpha 1.0.0 ").contains("beta 2.1.0 ");
        assertThat(read.responseHeader("ETag")).as("the read emits a revalidatable ETag").isNotNull();
        assertThat(store.listsOfGemTree.get())
                .as("the cached read re-scans no gem: it served the precomputed /versions blob")
                .isZero();
    }

    @Test
    void a_matching_if_none_match_is_a_cheap_304() throws IOException {
        CountingStore store = new CountingStore();
        RepositoryFormat gems = format("rubygems");
        push(gems, store, "alpha", "1.0.0");

        Call first = new Call("GET", "/rubygems/versions");
        gems.handle(first, store);
        String etag = first.responseHeader("ETag");
        assertThat(etag).isNotNull();

        store.listsOfGemTree.set(0);
        Call revalidate = new Call("GET", "/rubygems/versions").header("If-None-Match", etag);
        gems.handle(revalidate, store);

        assertThat(revalidate.status).as("a matching validator is answered 304, not a full 200").isEqualTo(304);
        assertThat(revalidate.body()).as("a 304 carries no body").isEmpty();
        assertThat(revalidate.responseHeader("ETag")).isEqualTo(etag);
        assertThat(store.listsOfGemTree.get()).as("a 304 reads neither the body nor any gem").isZero();
    }

    @Test
    void the_etag_is_the_content_address_and_moves_only_when_the_index_changes() throws IOException {
        MemStore store = new MemStore();
        RepositoryFormat gems = format("rubygems");
        push(gems, store, "alpha", "1.0.0");

        String first = etag(gems, store);
        assertThat(etag(gems, store)).as("the ETag is stable across reads with no publish between them")
                .isEqualTo(first);

        push(gems, store, "alpha", "1.1.0");
        assertThat(etag(gems, store)).as("a new version changes the document, so the content-address ETag moves")
                .isNotEqualTo(first);
    }

    @Test
    void a_hold_is_applied_to_the_stored_index_on_its_own_write_and_the_read_scans_nothing() throws IOException {
        CountingStore store = new CountingStore();
        RepositoryFormat gems = format("rubygems");
        push(gems, store, "alpha", "1.0.0");
        push(gems, store, "beta", "2.1.0");
        Call before = new Call("GET", "/rubygems/versions");
        gems.handle(before, store);
        assertThat(new String(before.body(), StandardCharsets.UTF_8)).contains("beta 2.1.0 ");

        // A retroactive hold of beta's gem: the hold's write re-decides beta's entry in the stored documents, so the
        // next read serves the screened body without scanning a single gem - one hold never turns every bundler
        // /versions GET into a whole-repository scan.
        String hash = ServableNames.hash(store.readVersioned("rubygemfiles/beta-2.1.0.gem").orElseThrow().content());
        Withheld.mark(store, hash, ((BlobLayout) gems).describe("/rubygems/gems/beta-2.1.0.gem").orElseThrow());

        store.listsOfGemTree.set(0);
        Call held = new Call("GET", "/rubygems/versions");
        gems.handle(held, store);
        assertThat(held.status).isEqualTo(200);
        assertThat(new String(held.body(), StandardCharsets.UTF_8))
                .as("the held gem is screened out of the stored compact index")
                .contains("alpha 1.0.0 ").doesNotContain("beta 2.1.0 ");
        assertThat(held.responseHeader("ETag")).as("the hold moved the ETag").isNotEqualTo(before.responseHeader("ETag"));
        assertThat(store.listsOfGemTree.get()).as("the read after the hold scans no gem").isZero();
    }

    private static String etag(RepositoryFormat gems, MemStore store) throws IOException {
        Call read = new Call("GET", "/rubygems/versions");
        gems.handle(read, store);
        assertThat(read.status).isEqualTo(200);
        return read.responseHeader("ETag");
    }

    private static void push(RepositoryFormat gems, MemStore store, String name, String version) throws IOException {
        Call push = new Call("POST", "/rubygems/api/v1/gems", gem(name, version));
        gems.handle(push, store);
        assertThat(push.status).as("gem push " + name + "-" + version).isEqualTo(200);
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

    /** A {@link MemStore} that counts every {@code list} of the {@code rubygems/} version tree - the full re-walk the
     *  precompute exists to keep off the read path (never the {@code rubygemsindex/} cache pointer's own lookups). */
    private static final class CountingStore extends MemStore {

        final AtomicInteger listsOfGemTree = new AtomicInteger();

        @Override
        public List<String> list(String prefix) {
            if (prefix.equals("rubygems") || prefix.startsWith("rubygems/")) {
                listsOfGemTree.incrementAndGet();
            }
            return super.list(prefix);
        }
    }
}
