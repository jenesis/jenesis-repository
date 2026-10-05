package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.InventoryBackfillConsumer;
import build.jenesis.repository.inventory.InventoryReconcileConsumer;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Stamp;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A copy a pull-through cached from an upstream is a holding of its own: recorded once, apart from the releases, so
 * the enumerations of the published set - retention's among them - never see it, while the enumerations of
 * everything held do. The recording is driven here the way a fill drives it, through the {@code onCached} notice the
 * pull-through fires, and the repairs are driven over the shared walk: a fill whose notice was lost converges to a
 * copy from its origin trail, and a fill recorded as a release is turned back into the copy it is.
 */
class CachedHoldingsTest {

    private static final String ECO = InventoryTestFormat.ECOSYSTEM;
    private static final String BLOB_ECO = InventoryTestBlobFormat.ECOSYSTEM;
    private static final String UPSTREAM = "https://repo1.example/maven2/";
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofDays(400));
    private static final ArtifactWalk WALK = WalkProvider.resolve(key -> null).orElseThrow();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("proxy");
    }

    private StoreRepositoryInventory inventory() {
        return new StoreRepositoryInventory(store);
    }

    @Test
    void a_fill_notice_records_a_cached_copy_that_the_published_set_never_sees() throws IOException {
        String path = link("snakeyaml", "1.33");
        fill(path);

        assertThat(inventory().cachedAt(ECO, "snakeyaml", "1.33")).as("the fill is recorded as a copy")
                .hasValueSatisfying(facts -> assertThat(facts.upstream()).isEqualTo(UPSTREAM));
        assertThat(inventory().publishedAt(ECO, "snakeyaml", "1.33")).as("and not as a release").isEmpty();
        assertThat(inventory().releases()).as("retention's enumeration keeps to releases").isEmpty();
        assertThat(inventory().coordinates()).as("so does every reader of the published set").isEmpty();
        assertThat(inventory().recent(null, 10).releases()).isEmpty();

        List<StoreRepositoryInventory.Coordinate> held = new ArrayList<>();
        inventory().holdings(held::add);
        assertThat(held).as("what the scans enumerate includes the copy")
                .containsExactly(new StoreRepositoryInventory.Coordinate(ECO, "snakeyaml", "1.33"));
        assertThat(inventory().cached(null, 10).holdings()).as("and the newest-first index a screen reads")
                .singleElement().satisfies(holding -> {
                    assertThat(holding.cached()).isTrue();
                    assertThat(holding.upstream()).isEqualTo(UPSTREAM);
                    assertThat(holding.version()).isEqualTo("1.33");
                });
        assertThat(inventory().holdings(ECO, "snakeyaml", null, 10).holdings())
                .as("and a coordinate's page").singleElement()
                .satisfies(holding -> assertThat(holding.cached()).isTrue());
    }

    @Test
    void a_re_fill_of_a_held_version_writes_nothing_and_a_release_is_never_recorded_as_a_copy() throws IOException {
        String path = link("snakeyaml", "1.33");
        fill(path);
        String document = new String(store.readVersioned(metaKey(ECO, "snakeyaml", "1.33")).orElseThrow().content(),
                StandardCharsets.UTF_8);

        assertThat(inventory().cache(ECO, "snakeyaml", "1.33", "https://elsewhere.example/", LATER))
                .as("a second fill of the same version is the same holding").isFalse();
        assertThat(new String(store.readVersioned(metaKey(ECO, "snakeyaml", "1.33")).orElseThrow().content(),
                StandardCharsets.UTF_8)).as("so its document is not rewritten").isEqualTo(document);

        inventory().record(ECO, "hosted", "1.0", NOW);
        assertThat(inventory().cache(ECO, "hosted", "1.0", UPSTREAM, LATER))
                .as("a version published here is a release, whatever an upstream also serves").isFalse();
        assertThat(inventory().cachedAt(ECO, "hosted", "1.0")).isEmpty();
    }

    @Test
    void a_fill_reported_by_path_is_recorded_only_where_its_format_now_stores_it() throws IOException {
        // A blobs-namespace format keeps its own key space, so the fill is reported by path and the format that
        // owns the path is asked whether a serving key stands there.
        store.writeVersioned(InventoryTestBlobFormat.blobKey("left-pad", "1.0.0"),
                "0".repeat(64).getBytes(StandardCharsets.UTF_8), null);
        new Publication(store).cached(ArtifactDescriptor.at("testblob", "/testblob/left-pad/1.0.0.bin"),
                URI.create("https://registry.example/"));
        new Publication(store).cached(ArtifactDescriptor.at("testblob", "/testblob/refused/1.0.0.bin"),
                URI.create("https://registry.example/"));

        assertThat(inventory().cachedAt(BLOB_ECO, "left-pad", "1.0.0")).as("what the format stored is held")
                .isPresent();
        assertThat(inventory().cachedAt(BLOB_ECO, "refused", "1.0.0"))
                .as("a fill that stored nothing holds nothing").isEmpty();
    }

    @Test
    void retention_plans_evict_nothing_the_repository_did_not_publish() throws IOException {
        for (String version : List.of("1.0", "1.1", "1.2")) {
            fill(link("cached-lib", version));
        }
        inventory().record(ECO, "published-lib", "1.0", NOW);
        inventory().record(ECO, "published-lib", "2.0", NOW.plus(Duration.ofHours(1)));

        CleanupPlan plan = new RetentionPolicy(0).maxAge(Duration.ofDays(1)).plan(inventory().releases(), LATER);

        // Every version is past the age cap and only a coordinate's newest is kept, so were the copies releases their
        // two older versions would go too.
        assertThat(plan.evictions()).extracting(eviction -> eviction.release().coordinate() + ":"
                        + eviction.release().version())
                .as("only the release's older version goes; the copies are not releases retention can age")
                .containsExactly("published-lib:1.0");
    }

    @Test
    void an_incremental_scan_pass_visits_the_copies_cached_since_the_last_full_one() throws IOException {
        Stamp lastFull = new Stamp(store, "scan-full");
        lastFull.mark(NOW);
        inventory().cache(ECO, "before", "1.0", UPSTREAM, NOW.minus(Duration.ofDays(1)));
        inventory().cache(ECO, "after", "1.0", UPSTREAM, NOW.plus(Duration.ofHours(1)));
        inventory().record(ECO, "released", "1.0", NOW.plus(Duration.ofHours(2)));

        IncrementalPasses cadence = IncrementalPasses.over(store, "scan", "scan-passes", lastFull, key -> null);
        assertThat(cadence.full()).isFalse();
        List<String> visited = new ArrayList<>();
        cadence.holdings(inventory(), held -> visited.add(held.coordinate()));

        assertThat(visited).as("the release and the copy since the stamp, not the copy before it")
                .containsExactlyInAnyOrder("released", "after");
    }

    @Test
    void every_holding_is_walked_as_the_kind_it_is_on_a_full_and_an_incremental_pass() throws IOException {
        Stamp lastFull = new Stamp(store, "sweep-full");
        lastFull.mark(NOW);
        inventory().cache(ECO, "before", "1.0", UPSTREAM, NOW.minus(Duration.ofDays(1)));
        inventory().cache(ECO, "after", "1.0", UPSTREAM, NOW.plus(Duration.ofHours(1)));
        inventory().record(ECO, "released", "1.0", NOW.plus(Duration.ofHours(2)));

        Map<String, Boolean> walked = new TreeMap<>();
        inventory().eachHolding(held -> walked.put(held.coordinate(), held.cached()));
        assertThat(walked).as("the whole-store walk yields every version with its kind")
                .containsExactlyInAnyOrderEntriesOf(Map.of("before", true, "after", true, "released", false));

        IncrementalPasses cadence = IncrementalPasses.over(store, "sweep", "sweep-passes", lastFull, key -> null);
        assertThat(cadence.full()).isFalse();
        Map<String, Boolean> recent = new TreeMap<>();
        cadence.eachHolding(inventory(), held -> recent.put(held.coordinate(), held.cached()));
        assertThat(recent).as("an incremental pass yields what was published or cached since, with its kind")
                .containsExactlyInAnyOrderEntriesOf(Map.of("after", true, "released", false));
    }

    @Test
    void the_forward_repair_records_an_unrecorded_fill_as_a_copy_and_an_unrecorded_publish_as_a_release()
            throws IOException {
        link("cached-before", "1.0");
        origin(ECO, "cached-before", "1.0");
        link("lost-release", "1.0");

        inventory().reconcile(WALK, NOW);

        assertThat(inventory().cachedAt(ECO, "cached-before", "1.0")).as("its origin trail says it was fetched")
                .hasValueSatisfying(facts -> assertThat(facts.upstream()).startsWith("https://repo1.example/"));
        assertThat(inventory().publishedAt(ECO, "cached-before", "1.0")).isEmpty();
        assertThat(inventory().publishedAt(ECO, "lost-release", "1.0"))
                .as("a pointer nothing says was fetched is a release whose record was lost").hasValue(NOW);
        assertThat(inventory().cached(null, 10).holdings()).extracting(StoreRepositoryInventory.Holding::coordinate)
                .containsExactly("cached-before");
    }

    @Test
    void the_forward_repair_never_turns_a_recorded_copy_into_a_release() throws IOException {
        fill(link("snakeyaml", "1.33"));

        inventory().reconcile(WALK, LATER);
        inventory().reconcile(WALK, LATER);

        assertThat(inventory().publishedAt(ECO, "snakeyaml", "1.33")).isEmpty();
        assertThat(inventory().cachedAt(ECO, "snakeyaml", "1.33")).isPresent();
    }

    @Test
    void a_fill_a_repair_recorded_as_a_release_becomes_the_copy_it_is_unless_pinned() throws IOException {
        link("promoted", "1.0");
        origin(ECO, "promoted", "1.0");
        inventory().record(ECO, "promoted", "1.0", NOW);
        link("pinned", "1.0");
        origin(ECO, "pinned", "1.0");
        inventory().record(ECO, "pinned", "1.0", NOW);
        inventory().pin(ECO, "pinned", "1.0");

        inventory().reconcile(WALK, LATER);

        assertThat(inventory().publishedAt(ECO, "promoted", "1.0")).as("no longer a release retention ages")
                .isEmpty();
        assertThat(inventory().cachedAt(ECO, "promoted", "1.0")).as("but a copy, dated when it was recorded")
                .hasValueSatisfying(facts -> assertThat(facts.at()).isEqualTo(NOW));
        assertThat(inventory().recent(null, 10).releases()).extracting(release -> release.coordinate())
                .containsExactly("pinned");
        assertThat(inventory().publishedAt(ECO, "pinned", "1.0")).as("a pinned release is an operator's decision")
                .hasValue(NOW);
    }

    @Test
    void a_copy_whose_pointers_are_gone_stops_being_held() throws IOException {
        String path = link("reclaimed", "1.0");
        fill(path);
        store.delete("publish" + path);

        inventory().reconcile(WALK, LATER);

        assertThat(inventory().cachedAt(ECO, "reclaimed", "1.0")).isEmpty();
        assertThat(inventory().cached(null, 10).holdings()).isEmpty();
        List<StoreRepositoryInventory.Coordinate> held = new ArrayList<>();
        inventory().holdings(held::add);
        assertThat(held).isEmpty();
    }

    @Test
    void the_back_fill_records_a_blobs_namespace_fill_as_a_copy() throws IOException {
        store.writeVersioned(InventoryTestBlobFormat.blobKey("left-pad", "1.0.0"),
                "0".repeat(64).getBytes(StandardCharsets.UTF_8), null);
        origin(BLOB_ECO, "left-pad", "1.0.0");
        store.writeVersioned(InventoryTestBlobFormat.blobKey("hosted", "2.0.0"),
                "1".repeat(64).getBytes(StandardCharsets.UTF_8), null);

        RebuildPass.run(WALK, store, new Publication(store), roots(),
                List.of(new InventoryBackfillConsumer(), new InventoryReconcileConsumer()));

        assertThat(inventory().cachedAt(BLOB_ECO, "left-pad", "1.0.0")).isPresent();
        assertThat(inventory().publishedAt(BLOB_ECO, "left-pad", "1.0.0")).isEmpty();
        assertThat(inventory().publishedAt(BLOB_ECO, "hosted", "2.0.0")).isPresent();
        assertThat(inventory().cachedAt(BLOB_ECO, "hosted", "2.0.0")).isEmpty();
    }

    /** Store and link a version's artifact the way a fill leaves it, and answer the path it serves at. */
    private String link(String coordinate, String version) throws IOException {
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(
                (coordinate + "@" + version).getBytes(StandardCharsets.UTF_8)));
        String path = InventoryTestFormat.path(coordinate, version);
        publication.link(path, hash);
        return path;
    }

    /** The notice a pull-through fires once it has stored and served a fill linked under {@code publish/}. */
    private void fill(String path) throws IOException {
        Publication publication = new Publication(store);
        String key = publication.located(path).orElseThrow();
        ArtifactDescriptor described = inventory().describe(path).orElseThrow()
                .withBlob(key.substring("blobs/".length()), store.size(key));
        publication.cached(described, URI.create(UPSTREAM));
    }

    /** The origin trail a router fallback writes beside the bytes it fetched. */
    private void origin(String ecosystem, String coordinate, String version) throws IOException {
        MetadataProvider.installed().over(store).mutate(ecosystem, coordinate, version, OriginSection.TAG,
                OriginSection.recordFallback("proxy", 0, UPSTREAM + coordinate + "/" + version, "a".repeat(64), true,
                        "default", NOW));
    }

    private static String metaKey(String ecosystem, String coordinate, String version) {
        return StoreRepositoryInventory.publishedRoot() + "/" + ecosystem + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + version;
    }

    private static RebuildPass.Roots roots() {
        return new RebuildPass.Roots(StoreRepositoryInventory.pointerRoots(),
                List.of(StoreRepositoryInventory.publishedRoot()), List.of("blobs"),
                StoreRepositoryInventory.derivedRoots());
    }
}
