package build.jenesis.repository.format.oci.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.oci.OciFormat;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listing repair rewrites a repository-wide index once per batch, not once per package: rebuilding every image's
 * tag list fires its derivation, which puts that image's one entry into the catalog, so a repair over P images that
 * wrote the catalog per entry would rewrite a P-entry document P times - quadratic in the repository. Measured by the
 * node's own count of listing rewrites, over two hundred images, and checked to have repaired the catalog it rewrote.
 */
class OciCatalogRebuildCostTest {

    private static final int IMAGES = 200;

    @TempDir
    Path root;

    @Test
    void a_repair_over_every_image_rewrites_the_catalog_once_per_batch() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        OciFormat format = new OciFormat();
        for (int i = 0; i < IMAGES; i++) {
            FakeExchange put = new FakeExchange("PUT", "/v2/image-" + i + "/manifests/1.0",
                    "{}".getBytes(StandardCharsets.UTF_8), Map.of(),
                    Map.of("Content-Type", "application/vnd.oci.image.manifest.v1+json"));
            format.handle(put, store);
            assertThat(put.status()).as("the push of image %d", i).isEqualTo(201);
        }
        StoredListing.settle();
        // A repair is what happens to a catalog that drifted: here, one that lost every entry.
        StoredListing.forget(store, "oci/_catalog");

        double before = updates();
        StoredListing.rebuildAll(store, StoredListing.Rebuilder.installed());
        StoredListing.settle();
        double rewrites = updates() - before;

        assertThat(rewrites).as("listing rewrites for a repair over %d images: the catalog once per batch, not once "
                        + "per image - and at least once, or the count measures nothing", IMAGES)
                .isBetween(1.0, IMAGES / 10.0);
        FakeExchange catalog = new FakeExchange("GET", "/v2/_catalog");
        format.handle(catalog, store);
        assertThat(catalog.responseText()).as("the repaired catalog names every image")
                .contains("\"image-0\"").contains("\"image-" + (IMAGES - 1) + "\"");
    }

    /** The node's count of listing documents rewritten on the write path. */
    private static double updates() {
        return new StoredListing.Observability().metrics().stream()
                .filter(metric -> metric.name().equals("jenrepo.listing.updates"))
                .mapToDouble(Metric::value).sum();
    }
}
