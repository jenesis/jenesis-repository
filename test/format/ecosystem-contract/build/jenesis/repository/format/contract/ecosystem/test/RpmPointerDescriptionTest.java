package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RPM key shapes the fixtured round trip cannot reach.
 *
 * <p>RPM is the second format here whose coordinate and version live in a <strong>filename</strong> rather than in
 * path segments, and as with Debian the only thing that makes decoding it safe is a rule of the ecosystem: an RPM
 * file is {@code <name>-<version>-<release>.<arch>.rpm}, and neither a version nor a release may carry a hyphen.
 * So a right-to-left split is exact where a left-to-right one would not be - and RPM package names carry hyphens
 * constantly ({@code python3-requests}), which is the case that would break the naive reading.
 *
 * <p>The coordinate is two segments, {@code <repo>/<name>}, so these also pin the half that is a path segment: a
 * repository name is spliced into the coordinate and has to be screened like any other addressable part.
 *
 * <p>These assert the guarantee rather than the parse. If the no-hyphen rule ever stopped holding, or a repodata
 * document ever started looking like a package, this is what would say so.
 */
class RpmPointerDescriptionTest {

    private static final BlobLayout RPM = new RpmFormatFixture().layout();

    private static Optional<ArtifactDescriptor> describe(String key) {
        return RPM.describePointer(key);
    }

    @Test
    void a_pool_rpm_names_its_repository_package_and_version() {
        assertThat(describe("rpm/prod/Packages/h/hello-2.10-3.x86_64.rpm"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("prod/hello");
                    assertThat(named.version()).isEqualTo("2.10-3.x86_64");
                });
    }

    @Test
    void a_hyphenated_package_name_survives_the_split() {
        // The case a left-to-right split gets wrong, and the reason the parse reads from the right: the name holds
        // two hyphens of its own, and neither the version nor the release may hold one.
        assertThat(describe("rpm/prod/Packages/p/python3-requests-2.31.0-1.noarch.rpm"))
                .hasValueSatisfying(named -> {
                    assertThat(named.coordinate()).isEqualTo("prod/python3-requests");
                    assertThat(named.version()).isEqualTo("2.31.0-1.noarch");
                });
    }

    @Test
    void the_pool_location_does_not_change_the_answer() {
        // A NEVRA may sit under several pool locations and all of them are the same version's keys, which is what
        // blobKeys relies on - so the depth of the path must not reach the coordinate.
        assertThat(describe("rpm/prod/deep/nested/pool/hello-2.10-3.x86_64.rpm"))
                .hasValueSatisfying(named -> assertThat(named.coordinate()).isEqualTo("prod/hello"));
    }

    @Test
    void a_key_that_is_not_a_package_is_not_claimed() {
        assertThat(describe("rpm/prod/repodata/primary.xml.gz")).isEmpty();
        // The by/ reverse index is the trap, and it is a real one: it stores a URL-ENCODED pool location, so the
        // leaf ends in .rpm and parses as a NEVRA perfectly well. Decoded, it would record a package called
        // "Packages%2Fh%2Fhello" under a version that exists. The whole repodata subtree is refused instead.
        assertThat(describe("rpm/prod/repodata/by/hello/2.10-3.x86_64/Packages%2Fh%2Fhello-2.10-3.x86_64.rpm"))
                .isEmpty();
        assertThat(describe("debian/stable/pool/main/h/hello/hello_2.10-3_amd64.deb")).isEmpty();
    }

    @Test
    void a_filename_that_is_not_a_nevra_is_not_claimed() {
        // describe() answers a coordinate-less descriptor here, which is the right answer for a request and the
        // wrong one for a durable row - so the pointer clause must refuse rather than pass it through.
        assertThat(describe("rpm/prod/Packages/hello.rpm")).isEmpty();
        assertThat(describe("rpm/prod/Packages/hello-2.10.rpm")).isEmpty();
    }

    @Test
    void a_traversal_shaped_repository_or_name_is_not_claimed() {
        assertThat(describe("rpm/../Packages/hello-2.10-3.x86_64.rpm")).isEmpty();
        assertThat(describe("rpm/prod/Packages/..-2.10-3.x86_64.rpm")).isEmpty();
    }
}
