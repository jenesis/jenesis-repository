package build.jenesis.repository.compliance.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.inventory.LicenseDerivation;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The general license-resolution logic in isolation over a real filesystem store, no network and no framework: the
 * gate sidecar is preferred and never re-parsed, a release with no sidecar is backfilled from its stored metadata
 * through the discovered {@link build.jenesis.repository.compliance.QualityInspector}s, a release that declares
 * nothing resolves to the single {@link License#UNKNOWN}, and a carrier larger than the metadata guard is skipped
 * (so it too resolves to unknown, never pulling a large body into the heap) - pinned here, apart from the licence
 * count and the search-index sweep that both consume it.
 */
class LicenseDerivationTest {

    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    private static Release release(String ecosystem, String coordinate, String version) {
        return new Release(ecosystem, coordinate, version, NOW, NOW, false, false);
    }

    /** Record a published pointer plus a stored artifact blob of the given size at the coordinate's request path, the
     *  way a gate ACCEPT leaves a release the backfill can later re-read. */
    private void publish(ArtifactStore store, String ecosystem, String coordinate, String version, int blobSize)
            throws IOException {
        String path = "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(new byte[blobSize]));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
    }

    private void sidecar(ArtifactStore store, String ecosystem, String coordinate, String version, String... names)
            throws IOException {
        List<LicenseInventory.Declared> declared = new ArrayList<>();
        for (String name : names) {
            declared.add(new LicenseInventory.Declared(name, null));
        }
        new LicenseInventory(store).record(ecosystem, coordinate, version, declared);
    }

    @Test
    void a_present_sidecar_is_resolved_without_re_deriving_from_metadata() throws IOException {
        ArtifactStore store = store();
        publish(store, "maven", "org.example:mit", "1.0", 32);
        sidecar(store, "maven", "org.example:mit", "1.0", "MIT License");

        List<License> resolved = new LicenseDerivation(store).resolve(release("maven", "org.example:mit", "1.0"));

        // The maven ecosystem has no inspector on this test path, so a value other than unknown proves the sidecar
        // was read directly rather than re-derived.
        assertThat(resolved).extracting(License::spdxId).containsExactly("MIT");
    }

    @Test
    void a_release_with_no_sidecar_is_backfilled_through_a_discovered_inspector() throws IOException {
        ArtifactStore store = store();
        publish(store, "fake", "example-lib", "2.0", 64);        // published, but no licenses section written
        assertThat(new LicenseInventory(store).read("fake", "example-lib", "2.0")).isEmpty();

        List<License> resolved = new LicenseDerivation(store).resolve(release("fake", "example-lib", "2.0"));

        assertThat(resolved).extracting(License::spdxId).containsExactly("Apache-2.0");
        assertThat(resolved).allMatch(License::identified);
    }

    @Test
    void a_release_that_declares_nothing_resolves_to_the_single_unknown_license() throws IOException {
        ArtifactStore store = store();
        publish(store, "maven", "org.example:bare", "1.0", 32);
        sidecar(store, "maven", "org.example:bare", "1.0");      // inspected, none declared: a present-but-empty sidecar

        List<License> resolved = new LicenseDerivation(store).resolve(release("maven", "org.example:bare", "1.0"));

        assertThat(resolved).containsExactly(License.UNKNOWN);
        assertThat(resolved).noneMatch(License::identified);
    }

    @Test
    void a_carrier_larger_than_the_metadata_guard_is_skipped_and_resolves_to_unknown() throws IOException {
        ArtifactStore store = store();
        // No sidecar, so the backfill runs; the only carrier exceeds MAX_METADATA_BYTES (16 MiB) so read() refuses it
        // and no license is derivable - the streaming guard, proven never to pull a large body into the heap.
        publish(store, "fake", "huge-lib", "3.0", (16 << 20) + 1);

        List<License> resolved = new LicenseDerivation(store).resolve(release("fake", "huge-lib", "3.0"));

        assertThat(resolved).containsExactly(License.UNKNOWN);
    }

    @Test
    void the_backfill_s_sibling_lookup_bounds_both_legs_at_the_store() throws IOException {
        // The third supplier. The backfill states both legs against the store: handing inspectors the size-capped
        // CANDIDATE read as their sibling lookup would answer the ABSENCE sentinel for a companion past the candidate
        // cap - the inspector told nothing was published where something was.
        ArtifactStore store = store();
        byte[] payload = new byte[1024];
        Arrays.fill(payload, (byte) 'x');
        new Publication(store).link("/fake/sib/data.bin", store.writeBlob(new ByteArrayInputStream(payload)));
        QualityInspector.Lookup siblings = new LicenseDerivation(store).siblings();

        QualityInspector.Lookup.Bounded cut = siblings.fetchBounded("/fake/sib/data.bin", 16).orElseThrow();
        assertThat(cut.content()).as("exactly the requested prefix").hasSize(16);
        assertThat(cut.truncated()).as("a longer companion is reported bounded, never handed back short").isTrue();

        QualityInspector.Lookup.Bounded exact = siblings.fetchBounded("/fake/sib/data.bin", 1024).orElseThrow();
        assertThat(exact.content()).isEqualTo(payload);
        assertThat(exact.truncated())
                .as("a companion of exactly the limit is whole - the `>` boundary, not `>=`")
                .isFalse();

        assertThat(siblings.fetch("/fake/sib/data.bin")).as("read whole, a small companion is entire").hasValue(payload);
        assertThat(siblings.fetch("/fake/sib/absent.bin")).as("nothing published there").isEmpty();
        assertThat(siblings.fetchBounded("/fake/sib/absent.bin", 16)).isEmpty();
    }

    @Test
    void a_companion_past_the_whole_document_ceiling_still_answers_the_bounded_leg() throws IOException {
        // The leg divergence itself: past the shared whole-document ceiling the read-whole leg fails loudly, while the
        // bounded leg - which is not subject to that ceiling - still answers, truncated. Under the deleted default
        // this second call threw too, because it was expressed in terms of the first.
        ArtifactStore store = store();
        new Publication(store).link("/fake/sib/huge.bin", store.writeBlob(
                new ByteArrayInputStream(new byte[PublishInterceptor.Content.LARGEST_SIBLING + 1])));
        QualityInspector.Lookup siblings = new LicenseDerivation(store).siblings();

        assertThatThrownBy(() -> siblings.fetch("/fake/sib/huge.bin"))
                .as("read whole, it fails loudly rather than yielding a prefix a caller would read as complete")
                .isInstanceOf(IOException.class)
                .hasMessageContaining(String.valueOf(PublishInterceptor.Content.LARGEST_SIBLING));

        QualityInspector.Lookup.Bounded bounded = siblings.fetchBounded("/fake/sib/huge.bin", 32).orElseThrow();
        assertThat(bounded.content()).hasSize(32);
        assertThat(bounded.truncated()).as("bounded, and visibly so").isTrue();
    }
}
