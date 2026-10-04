package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.format.pypi.PyPiFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.AssetCatalog;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The asset catalogue lists what a format keeping its own key space serves as well as what the {@code publish/}
 * pointers serve: a PyPI distribution stored only under {@code pypi/<project>/files/} is listed at the path a client
 * downloads it from, with its hash and size, once, whatever the page size - so an import from this deployment carries
 * it.
 */
class AssetCatalogBlobLayoutTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private AssetCatalog catalog;

    private final byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
    private final byte[] wheel = "a wheel".getBytes(StandardCharsets.UTF_8);
    private final byte[] sdist = "an sdist".getBytes(StandardCharsets.UTF_8);
    private final byte[] later = "a later sdist".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void seed() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("default");
        Publication publication = new Publication(store);
        publication.link("/maven/com/acme/app/1.0/app-1.0.pom",
                publication.storeBlob(new ByteArrayInputStream(pom)));

        Blobs blobs = new Blobs(store);
        blobs.write("pypi/acme/files/acme-1.0-py3-none-any.whl", wheel);
        blobs.write("pypi/acme/files/acme-1.0.tar.gz", sdist);
        blobs.write("pypi/acme/files/acme-2.0.tar.gz", later);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        inventory.record("PyPI", "acme", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
        inventory.record("PyPI", "acme", "2.0", Instant.parse("2026-01-02T00:00:00Z"));

        List<RepositoryFormat> installed = List.of(new MavenFormat(), new PyPiFormat());
        catalog = new AssetCatalog(store,
                path -> installed.stream().filter(format -> format.handles(path)).findFirst(), installed);
    }

    @Test
    void a_distribution_stored_in_the_formats_own_key_space_is_listed_where_it_is_served() throws IOException {
        Map<String, AssetCatalog.Asset> listed = new LinkedHashMap<>();
        for (AssetCatalog.Asset asset : all(100)) {
            listed.put(asset.path(), asset);
        }

        assertThat(listed).containsOnlyKeys("/maven/com/acme/app/1.0/app-1.0.pom",
                "/pypi/simple/acme/acme-1.0-py3-none-any.whl", "/pypi/simple/acme/acme-1.0.tar.gz",
                "/pypi/simple/acme/acme-2.0.tar.gz");
        AssetCatalog.Asset asset = listed.get("/pypi/simple/acme/acme-1.0-py3-none-any.whl");
        assertThat(asset.sha256()).isEqualTo(sha256(wheel));
        assertThat(asset.size()).isEqualTo(wheel.length);
        assertThat(asset.format()).isEqualTo("pypi");
        assertThat(asset).extracting(AssetCatalog.Asset::ecosystem, AssetCatalog.Asset::coordinate,
                AssetCatalog.Asset::version).containsExactly("PyPI", "acme", "1.0");
    }

    @Test
    void a_page_of_one_lists_every_asset_once() throws IOException {
        List<String> whole = all(100).stream().map(AssetCatalog.Asset::path).sorted().toList();
        List<String> paged = all(1).stream().map(AssetCatalog.Asset::path).sorted().toList();
        assertThat(paged).hasSize(4).isEqualTo(whole);
    }

    @Test
    void a_cursor_this_catalogue_did_not_hand_out_is_refused() {
        assertThatThrownBy(() -> catalog.page("maven/com", 10)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<AssetCatalog.Asset> all(int limit) throws IOException {
        List<AssetCatalog.Asset> assets = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AssetCatalog.Page page = catalog.page(cursor, limit);
            assets.addAll(page.assets());
            cursor = page.cursor();
            assertThat(++pages).as("the walk ends").isLessThan(20);
        } while (cursor != null);
        return assets;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
