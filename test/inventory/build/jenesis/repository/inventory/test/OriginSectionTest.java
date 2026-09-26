package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code origin} section: the provenance-of-source acquisition trail. This suite covers the
 * codec's one-row-per-{@code (source, sha256)} identity (an idempotent same-bytes converge, a new row on a digest
 * change, {@code serves}/{@code lastServed} update on a repeat fallback serve), the publish-path folding of a
 * {@code local-upload} row into the same doc mutate as the {@code published} section, and the eviction dividend: a
 * re-heatable cached fallback blob is reclaimed while its {@code origin} + {@code verdict} sections survive, and a
 * {@code local-upload}-origin blob is never cache-evicted. The gateway-side fallback-fetch writer is covered by
 * {@code OriginTrackingTest} in the gateway suite.
 */
class OriginSectionTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String COORD = "com.example:lib";
    private static final String VERSION = "1.0.0";
    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-05-02T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private MetadataStore metadata;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        metadata = MetadataProvider.installed().orElseThrow().over(store);
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    private Optional<Section> section(String tag) throws IOException {
        return metadata.section(ECO, COORD, VERSION, tag);
    }

    // ---- codec: one row per (source, sha256) -----------------------------------------------------------------------

    @Test
    void a_local_upload_row_is_one_per_sha_idempotent_and_appends_a_new_row_on_a_digest_change() {
        Section first = OriginSection.recordUpload("sha-a", NOW).apply(Optional.empty());
        assertThat(OriginSection.acquisitions(Optional.of(first))).singleElement().satisfies(row -> {
            assertThat(row.localUpload()).isTrue();
            assertThat(row.sha256()).isEqualTo("sha-a");
            assertThat(row.at()).isEqualTo(NOW);
        });

        // Re-record the SAME bytes: idempotent - still one row (the at refreshes, no duplicate).
        Section same = OriginSection.recordUpload("sha-a", LATER).apply(Optional.of(first));
        assertThat(OriginSection.acquisitions(Optional.of(same))).singleElement()
                .satisfies(row -> assertThat(row.at()).isEqualTo(LATER));

        // A digest change appends a NEW row (the shadowing/drift trail).
        Section changed = OriginSection.recordUpload("sha-b", LATER).apply(Optional.of(same));
        assertThat(OriginSection.acquisitions(Optional.of(changed)))
                .extracting(OriginSection.Acquisition::sha256).containsExactly("sha-a", "sha-b");
    }

    @Test
    void a_fallback_row_updates_serves_and_last_served_on_a_repeat_and_appends_a_new_row_on_a_digest_change() {
        Section first = OriginSection.recordFallback("frontdoor", 2,
                "https://central/lib-1.0.jar", "sha-a", false, "harden", NOW).apply(Optional.empty());
        assertThat(OriginSection.acquisitions(Optional.of(first))).singleElement().satisfies(row -> {
            assertThat(row.fallback()).isTrue();
            assertThat(row.repository()).isEqualTo("frontdoor");
            assertThat(row.fallbackIndex()).isEqualTo(2);
            assertThat(row.target()).isEqualTo("https://central/lib-1.0.jar");
            assertThat(row.stored()).isFalse();
            assertThat(row.screening()).isEqualTo("harden");
            assertThat(row.serves()).isEqualTo(1);
            assertThat(row.lastServed()).isEqualTo(NOW);
        });

        // A repeat no-copy serve of the SAME bytes updates lastServed and increments serves - one row still.
        Section repeat = OriginSection.recordFallback("frontdoor", 2,
                "https://central/lib-1.0.jar", "sha-a", false, "harden", LATER).apply(Optional.of(first));
        assertThat(OriginSection.acquisitions(Optional.of(repeat))).singleElement().satisfies(row -> {
            assertThat(row.serves()).as("serves increments on a repeat").isEqualTo(2);
            assertThat(row.lastServed()).as("lastServed refreshes").isEqualTo(LATER);
            assertThat(row.at()).as("the first-acquisition instant is preserved").isEqualTo(NOW);
        });

        // A digest change appends a NEW row (the visible drift history beside the verdict).
        Section drift = OriginSection.recordFallback("frontdoor", 2,
                "https://central/lib-1.0.jar", "sha-b", false, "harden", LATER).apply(Optional.of(repeat));
        assertThat(OriginSection.acquisitions(Optional.of(drift)))
                .extracting(OriginSection.Acquisition::sha256).containsExactly("sha-a", "sha-b");
    }

    @Test
    void the_classification_helpers_key_the_eviction_decision_off_the_recorded_rows() {
        Section fallbackOnly = OriginSection.recordFallback("f", 0, "u", "sha-a", true, "default", NOW)
                .apply(Optional.empty());
        assertThat(OriginSection.reheatableFallbackOnly(Optional.of(fallbackOnly))).isTrue();
        assertThat(OriginSection.hasLocalUpload(Optional.of(fallbackOnly))).isFalse();

        Section withUpload = OriginSection.recordUpload("sha-a", NOW).apply(Optional.of(fallbackOnly));
        assertThat(OriginSection.hasLocalUpload(Optional.of(withUpload))).isTrue();
        assertThat(OriginSection.reheatableFallbackOnly(Optional.of(withUpload)))
                .as("a local-upload row makes the blob system-of-record, not re-heatable").isFalse();

        assertThat(OriginSection.reheatableFallbackOnly(Optional.empty()))
                .as("no origin record is not a recognised cache entry").isFalse();
    }

    // ---- publish path: a hand upload folds a local-upload row into the SAME doc mutate as published ----------------

    @Test
    void a_hand_upload_records_a_local_upload_origin_row_beside_the_published_section() throws IOException {
        inventory().record(ECO, COORD, VERSION, false, NOW, "sha-upload");

        assertThat(PublishedSection.published(section(PublishedSection.TAG)))
                .as("the publish commit still records the published section").isTrue();
        assertThat(OriginSection.acquisitions(section(OriginSection.TAG))).singleElement().satisfies(row -> {
            assertThat(row.localUpload()).isTrue();
            assertThat(row.sha256()).isEqualTo("sha-upload");
        });
        // Both sections live in the ONE version document - one CAS, no extra round-trip.
        assertThat(metadata.read(ECO, COORD, VERSION).orElseThrow().tags())
                .contains(PublishedSection.TAG, OriginSection.TAG);
    }

    @Test
    void a_re_publish_of_the_same_bytes_keeps_one_local_upload_row() throws IOException {
        inventory().record(ECO, COORD, VERSION, false, NOW, "sha-upload");
        inventory().record(ECO, COORD, VERSION, false, LATER, "sha-upload");
        assertThat(OriginSection.acquisitions(section(OriginSection.TAG)))
                .as("one row per (source, sha256) survives a same-bytes re-publish").hasSize(1);
    }

    @Test
    void a_publish_without_a_sha_records_no_origin_row() throws IOException {
        inventory().record(ECO, COORD, VERSION, false, NOW);
        assertThat(section(OriginSection.TAG)).as("no upload sha - no origin row").isEmpty();
        assertThat(PublishedSection.published(section(PublishedSection.TAG))).isTrue();
    }

    // ---- eviction dividend: reclaim a re-heatable fallback blob, retain origin + verdict ---------------------------

    @Test
    void reclaiming_a_cached_fallback_blob_discards_the_bytes_but_retains_origin_and_verdict() throws IOException {
        String path = InventoryTestFormat.path(COORD, VERSION);
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("cached-bytes".getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        inventory().record(ECO, COORD, VERSION, false, NOW);                      // a published member (the caching leg)
        metadata.mutate(ECO, COORD, VERSION, OriginSection.TAG,
                OriginSection.recordFallback("frontdoor", 0, "https://central/lib", hash, true, "harden", NOW));
        metadata.mutate(ECO, COORD, VERSION, "verdict", verdictSection());        // the sibling verdict record

        assertThat(publication.located(path)).as("the cached fallback blob is present before reclaim").isPresent();

        boolean reclaimed = inventory().reclaimFallbackCache(ECO, COORD, VERSION);

        assertThat(reclaimed).as("a re-heatable fallback blob is reclaimed").isTrue();
        assertThat(publication.located(path)).as("the bytes are discarded - the pointer is gone").isEmpty();
        assertThat(section(OriginSection.TAG)).as("the origin section is retained (records kept)").isPresent();
        assertThat(section("verdict")).as("the sibling verdict section is retained").isPresent();
        assertThat(section(PublishedSection.TAG))
                .as("the served-fact published section goes with the discarded bytes").isEmpty();
    }

    @Test
    void a_local_upload_origin_blob_is_never_cache_evicted() throws IOException {
        String path = InventoryTestFormat.path(COORD, VERSION);
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream("uploaded".getBytes(StandardCharsets.UTF_8)));
        publication.link(path, hash);
        inventory().record(ECO, COORD, VERSION, false, NOW, hash);                // a hand upload: local-upload origin

        boolean reclaimed = inventory().reclaimFallbackCache(ECO, COORD, VERSION);

        assertThat(reclaimed).as("a local-upload blob is system-of-record, never cache-evicted").isFalse();
        assertThat(publication.located(path)).as("the system-of-record blob is untouched").isPresent();
        assertThat(section(OriginSection.TAG)).isPresent();
        assertThat(PublishedSection.published(section(PublishedSection.TAG))).isTrue();
    }

    private static SectionMutation verdictSection() {
        return current -> Section.derived("verdict", 1, NOW, Signal.NEUTRAL, null);
    }
}
