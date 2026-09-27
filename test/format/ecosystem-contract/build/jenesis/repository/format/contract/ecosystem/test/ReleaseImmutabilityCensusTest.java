package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every hosted format keeps a released file's bytes, or says why its files may change.
 *
 * <p>A released file is refused a second, different upload in one of two places, and the census asks each format
 * which. A format that knows the version only inside its layout links the file through {@code Blobs.linkRelease}, and
 * {@link ReleaseImmutabilityTest} drives each of those through its own registry's answer. Every other format's file is
 * the {@code publish/} pointer of the request path it was uploaded to, which the ingress edge holds to the pointer it
 * replaces ({@link Publication#guarded}) - so this publishes each such format's release, then publishes other bytes
 * under the guard bound to the path the upload actually addressed, as the edge binds it, and requires the first bytes
 * still to serve. A format whose upload addresses one path and links another would pass the edge unguarded, and fails
 * here. The rest are {@link #MUTABLE}, each with its reason; a new format joins this census by joining the fixtures,
 * so it cannot arrive undecided.
 */
class ReleaseImmutabilityCensusTest {

    /** Formats whose published file may change under its name, and why. */
    private static final Map<String, String> MUTABLE = Map.of(
            "huggingface", "a file is addressed through a revision, and the revision the fixture publishes to is a "
                    + "branch (main): a push moves the branch to a new commit, as the Hub does, and the file at a "
                    + "commit id - the address a pinned download uses - is what never changes");

    @TempDir
    Path root;

    /** Finish any derivation a publish queued - a conda repodata's compressed twin, a Debian index's signed release -
     *  before the {@code @TempDir} under it is deleted. */
    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    static List<EcosystemFormatFixture> fixtures() {
        return EcosystemFormatFixture.all();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void a_released_file_keeps_its_bytes_or_the_format_says_why_it_may_change(EcosystemFormatFixture fixture)
            throws Exception {
        Set<String> refusedByTheFormat = ReleaseImmutabilityTest.formats().stream()
                .map(format -> format.fixture().format()).collect(Collectors.toSet());
        if (refusedByTheFormat.contains(fixture.format())) {
            assertThat(MUTABLE).as("%s refuses at its own pointer, so it is not mutable", fixture.format())
                    .doesNotContainKey(fixture.format());
            return;
        }
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // A format that parses what it is sent publishes a real package, and its fixture builds the same version with
        // other bytes; any other takes the bytes it is given.
        ContractExchange.recordWrites();
        Optional<EcosystemFormatFixture.Packaged> packaged = fixture.publishPackage(store);
        String servedPath;
        String firstHash;
        if (packaged.isPresent()) {
            servedPath = packaged.get().servedPath();
            firstHash = packaged.get().contentHash();
        } else {
            FormatFixture.Published first = fixture.publish(store, "the release as first published"
                    .getBytes(StandardCharsets.UTF_8));
            servedPath = first.servedPath();
            firstHash = first.contentHash();
        }
        List<String> writes = ContractExchange.recordedWrites();
        assertThat(writes).as("%s published through a write", fixture.format()).isNotEmpty();
        String uploaded = writes.getLast();

        Throwable second = catchThrowable(() -> Publication.guarded(uploaded, () -> packaged.isPresent()
                ? fixture.republishPackage(store).orElseThrow(() -> new AssertionError(fixture.format()
                        + " publishes a package but builds no second one with other bytes"))
                : fixture.publish(store, "the same release with other bytes".getBytes(StandardCharsets.UTF_8))));
        String served = served(fixture, store, servedPath);

        if (MUTABLE.containsKey(fixture.format())) {
            assertThat(second).as("%s is declared mutable, so a second upload replaces the first; if it now refuses, "
                    + "drop it from MUTABLE", fixture.format()).isNull();
            return;
        }
        assertThat(served).as("%s still serves its first bytes after an upload of other bytes under the guard at %s "
                + "(the second upload answered %s)", fixture.format(), uploaded, second)
                .isEqualTo(firstHash);
        assertThat(second).as("and the second upload was refused").isNotNull();
    }

    /** The content hash of what {@code path} serves. */
    private static String served(EcosystemFormatFixture fixture, ArtifactStore store, String path) throws Exception {
        ContractExchange get = ContractExchange.of("GET", path);
        fixture.serving().handle(get, store);
        assertThat(get.status()).as("%s serves %s", fixture.format(), path).isEqualTo(200);
        return Packages.sha256(get.responseBytes());
    }
}
