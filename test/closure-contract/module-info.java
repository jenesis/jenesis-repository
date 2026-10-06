/**
 * The closure-source contract suite: the JUnit driver for the kit's {@code ClosureContract}, one fixture per
 * {@code ClosureSource} the core ships - the carried bill, the npm shrinkwrap, the Cargo lock, Maven Resolver and the
 * walk over declarations - and the census that keeps the two in step, all over a real filesystem store.
 *
 * <p>The module requires every source's module and reaches them only through {@code ServiceLoader}, the way the pass
 * does, so it is also the runtime-discovery graph the census compares with the declared {@code provides} clauses: a
 * source module left out here disappears from discovery, and the census fails. It provides one service, the
 * {@code NoEgress} name-resolution tripwire, which refuses and records every non-local lookup so the contract's
 * "nothing is fetched" check measures rather than assumes.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.closure.testkit
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.closure.contract.test {
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.closure.lock;
    requires build.jenesis.repository.closure.maven;
    requires build.jenesis.repository.closure.testkit;
    requires build.jenesis.repository.compliance.maven;
    requires build.jenesis.repository.compliance.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.dependents.requirements;
    requires build.jenesis.repository.format.cargo;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.apache.commons.compress;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census compares what ServiceLoader sees in this graph with the
    // declared provides clauses, so this module loads the SPI itself.
    uses build.jenesis.repository.closure.spi.ClosureSource;

    provides java.net.spi.InetAddressResolverProvider
            with build.jenesis.repository.closure.contract.test.NoEgress;
}
