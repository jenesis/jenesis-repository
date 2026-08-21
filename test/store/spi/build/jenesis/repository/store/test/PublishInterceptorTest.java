package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload post-processing gate on {@link Publication#screen}: the blob is always stored content-addressed, then the
 * interceptor chain's strongest {@link PublishInterceptor.Disposition} routes it - an accepted upload links no pointer
 * of its own (the screening edge owns the accepted write and links it), a quarantined one diverts to the
 * {@code /quarantine} view (stored but not served), a rejected one links nothing - and every interceptor is notified of
 * the outcome. Interceptors are passed explicitly here, since the core's ServiceLoader-discovered chain is empty.
 */
class PublishInterceptorTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ArtifactDescriptor descriptor(String path) {
        return ArtifactDescriptor.at("raw", path);
    }

    /** An interceptor that returns a fixed verdict and remembers the outcome it was told about. */
    private static final class Fixed implements PublishInterceptor {

        private final Disposition verdict;
        private Disposition committed;
        private ArtifactStore committedStore;

        Fixed(Disposition verdict) {
            this.verdict = verdict;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) {
            return verdict;
        }

        @Override
        public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store) {
            this.committed = disposition;
            this.committedStore = store;
        }
    }

    @Test
    void an_empty_chain_accepts_and_the_caller_links_the_artifact() throws IOException {
        Publication publication = new Publication(store, List.of());
        Publication.Published screened = publication.screen(descriptor("/raw/a"), bytes("payload"));

        assertThat(screened.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(store.exists("blobs/" + screened.hash())).isTrue();
        assertThat(publication.located("/raw/a")).as("screen links nothing itself").isEmpty();

        publication.link("/raw/a", screened.hash());
        assertThat(publication.located("/raw/a")).as("the accepted write is the caller's link")
                .contains("blobs/" + screened.hash());
    }

    @Test
    void a_quarantine_verdict_stores_the_blob_but_diverts_the_pointer() throws IOException {
        Fixed interceptor = new Fixed(PublishInterceptor.Disposition.QUARANTINE);
        Publication publication = new Publication(store, List.of(interceptor));

        Publication.Published published = publication.screen(descriptor("/raw/held"), bytes("suspect"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(store.exists("blobs/" + published.hash())).as("the blob is stored").isTrue();
        assertThat(publication.located("/raw/held")).as("but not served at its path").isEmpty();
        assertThat(publication.located("/quarantine/raw/held")).as("only under the quarantine view")
                .contains("blobs/" + published.hash());
        assertThat(interceptor.committed).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(interceptor.committedStore).as("the outcome carries the scoped store").isSameAs(store);
    }

    @Test
    void a_screened_upload_is_stored_but_the_accepted_link_is_the_callers() throws IOException {
        Fixed interceptor = new Fixed(PublishInterceptor.Disposition.ACCEPT);
        Publication publication = new Publication(store, List.of(interceptor));

        Publication.Published screened = publication.screen(descriptor("/raw/external"), bytes("laid-out-elsewhere"));

        assertThat(screened.disposition()).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
        assertThat(store.exists("blobs/" + screened.hash())).as("stored content-addressed for the caller").isTrue();
        assertThat(publication.located("/raw/external")).as("but linked by the caller, not the screen").isEmpty();
        assertThat(interceptor.committed).isEqualTo(PublishInterceptor.Disposition.ACCEPT);
    }

    @Test
    void a_screened_upload_still_diverts_to_quarantine_on_that_verdict() throws IOException {
        Publication publication = new Publication(store,
                List.of(new Fixed(PublishInterceptor.Disposition.QUARANTINE)));

        Publication.Published screened = publication.screen(descriptor("/raw/suspect"), bytes("held"));

        assertThat(screened.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        assertThat(publication.located("/raw/suspect")).isEmpty();
        assertThat(publication.located("/quarantine/raw/suspect")).as("reviewable under the quarantine view")
                .contains("blobs/" + screened.hash());
    }

    @Test
    void a_reject_verdict_stores_the_blob_but_links_no_pointer() throws IOException {
        Publication publication = new Publication(store, List.of(new Fixed(PublishInterceptor.Disposition.REJECT)));

        Publication.Published published = publication.screen(descriptor("/raw/bad"), bytes("malware"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.REJECT);
        assertThat(store.exists("blobs/" + published.hash())).as("the orphan blob is left for GC").isTrue();
        assertThat(publication.located("/raw/bad")).isEmpty();
        assertThat(publication.located("/quarantine/raw/bad")).isEmpty();
    }

    @Test
    void the_strongest_disposition_across_the_chain_wins() throws IOException {
        Publication publication = new Publication(store, List.of(
                new Fixed(PublishInterceptor.Disposition.ACCEPT),
                new Fixed(PublishInterceptor.Disposition.QUARANTINE),
                new Fixed(PublishInterceptor.Disposition.ACCEPT)));

        Publication.Published published = publication.screen(descriptor("/raw/mixed"), bytes("x"));

        assertThat(published.disposition()).isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
    }

    @Test
    void screens_run_sorted_by_their_declared_order() throws IOException {
        List<String> sequence = new ArrayList<>();
        class Positioned implements PublishInterceptor {
            private final String name;
            private final int order;

            Positioned(String name, int order) {
                this.name = name;
                this.order = order;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) {
                sequence.add(name);
                return Disposition.ACCEPT;
            }
        }
        Publication publication = new Publication(store, List.of(
                new Positioned("last", 10), new Positioned("first", -10), new Positioned("middle", 0)));

        publication.screen(descriptor("/raw/ordered"), bytes("x"));

        assertThat(sequence).containsExactly("first", "middle", "last");
    }

    @Test
    void a_withholding_screen_retracts_a_published_path_from_serving() throws IOException {
        Publication publication = new Publication(store, List.of());
        publication.link("/raw/served", publication.screen(descriptor("/raw/served"), bytes("fine")).hash());
        publication.link("/raw/retracted",
                publication.screen(descriptor("/raw/retracted"), bytes("later-flagged")).hash());

        Publication screened = new Publication(store, List.of(new PublishInterceptor() {
            @Override
            public boolean withheld(String path, ArtifactStore store) {
                return path.equals("/raw/retracted");
            }
        }));

        assertThat(screened.located("/raw/served")).as("an unflagged path still serves").isPresent();
        assertThat(screened.located("/raw/retracted")).as("the flagged path is withheld").isEmpty();
        assertThat(screened.blob("/raw/retracted")).as("but its pointer is untouched").isPresent();
    }

    @Test
    void the_content_view_reads_the_just_stored_blob_and_a_published_sibling() throws IOException {
        Publication seed = new Publication(store, List.of());
        seed.link("/raw/pom", seed.screen(descriptor("/raw/pom"), bytes("the-pom")).hash());

        List<String> seenSibling = new ArrayList<>();
        PublishInterceptor reader = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                try (InputStream in = content.open()) {
                    assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("the-jar");
                }
                content.sibling("/raw/pom").ifPresent(b -> seenSibling.add(new String(b, StandardCharsets.UTF_8)));
                return Disposition.ACCEPT;
            }
        };
        new Publication(store, List.of(reader)).screen(descriptor("/raw/jar"), bytes("the-jar"));

        assertThat(seenSibling).containsExactly("the-pom");
    }

    /** Publish {@code size} bytes at {@code path} so a later screen can read it as an already-published sibling. The
     *  content is deterministic (the index modulo 251) so a bounded read's bytes can be checked to be the real leading
     *  bytes of the sibling rather than merely the right length. */
    private void seedSibling(String path, int size) throws IOException {
        byte[] content = new byte[size];
        for (int index = 0; index < size; index++) {
            content[index] = (byte) (index % 251);
        }
        Publication seed = new Publication(store, List.of());
        seed.link(path, seed.screen(descriptor(path), new ByteArrayInputStream(content)).hash());
    }

    /** Run {@code read} inside a screen's {@code assess}, so the bounded sibling seam is exercised exactly where a
     *  compliance gate would use it - on the publish leg, against the publication's own scoped store. */
    private void screening(SiblingRead read) throws IOException {
        PublishInterceptor reader = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                read.accept(content);
                return Disposition.ACCEPT;
            }
        };
        new Publication(store, List.of(reader)).screen(descriptor("/raw/jar"), bytes("the-jar"));
    }

    @FunctionalInterface
    private interface SiblingRead {
        void accept(PublishInterceptor.Content content) throws IOException;
    }

    @Test
    void a_bounded_sibling_read_honours_a_limit_above_the_whole_document_ceiling() throws IOException {
        //. The bounded read's bound is the CALLER's, not this seam's. A companion between the whole-document
        // ceiling (8 MiB) and the caller's own inspection window is exactly the case that used to divide the two
        // ingress legs: a proxy screen streaming its own bound returned the sibling whole and hashable, while the
        // publish leg - whose only route was the whole-document read - raised out of the interceptor chain and failed
        // the publish outright. The two legs now answer identically because the publish leg has a real bounded read of
        // its own, capped where the caller said and nowhere else.
        int size = 9 * 1024 * 1024;         // above LARGEST_SIBLING...
        int limit = 32 * 1024 * 1024;       // ...and comfortably below the caller's window
        seedSibling("/raw/fat-companion", size);

        List<PublishInterceptor.Content.Bounded> read = new ArrayList<>();
        screening(content -> content.sibling("/raw/fat-companion", limit).ifPresent(read::add));

        assertThat(read).hasSize(1);
        assertThat(read.getFirst().truncated())
                .as("the sibling fits the caller's bound, so nothing was cut off and a digest over it is the "
                        + "companion's real digest")
                .isFalse();
        assertThat(read.getFirst().content())
                .as("every byte of the companion is handed over, not the whole-document ceiling's worth")
                .hasSize(size);
        assertThat(read.getFirst().content()[size - 1]).isEqualTo((byte) ((size - 1) % 251));
    }

    @Test
    void a_bounded_sibling_read_reports_an_over_bound_companion_instead_of_failing_on_it() throws IOException {
        // The bound fails VISIBLY but not fatally: the caller asked to be told about the overflow, so it is told -
        // an explicit truncated result - rather than being handed an exception it would have to translate back into
        // "there was more". Size alone never raises on this seam, whatever LARGEST_SIBLING says.
        seedSibling("/raw/fat-companion", 9 * 1024 * 1024);
        int limit = 1024 * 1024;

        List<PublishInterceptor.Content.Bounded> read = new ArrayList<>();
        screening(content -> content.sibling("/raw/fat-companion", limit).ifPresent(read::add));

        assertThat(read).hasSize(1);
        assertThat(read.getFirst().truncated()).as("the caller is told a fact derived from the whole is unconfirmable")
                .isTrue();
        assertThat(read.getFirst().content()).as("and holds exactly the bound it asked for, never more")
                .hasSize(limit);
        assertThat(read.getFirst().content()[limit - 1]).isEqualTo((byte) ((limit - 1) % 251));
    }

    @Test
    void a_sibling_of_exactly_the_limit_is_reported_whole_rather_than_pessimistically_truncated() throws IOException {
        // The one-byte-past read is what buys this: at exactly the bound the caller really does hold every byte, so
        // flagging it truncated would refuse a confirmation that is genuinely available. An implementation that can
        // only see a limit-length buffer cannot tell this case from an over-bound one and has to guess - which is why
        // the bounded read is implemented against the store rather than layered over the whole-document read.
        int size = 512 * 1024;
        seedSibling("/raw/exact-companion", size);

        List<PublishInterceptor.Content.Bounded> read = new ArrayList<>();
        screening(content -> content.sibling("/raw/exact-companion", size).ifPresent(read::add));

        assertThat(read).hasSize(1);
        assertThat(read.getFirst().truncated()).isFalse();
        assertThat(read.getFirst().content()).hasSize(size);
    }

    @Test
    void a_bounded_sibling_read_of_an_unpublished_path_is_empty_not_an_empty_document() throws IOException {
        // Absence stays distinguishable from a zero-length read: a companion published before its artifact lands is
        // "not there yet", which a gate defers on, while an empty one is a document that declares nothing.
        List<Optional<PublishInterceptor.Content.Bounded>> read = new ArrayList<>();
        screening(content -> read.add(content.sibling("/raw/never-published", 4096)));

        assertThat(read).hasSize(1);
        assertThat(read.getFirst()).isEmpty();
    }

    @Test
    void the_sibling_view_refuses_an_oversized_sibling_rather_than_buffering_a_whole_artifact() throws IOException {
        // A sibling read WHOLE is small published metadata a gate inspects beside the artifact (a jar reading its
        // POM); it must never be turned into a lever to buffer an arbitrarily large blob into the heap. Its caller
        // has no use for half a POM, so an over-cap sibling fails loudly instead of materialising - returning a
        // prefix under a bound the caller never named would be the silently-incomplete answer. That failure is the
        // whole-document read's alone: the very same sibling is readable through the bounded seam (asserted above),
        // which is the seam a caller that can account for a prefix uses.
        Publication seed = new Publication(store, List.of());
        seed.link("/raw/huge",
                seed.screen(descriptor("/raw/huge"), new ByteArrayInputStream(new byte[9 * 1024 * 1024])).hash());

        PublishInterceptor reader = new PublishInterceptor() {
            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                content.sibling("/raw/huge");
                return Disposition.ACCEPT;
            }
        };
        assertThatThrownBy(() -> new Publication(store, List.of(reader))
                .screen(descriptor("/raw/jar"), bytes("the-jar")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sibling");
    }
}
