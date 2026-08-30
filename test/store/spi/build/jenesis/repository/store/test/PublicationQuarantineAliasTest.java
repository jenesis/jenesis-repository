package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Publication#quarantineAlias} (Audit-26 F3, three-valued by): the free-store cross-alias scan an automated
 * content-addressed marker clear runs before lifting the marker. It walks the {@code publish/quarantine} pointer subtree
 * for any live review pointer OUTSIDE the caller's own served paths whose body is the hash, short-circuiting on the
 * first, and propagating a genuine store {@link IOException} so the caller fails closed (does not clear).
 *
 * <p>The third answer is the point of this suite. A garbled pointer key used to be skipped and reported as a clean
 * "no other alias", which is precisely the answer that lifts a content-addressed hold - so the one entry the scan
 * could not read was allowed to be the very alias that should have kept the marker. It is still contained (one bad
 * entry never throws out of the guard) but it is now recorded, and the scan answers {@link Known.Unknown}, which does
 * not fit {@code Withheld.clear}'s parameter at all.
 */
class PublicationQuarantineAliasTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;

    private static final String HASH = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    private void quarantine(String servedPath, String hash) throws IOException {
        store.write("publish/quarantine" + servedPath, new ByteArrayInputStream(hash.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_live_sibling_pointer_holding_the_hash_is_found() throws IOException {
        quarantine("/v2/sibling/app/manifests/1.0", HASH);
        assertThat(publication.quarantineAlias(HASH, Set.of("/v2/self/app/manifests/1.0")))
                .as("a sibling review pointer whose body is the hash keeps the marker, and names itself")
                .isEqualTo(Known.known("/v2/sibling/app/manifests/1.0"));
    }

    @Test
    void an_empty_quarantine_subtree_holds_nothing() throws IOException {
        assertThat(publication.quarantineAlias(HASH, Set.of())).isEqualTo(Known.absent());
    }

    @Test
    void the_callers_own_excluded_path_does_not_count_as_an_alias() throws IOException {
        quarantine("/v2/self/app/manifests/1.0", HASH);
        assertThat(publication.quarantineAlias(HASH, Set.of("/v2/self/app/manifests/1.0")))
                .as("the caller's own pointer is excluded, so it does not hold the marker on its own account")
                .isEqualTo(Known.absent());
    }

    @Test
    void a_sibling_pointer_in_the_qualified_dialect_is_still_an_alias() throws IOException {
        // A stored pointer body carries either the bare lower-case hex or the algorithm-qualified sha256:<hex> of the
        // OCI Distribution digests; both name the same blob and both must count. Compared raw, a qualified body reads
        // unequal, the scan reports NO other alias and the caller clears a withheld/<hash> marker a sibling coordinate
        // still holds - a fail-OPEN disclosure. Reading the body through ServableNames.hash only ever finds MORE
        // aliases, so it only ever narrows the clear.
        quarantine("/v2/sibling/app/manifests/1.0", "sha256:" + HASH);
        assertThat(publication.quarantineAlias(HASH, Set.of("/v2/self/app/manifests/1.0")))
                .as("a qualified sibling body names the same blob, so the marker stays")
                .isEqualTo(Known.known("/v2/sibling/app/manifests/1.0"));
    }

    @Test
    void a_pointer_holding_a_different_hash_is_not_an_alias() throws IOException {
        quarantine("/v2/sibling/app/manifests/1.0", OTHER);
        assertThat(publication.quarantineAlias(HASH, Set.of())).isEqualTo(Known.absent());
    }

    @Test
    void a_garbled_pointer_key_is_contained_but_never_reported_as_a_clean_negative() throws IOException {
        // A pointer whose read throws a RuntimeException (an InvalidPathException out of a hostile key, or an
        // unreadable container) is contained: the scan never propagates it out of the guard. What it must NOT do is
        // answer "no other alias" - that answer lifts a content-addressed hold, and the entry it could not read is
        // exactly where the alias that should have kept the marker would have been.
        Known<String> answer = new Publication(new WalkStore(new InvalidPathException("x", "hostile"), null))
                .quarantineAlias(HASH, Set.of());

        assertThat(answer).isInstanceOf(Known.Unknown.class);
        assertThat(((Known.Unknown<String>) answer).cause()).isEqualTo(Known.Cause.FAILED);
        assertThat(((Known.Unknown<String>) answer).detail()).contains("did not enumerate whole");
        assertThat(answer).isNotEqualTo(Known.absent());
    }

    @Test
    void a_live_alias_found_before_an_unreadable_entry_is_still_a_determinate_answer() throws IOException {
        // Finding a holder settles the question whatever else the scan could not read: the marker stays either way,
        // and naming the holder is more useful than refusing.
        quarantine("/v2/sibling/app/manifests/1.0", HASH);

        assertThat(publication.quarantineAlias(HASH, Set.of()))
                .isEqualTo(Known.known("/v2/sibling/app/manifests/1.0"));
    }

    @Test
    void a_genuine_store_io_exception_propagates_so_the_caller_fails_closed() {
        IOException failure = new IOException("store down");
        assertThatThrownBy(() -> new Publication(new WalkStore(null, failure))
                .quarantineAlias(HASH, Set.of()))
                .as("an IOException propagates: the caller does NOT clear (fail-closed)").isSameAs(failure);
    }

    @Test
    void the_release_seam_accepts_only_an_answered_proof() {
        // The other half of the fix, asserted where a non-compiling test could not be: Withheld.clear takes the
        // cross-alias proof as a Known.Determined, so an Unknown fits no overload and a release that cannot prove
        // holderlessness is a compile error. The single-overload check is the load-bearing one - the defect would
        // return the moment a convenience clear(store, hash) reappeared beside it.
        List<Method> clears = Arrays.stream(Withheld.class.getMethods())
                .filter(method -> method.getName().equals("clear"))
                .toList();

        assertThat(clears).isNotEmpty().allSatisfy(clear -> assertThat(clear.getParameterTypes())
                .as("every clear overload takes the proof as a Known.Determined")
                .contains(Known.Determined.class));
        assertThat(Known.Determined.class.isAssignableFrom(Known.Unknown.class))
                .as("an unanswerable proof must not widen into the release seam").isFalse();
    }

    /** A store presenting one {@code publish/quarantine/v2/app/manifests/1.0} leaf whose {@code readVersioned} throws the
     *  configured RuntimeException or IOException, exercising the scan's contain-vs-propagate split; every other method
     *  is inert. */
    private static final class WalkStore implements ArtifactStore {

        private final RuntimeException runtimeOnRead;
        private final IOException ioOnRead;

        WalkStore(RuntimeException runtimeOnRead, IOException ioOnRead) {
            this.runtimeOnRead = runtimeOnRead;
            this.ioOnRead = ioOnRead;
        }

        @Override
        public List<String> list(String prefix) {
            return switch (prefix) {
                case "publish/quarantine" -> List.of("v2");
                case "publish/quarantine/v2" -> List.of("app");
                case "publish/quarantine/v2/app" -> List.of("manifests");
                case "publish/quarantine/v2/app/manifests" -> List.of("1.0");
                default -> List.of();
            };
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            if (key.equals("publish/quarantine/v2/app/manifests/1.0")) {
                if (ioOnRead != null) {
                    throw ioOnRead;
                }
                if (runtimeOnRead != null) {
                    throw runtimeOnRead;
                }
            }
            return Optional.empty();
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void write(String key, InputStream in) {
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size(String key) {
            return -1L;
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
