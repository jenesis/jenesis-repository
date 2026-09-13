/**
 * The import-connector contract suite: the JUnit driver for the testkit's {@code ImportContract}, one behavioural
 * fixture per {@code ImportSourceProvider} the core ships, and the completeness census that keeps the two in step.
 *
 * <p>The suite exists because the behavioural half of a migration was shared in prose only. {@code ImporterContractTest}
 * had taken the census as far as discovery and coordinate derivation, while {@code test/importer/{nexus,artifactory,
 * maven,index,jenesis}} each hand-wrote their own idea of what resuming, streaming, failing and authenticating mean -
 * and the five drifted: Artifactory's Pro listing ignores a resume cursor entirely where its OSS crawl honours one, and
 * the Maven connector turned a refused credential into "the server exposes no directory listing". Here the contract is
 * stated once in the testkit and every connector runs all of it through an
 * {@link build.jenesis.repository.importer.testkit.ImportFixture} against a scripted incumbent and a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} - no
 * network, no Nexus, no Artifactory.
 *
 * <p>This module deliberately requires all five connector implementations and reaches them only through
 * {@code ServiceLoader} - the way the server does - so it is simultaneously the runtime-discovery graph the census
 * needs: a connector module omitted here disappears from discovery, and the census fails because the source
 * {@code provides} scan still declares it.
 *
 * <p>It also {@code provides} the one enumerable {@code RepositoryFormat} the {@code index} connector's leg needs. That
 * connector borrows a format's enumeration rather than owning one, so without a discovered enumerable format there is
 * no walk to drive at all; the stand-in serves and proxies nothing and is asserted about nowhere.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.importer.testkit
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.importer.contract.test {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.importer.testkit;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer.jenesis;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census enumerates what ServiceLoader really sees in this graph and
    // compares it against the source `provides` scan, so this module loads the SPI itself rather than through a static.
    // The same `uses`-in-a-test-module shape test/store/contract and test/format/contract already carry.
    uses build.jenesis.repository.importer.ImportSourceProvider;

    // The enumerable format the `index` connector borrows its walk from - scenery for that connector's legs, and the
    // only way to exercise a connector whose enumeration belongs to a format rather than to itself.
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.importer.contract.test.ContractIndexedFormat;
}
