package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.nuget.NuGetImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins whether the NuGet importer's two coordinate-deriving surfaces agree on a given {@code .nupkg} source path
 * (audit finding NG1). {@link NuGetImporter#importTarget} screens by the coordinate parsed from the flat-container
 * {@code <id>/<version>/<file>.nupkg} path shape ({@code NuGetFormat.describe}, which needs at least two slashes after
 * {@code /nuget/v3-flatcontainer/}); {@link NuGetImporter#importArtifact} lays out ANY path ending {@code .nupkg},
 * replaying it through {@code NuGetFormat.handle} whose push reads the id/version from the embedded {@code .nuspec}
 * (not the path) and keys the flat container on it. The two therefore disagree on a path that carries no
 * {@code <id>/<version>/} directory shape.
 *
 * <p>The disagreement is real, but not the release-degradation the audit hypothesised. In
 * {@code RepositoryImport.screenAndLayout} an empty {@code importTarget} makes the walk lay the asset out
 * <em>unscreened</em> (it is never screened, so it is never quarantined, so {@code HoldLifecycle.replayImport} is never
 * reached for it) - so "no importer matches on release, degrading to a raw linked blob missing from the flat-container
 * index" cannot occur. What is real is a compliance-screen <em>bypass</em>: a flat single-file {@code .nupkg} is
 * imported without the EPIC 26 inline screen yet is still correctly laid out and indexed in the flat container (the
 * embedded {@code .nuspec} keys it), so it is NOT missing from the index. A deep-prefixed source path exposes a second,
 * milder gap: a deep-prefixed path once screened under a wrong coordinate taken from the FIRST two path segments;
 * {@code importTarget} now derives the coordinate from the TRAILING {@code <id>/<version>/<file>} segments (the
 * Composer/CocoaPods trailing-segment fix, extended to NuGet), so the deep path screens under its true coordinate.
 * Container-free.
 */
class NuGetImportCoordinateAgreementTest {

    @TempDir
    Path root;

    /**
     * The standard incumbent shape the two surfaces DO agree on: {@code importTarget} resolves the flat-container
     * coordinate and {@code importArtifact} lays the package out under the identical store key, so a screened, accepted
     * import serves under exactly the coordinate it was screened against.
     */
    @Test
    void the_two_surfaces_agree_on_the_standard_incumbent_shape() throws IOException {
        ArtifactStore store = store();
        String path = "Newtonsoft.Json/13.0.1/newtonsoft.json.13.0.1.nupkg";

        ArtifactDescriptor target = new NuGetImporter().importTarget(path).orElseThrow();
        assertThat(target.ecosystem()).isEqualTo("NuGet");
        assertThat(target.coordinate()).isEqualTo("newtonsoft.json");
        assertThat(target.version()).isEqualTo("13.0.1");

        new NuGetImporter().importArtifact(path, new ByteArrayInputStream(nupkg("Newtonsoft.Json", "13.0.1")), store);

        // The store key importArtifact keyed on is the very coordinate importTarget screened against - they agree.
        assertThat(new Blobs(store).exists("nuget/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg"))
                .as("importArtifact lays the package out under the coordinate importTarget screened it against")
                .isTrue();
    }

    /**
     * The core NG1 disagreement: a flat single-file {@code .nupkg} (no {@code <id>/<version>/} directory) is DECLINED by
     * {@code importTarget} (empty) yet ACCEPTED and correctly indexed by {@code importArtifact}. The two disagree on
     * whether this path is importable.
     */
    @Test
    void a_flat_single_file_nupkg_is_declined_by_importTarget_but_laid_out_by_importArtifact() throws IOException {
        ArtifactStore store = store();
        String flat = "Newtonsoft.Json.13.0.1.nupkg";   // no <id>/<version>/ directory shape

        // importTarget declines it (fewer than two slashes after the flat-container prefix), so the import walk lays it
        // out UNSCREENED - a compliance-screen bypass, NOT a route to HoldLifecycle.replayImport (an unscreened asset is
        // never quarantined, so it never reaches the release replay the audit feared).
        assertThat(new NuGetImporter().importTarget(flat))
                .as("importTarget returns empty for a flat single-file .nupkg path")
                .isEmpty();

        // importArtifact accepts the same path (any .nupkg), reads the true id/version from the embedded .nuspec and
        // keys the flat container on it - so the package is CORRECTLY laid out and indexed, refuting the audit's
        // "degrades to a raw linked blob, missing from the flat-container index".
        new NuGetImporter().importArtifact(flat, new ByteArrayInputStream(nupkg("Newtonsoft.Json", "13.0.1")), store);
        Blobs blobs = new Blobs(store);
        assertThat(blobs.exists("nuget/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg"))
                .as("the flat single-file .nupkg IS laid out under its true .nuspec coordinate")
                .isTrue();
        assertThat(blobs.list("nuget"))
                .as("the package is present in the flat-container index (keyed by the .nuspec id), not missing")
                .contains("newtonsoft.json");
        assertThat(blobs.list("nuget/newtonsoft.json"))
                .as("its version enumerates in the flat container")
                .contains("13.0.1");
    }

    /**
     * The second gap, now CLOSED: a deep-prefixed source path (a Nexus channel/store prefix, or a re-imported export
     * nested under its own flat container) once made {@code importTarget} screen under a WRONG coordinate taken from the
     * first two path segments, while {@code importArtifact} laid the package out under the true {@code .nuspec}
     * coordinate - a screen MISLABEL. The trailing-segment {@code importTarget} fix (the Composer/CocoaPods bug class,
     * now applied to NuGet) derives the coordinate from the last {@code <id>/<version>/<file>} segments, so the deep
     * path screens under the SAME coordinate the {@code .nuspec} lays it out at: the screen label and the served
     * coordinate now agree.
     */
    @Test
    void a_deep_prefixed_path_now_screens_under_the_same_coordinate_it_is_stored() throws IOException {
        ArtifactStore store = store();
        String deep = "channel/store/Newtonsoft.Json/13.0.1/newtonsoft.json.13.0.1.nupkg";

        // importTarget derives id/version from the TRAILING <id>/<version>/<file> segments, ignoring the deep prefix.
        ArtifactDescriptor target = new NuGetImporter().importTarget(deep).orElseThrow();
        assertThat(target.coordinate())
                .as("the deep-prefixed path screens under its TRUE coordinate, not the leading prefix segment")
                .isEqualTo("newtonsoft.json");
        assertThat(target.version()).isEqualTo("13.0.1");

        // importArtifact keys on the .nuspec; the package is stored/served under the very coordinate it was screened as.
        new NuGetImporter().importArtifact(deep, new ByteArrayInputStream(nupkg("Newtonsoft.Json", "13.0.1")), store);
        Blobs blobs = new Blobs(store);
        assertThat(blobs.exists("nuget/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg"))
                .as("importArtifact stores under the true .nuspec coordinate - the same one importTarget screened")
                .isTrue();
        assertThat(blobs.list("nuget"))
                .as("nothing is stored under the former mis-parsed screen coordinate 'channel'")
                .doesNotContain("channel");
        assertThat(target.coordinate())
                .as("the screen label and the served coordinate now agree - the mislabel is closed")
                .isEqualTo("newtonsoft.json");
    }

    /**
     * The manifest-vs-path agreement (audit finding N2, NuGet leg): a {@code .nupkg} whose embedded {@code .nuspec}
     * declares a DIFFERENT id than the id the edge screened from the path is REFUSED - never stored under an id the
     * gate never saw. Because {@code nuget push} is coordinate-less (the id lives only in the {@code .nuspec}), the
     * check lives in {@code importArtifact}, mirroring the way Composer/CocoaPods refuse a manifest that disagrees with
     * the deploy path. Only the id is compared (NuGet normalises the version in the flat container).
     */
    @Test
    void a_nupkg_whose_nuspec_id_disagrees_with_the_screened_path_is_refused() throws IOException {
        ArtifactStore store = store();
        String path = "innocent/1.0.0/innocent.1.0.0.nupkg";

        // The edge screens this asset under the path id "innocent".
        ArtifactDescriptor screened = new NuGetImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("innocent");

        // The .nupkg's own .nuspec declares a DIFFERENT id; the importer must refuse the mismatch outright.
        new NuGetImporter().importArtifact(path, new ByteArrayInputStream(nupkg("Evil.Payload", "9.9.9")), store);

        assertThat(new Blobs(store).list("nuget"))
                .as("a .nuspec id disagreeing with the screened path is refused - stored under neither id")
                .doesNotContain("evil.payload", "innocent");
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** A minimal {@code .nupkg}: a zip whose entry is the {@code .nuspec} the push reads the id/version from. */
    private static byte[] nupkg(String id, String version) throws IOException {
        String nuspec = "<?xml version=\"1.0\"?>\n<package><metadata>"
                + "<id>" + id + "</id><version>" + version + "</version></metadata></package>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(id + ".nuspec"));
            zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
