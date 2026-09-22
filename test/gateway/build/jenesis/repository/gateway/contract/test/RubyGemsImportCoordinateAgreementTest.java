package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.gems.RubyGemsImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the RubyGems importer's manifest-vs-path agreement (audit finding N2, RubyGems leg). {@link
 * RubyGemsImporter#importTarget} screens the coordinate parsed from the {@code .gem} FILENAME
 * ({@code <name>-<version>.gem}); {@link RubyGemsImporter#importArtifact} replays the gem through the coordinate-less
 * {@code gem push} endpoint, and {@code RubyGemsFormat} keys the store on the {@code name} read from the embedded
 * gemspec. Because {@code gem push} carries no path coordinate, the importer now reads the gemspec name and REFUSES a
 * gem whose gemspec name disagrees with the screened filename - so a gem can never be screened under one name and then
 * stored/served under another (the screen-label bypass), the way Composer/CocoaPods refuse a manifest that disagrees
 * with the deploy path. Container-free.
 */
class RubyGemsImportCoordinateAgreementTest {

    @TempDir
    Path root;

    /**
     * A gem screened under the filename name {@code innocent} whose gemspec declares a DIFFERENT name
     * ({@code evil-payload}) is REFUSED - stored under neither name.
     */
    @Test
    void a_gem_whose_gemspec_name_disagrees_with_the_screened_filename_is_refused() throws IOException {
        ArtifactStore store = store();
        String path = "rubygems/gems/innocent-1.0.0.gem";

        ArtifactDescriptor screened = new RubyGemsImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("innocent");

        new RubyGemsImporter().importArtifact(path, new ByteArrayInputStream(gem("evil-payload", "9.9.9")), store);

        assertThat(new Blobs(store).list("rubygems"))
                .as("a gemspec name disagreeing with the screened filename is refused - stored under neither name")
                .doesNotContain("evil-payload", "innocent");
    }

    /** The honest baseline: a gemspec name that matches the filename is stored under the screened coordinate. */
    @Test
    void a_matching_gem_is_stored_under_the_coordinate_it_was_screened_against() throws IOException {
        ArtifactStore store = store();
        String path = "rubygems/gems/lodash-4.17.11.gem";

        ArtifactDescriptor screened = new RubyGemsImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("lodash");

        new RubyGemsImporter().importArtifact(path, new ByteArrayInputStream(gem("lodash", "4.17.11")), store);

        assertThat(new Blobs(store).exists("rubygems/lodash/versions/4.17.11"))
                .as("a gemspec that matches the filename serves under the screened coordinate").isTrue();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** A minimal {@code .gem}: a tar carrying a {@code metadata.gz} whose gzipped YAML is the gemspec the format reads. */
    private static byte[] gem(String name, String version) throws IOException {
        String gemspec = "--- !ruby/object:Gem::Specification\n"
                + "name: " + name + "\n"
                + "version: !ruby/object:Gem::Version\n  version: " + version + "\n"
                + "licenses:\n- MIT\n";
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzipped)) {
            out.write(gemspec.getBytes(StandardCharsets.UTF_8));
        }
        byte[] metadata = gzipped.toByteArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("metadata.gz");
            entry.setSize(metadata.length);
            tar.putArchiveEntry(entry);
            tar.write(metadata);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
