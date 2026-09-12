package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The content-addressed publication model over a real filesystem store on a {@code @TempDir}: a blob is stored once by
 * its SHA-256 and any number of request paths point at it, so a republish is a pointer update, an unpublish drops only
 * the pointer, and a path whose blob is gone resolves to nothing rather than a dangling key.
 */
class PublicationTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The pointer records the blob's length beside its hash, and a serve reads the length off the pointer rather
     * than off the blob: {@code locate} stats nothing, {@code blob} still answers the bare hash whatever the body
     * carries (the composition {@code "blobs/" + hash} depends on it), and a pointer written before the length was
     * recorded answers no length rather than a stat - the reconcile pass regenerates it. Measured 2026-09-12: the
     * stat was the fifth of a download's five reads on every backing.
     */
    @Test
    void the_pointer_records_the_length_and_a_serve_reads_it_there() throws IOException {
        Publication publication = new Publication(store, List.of());
        String hash = publication.storeBlob(bytes("twelve bytes"));
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        Publication counted = new Publication(counting, List.of());

        counted.link("/raw/sized.bin", hash, 12L);
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).as("a link handed the length stats nothing").isZero();
        assertThat(new String(store.readVersioned("publish/raw/sized.bin").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("the pointer body is the hash and the length").isEqualTo(hash + " 12");
        assertThat(counted.blob("/raw/sized.bin")).as("blob() answers the bare hash, not the body").contains(hash);
        assertThat(counted.locate("/raw/sized.bin")).as("a serve reads the length off the pointer")
                .contains(new Publication.Located("blobs/" + hash, 12L));
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).as("and stats no blob for it").isZero();

        counted.link("/raw/counted.bin", hash);
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).as("a link without the length stats it once").isEqualTo(1);
        assertThat(counted.locate("/raw/counted.bin")).contains(new Publication.Located("blobs/" + hash, 12L));
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).as("and the serve still stats nothing").isEqualTo(1);

        store.writeVersioned("publish/raw/older.bin", hash.getBytes(StandardCharsets.UTF_8), null);
        assertThat(counted.locate("/raw/older.bin")).as("a pointer without a length is served without one, never "
                + "through a stat").contains(new Publication.Located("blobs/" + hash, -1L));
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).isEqualTo(1);
    }

    @Test
    void a_sidecar_of_a_held_artifact_is_invisible_to_a_serving_read_and_visible_to_a_held_one() throws IOException {
        // The two content views, and the deadlock the second one exists to break. A sidecar is withheld by its
        // subject's hold, so an artifact held FOR THE WANT of its signature cannot be released by that signature
        // arriving: the re-assessment asks what a client would see, is answered by the hold it is deciding about, and
        // confirms it. Reading the stored pointer instead is what lets the evidence be read back exactly once, by the
        // gate deciding whether to release - never by a client, which is the leg below.
        String held = publication.storeBlob(bytes("the artifact"));
        String signature = publication.storeBlob(bytes("the signature over it"));
        Publication withholding = new Publication(store, List.of(new Withholding("/maven/a/lib-1.0.jar")));
        withholding.link("/maven/a/lib-1.0.jar", held);
        withholding.link("/maven/a/lib-1.0.jar.asc", signature);

        assertThat(withholding.located("/maven/a/lib-1.0.jar.asc"))
                .as("a sidecar is withheld by its subject's hold, so no client reads it")
                .isEmpty();
        assertThat(withholding.contentOf(held).sibling("/maven/a/lib-1.0.jar.asc"))
                .as("the serving view answers the same way, which is what made the hold self-confirming")
                .isEmpty();
        assertThat(withholding.heldContentOf(held).sibling("/maven/a/lib-1.0.jar.asc"))
                .as("the held view reads what is stored, so the evidence that would release the hold is legible")
                .isPresent();
    }

    /** An interceptor that withholds one path - the shape a compliance hold has from this module's point of view,
     *  which knows nothing about why anything is held. */
    private record Withholding(String path) implements PublishInterceptor {

        @Override
        public boolean withheld(String requestPath, ArtifactStore store) {
            return path.equals(requestPath);
        }
    }

    @Test
    void a_stored_blob_is_located_through_the_pointer_it_is_linked_under() throws IOException {
        String hash = publication.storeBlob(bytes("payload"));
        assertThat(store.exists("blobs/" + hash)).isTrue();
        assertThat(publication.located("/raw/a/b")).as("an unlinked path resolves to nothing").isEmpty();

        publication.link("/raw/a/b", hash);
        assertThat(publication.blob("/raw/a/b")).contains(hash);
        assertThat(publication.located("/raw/a/b")).contains("blobs/" + hash);
    }

    @Test
    void identical_content_dedupes_to_one_blob_shared_by_several_paths() throws IOException {
        String first = publication.storeBlob(bytes("same"));
        String second = publication.storeBlob(bytes("same"));
        assertThat(second).as("identical content addresses to one blob").isEqualTo(first);

        publication.link("/module/x/1/x.jar", first);
        publication.link("/module/x/x.jar", first);
        assertThat(publication.located("/module/x/1/x.jar")).contains("blobs/" + first);
        assertThat(publication.located("/module/x/x.jar")).contains("blobs/" + first);
    }

    @Test
    void a_republish_is_a_pointer_update() throws IOException {
        String one = publication.storeBlob(bytes("one"));
        String two = publication.storeBlob(bytes("two"));
        publication.link("/raw/p", one);
        assertThat(publication.blob("/raw/p")).contains(one);

        publication.link("/raw/p", two);
        assertThat(publication.blob("/raw/p")).as("the pointer now names the new blob").contains(two);
        assertThat(publication.located("/raw/p")).contains("blobs/" + two);
    }

    @Test
    void a_link_that_loses_a_compare_and_set_retries_and_lands() throws IOException {
        String hash = publication.storeBlob(bytes("contended"));
        ConflictingStore contended = new ConflictingStore(store, 1);

        new Publication(contended).link("/raw/contended", hash);

        assertThat(contended.conflicts).as("the first compare-and-set was made to conflict").isZero();
        assertThat(publication.blob("/raw/contended"))
                .as("the losing write re-read the token and retried rather than silently dropping").contains(hash);
    }

    @Test
    void a_link_that_cannot_land_surfaces_rather_than_silently_dropping() throws IOException {
        String hash = publication.storeBlob(bytes("unlandable"));
        Publication contended = new Publication(new ConflictingStore(store, Integer.MAX_VALUE));

        assertThatThrownBy(() -> contended.link("/raw/unlandable", hash))
                .as("persistent conflicts are an error the caller hears about, never a lost publish")
                .isInstanceOf(IOException.class);
        assertThat(publication.blob("/raw/unlandable")).isEmpty();
    }

    /** A store whose next {@code n} versioned writes report a benign compare-and-set conflict, then behave. */
    private static final class ConflictingStore implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }


        private final ArtifactStore delegate;
        int conflicts;

        private ConflictingStore(ArtifactStore delegate, int conflicts) {
            this.delegate = delegate;
            this.conflicts = conflicts;
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            if (conflicts > 0) {
                conflicts--;
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

    @Test
    void unpublish_removes_the_pointer_and_located_reflects_a_missing_blob() throws IOException {
        String hash = publication.storeBlob(bytes("gone"));
        publication.link("/raw/q", hash);

        publication.unpublish("/raw/q");
        assertThat(publication.blob("/raw/q")).isEmpty();
        assertThat(publication.located("/raw/q")).isEmpty();

        publication.link("/raw/r", hash);
        store.delete("blobs/" + hash);
        assertThat(publication.blob("/raw/r")).as("the pointer still exists").contains(hash);
        // The pointer says servable and locate believes it: a serve learns the blob is gone from the open it makes
        // before committing its response (a clean 404, held by the format contract), not from a stat here - that
        // stat was the fifth read of every download and proved only what the open proves anyway. The enumeration
        // face, which lists rather than opens, is the one that still tells a torn pointer apart.
        assertThat(publication.located("/raw/r")).as("locate answers the pointer, and the open answers the blob")
                .contains("blobs/" + hash);
        assertThat(new ServableNames(store, publication).state("/raw/r"))
                .as("the enumeration face still sees the torn pointer for what it is")
                .isEqualTo(ServableNames.State.BLOB_GONE);
        assertThatThrownBy(() -> store.open("blobs/" + hash))
                .as("and the open a serve makes before it commits is the typed absence it turns into a 404")
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void linking_a_blob_clears_a_garbage_collectors_condemned_marker() throws IOException {
        // Identical content dedupes to one blob, so a "new" publish may link a blob a collector already judged
        // unreferenced; the link clears the condemned marker on the write path, before the collecting sweep's
        // final marker re-read - the dedup re-publish guard of the condemn-then-collect contract.
        String hash = publication.storeBlob(bytes("payload"));
        store.writeVersioned("gc/condemned/" + hash,
                "pass=1\nsince=2026-07-16T00:00:00Z".getBytes(StandardCharsets.UTF_8), null);

        publication.link("/raw/back", hash);
        assertThat(store.exists("gc/condemned/" + hash))
                .as("a re-linked blob is un-condemned the moment its pointer lands").isFalse();
        assertThat(publication.located("/raw/back")).contains("blobs/" + hash);
    }
}
