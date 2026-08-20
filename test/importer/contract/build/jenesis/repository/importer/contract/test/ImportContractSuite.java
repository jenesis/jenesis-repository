package build.jenesis.repository.importer.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.importer.testkit.ImportContract;
import build.jenesis.repository.importer.testkit.ImportFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

/**
 * The JUnit driver for one connector's leg of the shared {@code ImportSource} / {@code ImportSourceProvider} contract.
 * Everything connector-specific lives in the {@link ImportFixture} a subclass supplies; the checks themselves come from
 * the testkit, so a new incumbent connector is covered by a fixture and a four-line subclass rather than by another
 * hand-written suite.
 *
 * <p>Each contract property becomes one dynamic test named for its connector and its expectation, so a divergence
 * reports as "artifactory: an interrupted walk resumes from its own cursor ..." rather than as one opaque failure
 * covering five properties. Every check gets its own freshly created, empty store - the streaming leg asserts what
 * landed and what did not, and a store carrying another check's blob would weaken that.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ImportContractSuite {

    @TempDir
    Path root;

    /** The connector under test. */
    abstract ImportFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_import_source_contract() {
        ImportFixture fixture = fixture();
        return ImportContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.source() + ": " + check.name(),
                () -> check.body().run(fixture, store(fixture.source() + "-" + check.property()))));
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
