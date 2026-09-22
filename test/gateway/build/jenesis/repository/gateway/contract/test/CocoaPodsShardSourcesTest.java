package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.cocoapods.CocoaPodsFormat;
import build.jenesis.repository.format.cocoapods.CocoaPodsListingObserver;
import build.jenesis.repository.gateway.testkit.FormatDrive;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CocoaPods shard is a two-level listing: each line is derived from a pod's own document, by the publish that
 * wrote the document and by the rebuild pass that regenerates the shard from a walk over every pod document. The
 * sixth soak (2026-09-13) caught the second writer landing a snapshot's older line over the publish's fresher one.
 * The guard is the listing primitive's: a line carries the sequence of the pod document it was derived from, and a
 * regeneration merges into the stored shard per line rather than replacing it. This holds the format's half - that
 * the publish states the source, that the regeneration states it too, and that the shard keeps it through both.
 */
class CocoaPodsShardSourcesTest {

    private static final String REPO = "myrepo";

    @TempDir
    Path root;

    private ArtifactStore store;
    private final CocoaPodsFormat format = new CocoaPodsFormat();

    @BeforeEach
    void arrange() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void the_shard_line_carries_the_pod_documents_sequence_and_a_regeneration_keeps_it() throws Exception {
        publish("AcmeKit", "1.0.0");
        publish("AcmeKit", "1.1.0");
        String shard = shard("AcmeKit");
        long pod = StoredListing.header(store, "cocoapods/" + REPO + "/pods/AcmeKit").orElseThrow().seq();

        assertThat(body(shard)).contains("AcmeKit/1.0.0/1.1.0");
        assertThat(StoredListing.sources(store, shard).entries())
                .as("the publish derived the line from the pod document at its current sequence")
                .containsEntry("AcmeKit", pod);
        assertThat(StoredListing.sources(store, shard).removed()).isEmpty();

        assertThat(new CocoaPodsListingObserver().rebuild(shard, store)).as("the rebuild pass claims the shard").isTrue();

        assertThat(body(shard)).as("the regeneration read the same pod document and its line landed")
                .contains("AcmeKit/1.0.0/1.1.0");
        assertThat(StoredListing.sources(store, shard).entries())
                .as("stated at the sequence it was read from, so a later publish's line will win over a rebuild's "
                        + "snapshot and a snapshot read later than the publish will win over the line")
                .containsEntry("AcmeKit", pod);
        assertThat(StoredListing.header(store, shard).orElseThrow().count())
                .as("the header still counts the shard's lines").hasValue(1L);
    }

    private void publish(String name, String version) throws IOException {
        byte[] zip = zip(name + ".podspec.json", "{\"name\":\"" + name + "\",\"version\":\"" + version
                + "\",\"summary\":\"a kit\",\"license\":\"MIT\",\"source\":{\"git\":\"https://example.com/" + name
                + ".git\",\"tag\":\"" + version + "\"}}");
        FormatDrive.Call put = new FormatDrive.Call("PUT", "/cocoapods/" + REPO + "/" + name + "/" + version, zip);
        format.handle(put, store);
        assertThat(put.status).as("the publish of " + name + " " + version).isEqualTo(201);
    }

    private String body(String listing) throws IOException {
        try (StoredListing.Served served = StoredListing.served(store, listing).orElseThrow()) {
            return new String(served.bytes(), StandardCharsets.UTF_8);
        }
    }

    /** The shard listing a pod lives in: three hex characters of the pod name's MD5, as the CDN lays them out. */
    private static String shard(String name) throws Exception {
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8)));
        return "cocoapods/" + REPO + "/all_pods_versions_" + hex.charAt(0) + "_" + hex.charAt(1) + "_" + hex.charAt(2)
                + ".txt";
    }

    private static byte[] zip(String entry, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
