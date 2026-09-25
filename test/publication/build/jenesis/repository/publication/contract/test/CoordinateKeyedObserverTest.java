package build.jenesis.repository.publication.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.index.PublishedIndex;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.webhook.WebhookOutbox;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the properties the fixtures exclude are proven instead.
 *
 * <p><b>Why a second test exists at all - and why it is not a way round gate 3.</b> The shared contract mints every
 * artifact it publishes as {@code ArtifactDescriptor.at("kit", path)} and lays it out through
 * {@code Visibility.at(...)}, which sets no {@code described}, so the descriptor that reaches an after-commit observer
 * carries a path and a blob identity and <b>no coordinate and no version</b>. The event observer skips exactly that
 * shape - deliberately, because it is what a checksum or a generated sidecar looks like - and the index retraction and
 * the provenance reaper have no publish leg at all. Their surfaces therefore cannot move inside the kit, whatever the
 * fixture does, which is a limit of the kit rather than a gap in the hooks.
 *
 * <p>So these checks drive the same instances through {@link Publication#published} - the store SPI's own after-commit
 * seam, documented as "the sole seam that carries {@code PublicationObserver#onPublished}" and the one every
 * blobs-namespace ingress edge already fires - and through {@code Publication.link} / {@code unpublish} for the
 * withhold faces. Nothing here assembles a publish choreography: the pointer is linked with the store primitive and the
 * notification is the store SPI's own, so the observer sees precisely what a real edge hands it.
 */
class CoordinateKeyedObserverTest {

    private static final String BODY = "the-artifact-body";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null).scope("acme").scope("main");
        PublicationHookContractTest.reset();
    }

    @AfterEach
    void tearDown() {
        PublicationHookContractTest.reset();
    }

    // --- the webhook: durable on return, and no route back --------------------------------------------------------

    @Test
    void a_webhook_note_is_durable_when_the_callback_returns_and_the_drain_delivers_it() throws IOException {
        PublicationEventFixture fixture = new PublicationEventFixture();
        fixture.deploy(store);
        ArtifactDescriptor artifact = coordinate(publish("/kit/app-1.0.jar"), "com.acme:app", "1.0");

        fixture.create().onPublished(artifact, store);

        assertThat(fixture.enqueued(store))
                .as("the note survives the process before the producer resumes - that is what durable-after-enqueue "
                        + "means, and it is read back out of the store rather than out of the instance")
                .hasSize(1);
        fixture.drain(store);
        assertThat(fixture.enqueued(store)).as("and the drain consumes it").isEmpty();
        fixture.drain(store);
        assertThat(fixture.enqueued(store)).as("a repeated drain converges rather than doubling").isEmpty();
    }

    @Test
    void a_dropped_webhook_call_leaves_no_route_back() throws IOException {
        new PublicationEventFixture().deploy(store);
        ArtifactDescriptor artifact = coordinate(publish("/kit/app-1.0.jar"), "com.acme:app", "1.0");
        // The callback never ran: the visibility write landed and the process died before the notify.

        assertThat(new WebhookOutbox(store).entries())
                .as("nothing was enqueued, because the callback never ran")
                .isEmpty();
        assertThat(store.readVersioned("publish" + artifact.path()))
                .as("while the artifact serves - the store retains 'this path is published' ...")
                .isPresent();
        assertThat(Hooks.rows(store, PublicationEventFixture.SPACE))
                .as("... and nowhere retains 'a publish occurred at T that was not announced'. There is no walk, "
                        + "sweep or pass that could rebuild the missed event, which is why this hook excludes the "
                        + "repair property with a reason instead of supplying a leg that cannot exist.")
                .isEmpty();
    }

    // --- the published index's retraction flag ---------------------------------------------------------------------

    @Test
    void the_retraction_flag_is_raised_by_a_withhold_transition_and_consumed_by_the_rebase() throws IOException {
        IndexRetractionFixture fixture = new IndexRetractionFixture();
        Publication publication = new Publication(store, List.of(), List.of(fixture.create()));
        String hash = publication.storeBlob(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
        publication.link("/kit/app-1.0.jar", hash);

        assertThat(fixture.projection(store))
                .as("a publish is not a withhold transition and must not force an index rebase")
                .isEmpty();

        publication.link("/quarantine/kit/app-1.0.jar", hash);

        assertThat(fixture.projection(store))
                .as("a freshly linked review pointer is the transition this hook rides")
                .containsEntry("retraction", "flagged");

        fixture.repair(store);

        assertThat(new PublishedIndex(store).retraction().peek())
                .as("and the real PublishedIndexTask rebase re-screens every path through the servable-name seam and "
                        + "clears the flag under its own token - the repair leg, executed")
                .isEmpty();
    }

    // --- the provenance reaper's delete leg, and the sweep that is not behind it -----------------------------------

    @Test
    void the_provenance_reaper_reclaims_a_cached_attestation_when_its_pointer_is_unpublished() throws IOException {
        ProvenanceReaperFixture fixture = new ProvenanceReaperFixture();
        Publication publication = new Publication(store, List.of(), List.of(fixture.create()));
        String path = "/kit/app-1.0.jar";
        String hash = publication.storeBlob(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        // What the provenance endpoint signs and caches on first read, keyed by (blob, served path).
        Hooks.upsert(store, ProvenanceReaperFixture.key(hash, path), "{\"envelope\":\"...\"}");
        assertThat(fixture.projection(store)).as("the attestation is cached").hasSize(1);

        publication.unpublish(path);

        assertThat(fixture.projection(store))
                .as("the reaper rebuilds the identical content-addressed key from the onDeleted descriptor's blob "
                        + "hash and served path - the one way this cache's (blob, path) identity can be reached - and "
                        + "the unpublished artifact leaves no attestation behind")
                .isEmpty();
    }

    @Test
    void a_lost_reaper_call_orphans_the_attestation_with_no_pass_to_reclaim_it() throws IOException {
        ProvenanceReaperFixture fixture = new ProvenanceReaperFixture();
        Publication publication = new Publication(store, List.of(), List.of());   // the call is LOST
        String path = "/kit/app-1.0.jar";
        String hash = publication.storeBlob(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        Hooks.upsert(store, ProvenanceReaperFixture.key(hash, path), "{\"envelope\":\"...\"}");

        publication.unpublish(path);

        assertThat(fixture.projection(store))
                .as("the orphan is real and it is permanent: no MaintenanceTaskProvider in compliance/web, no sweep "
                        + "over the prefix, and the orphan diagnostic cannot see it while the module is installed. "
                        + "This is why the fixture excludes the repair property rather than supplying a leg - and the "
                        + "assertion is what would fail the day §9 D-4's retention leg lands, which is the "
                        + "moment the exclusion should go.")
                .hasSize(1);
        assertThatThrownBy(() -> fixture.repair(store))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("no sweep names the prefix");
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Link a serving pointer with the store primitive and answer the descriptor an ingress edge would then hand
     *  {@link Publication#published} - no coordinate yet, exactly as the kit's own publishes carry none. */
    private ArtifactDescriptor publish(String path) throws IOException {
        return publish(path, BODY);
    }

    private ArtifactDescriptor publish(String path, String body) throws IOException {
        Publication publication = new Publication(store, List.of(), List.of());
        String hash = publication.storeBlob(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        return ArtifactDescriptor.at("kit", path).withBlob(hash, body.getBytes(StandardCharsets.UTF_8).length);
    }

    /** The same descriptor with the layout-derived coordinate an edge stamps on before notifying. */
    private static ArtifactDescriptor coordinate(ArtifactDescriptor artifact, String coordinate, String version) {
        return new ArtifactDescriptor(artifact.ecosystem(), coordinate, version, artifact.path(),
                "application/java-archive", false, artifact.hash(), artifact.size());
    }
}
