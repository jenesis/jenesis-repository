package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gem version is a prerelease as RubyGems decides it - its number holds a letter - and the version pointer and the
 * download path say so alike. A platform gem carries its platform after a {@code -}, which is no part of the number:
 * read as a prerelease, every platform build of a release was aged and expired as one.
 */
class RubyGemsPrereleaseTest {

    private static final BlobLayout GEMS = new RubyGemsFormatFixture().layout();

    @Test
    void the_pointer_and_the_path_agree_on_what_a_prerelease_is() {
        Map<String, Boolean> versions = Map.of(
                "1.16.0", false,
                "1.16.0-x86_64-linux", false,
                "7.1.0.rc1", true,
                "2.0.0.pre-arm64-darwin", true);
        versions.forEach((version, prerelease) -> {
            assertThat(GEMS.describePointer("rubygems/nokogiri/versions/" + version).orElseThrow().prerelease())
                    .as("the pointer of %s", version).isEqualTo(prerelease);
            ArtifactDescriptor downloaded = GEMS.describe("/rubygems/gems/nokogiri-" + version + ".gem").orElseThrow();
            assertThat(downloaded.version()).isEqualTo(version);
            assertThat(downloaded.prerelease()).as("the download of %s", version).isEqualTo(prerelease);
        });
    }
}
