/**
 * The ecosystem format contract suite: the JUnit driver for {@code FormatContract}, one fixture per
 * serving blobs-namespace {@code RepositoryFormat}, and the completeness census that keeps the two in step.
 *
 * <p>It is the ecosystem half of what {@code test/format/contract} does for the four published-tree layouts. The
 * contract itself is stated once, in {@code source/format/testkit}, and every format runs all of it through a
 * {@code FormatFixture} against a real {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} - no
 * HTTP server, no registry, no network. That is the point: the fourteen blobs-namespace layouts had
 * fourteen hand-written suites and therefore fourteen ideas of what a traversal-shaped request path, a withheld
 * version or a proxied digest promises.
 *
 * <p>Two additions live beside the kit, both because a blobs-namespace format differs from a published-tree one
 * in a way the kit's seams cannot express:
 * <ul>
 *   <li>{@code BlobLayoutCoordinateSeamTest} - the kit's coordinate-seam property is stated over the free
 *       {@code ArtifactLayout}, but each of these formats serves from the shared {@code blobs/} namespace, so its
 *       coordinate-to-pointer mapping is {@code BlobLayout}. That is the seam an eviction deletes
 *       through, so the property is proven there, over every discovered layout rather than only the fixtured ones.</li>
 *   <li>{@code PackagedArtifactContract} - seven ecosystems ({@code .nupkg}, {@code .gem}, {@code .deb}, {@code .rpm},
 *       the two conda containers, the Composer zip and the CocoaPods pod) parse the artifact at publish and cannot
 *       accept the kit's arbitrary byte body, so they restate its two publish properties over a real package, with the
 *       same sealed-blob proof.</li>
 * </ul>
 *
 * <p>This module deliberately requires all fourteen format implementations and reaches them only through
 * {@code RepositoryFormat.installed} - the way a dispatcher does - so it is simultaneously the runtime-discovery graph
 * the census needs: a format module omitted here disappears from {@code ServiceLoader}, and the census fails because
 * the source {@code provides} scan still declares it.
 *
 * <p>The pins below are hand-seeded rather than produced by the {@code pin} goal, and deliberately match the versions
 * this repository's other test modules already carry - the same reason {@code test/compliance/contract} states: left
 * to resolve on its own, a new test module drifts to whatever JUnit and AssertJ happen to be newest while every
 * sibling runs 6.0.3 / 3.27.7.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.blobs
 * @jenesis.test build.jenesis.repository.format.npm
 * @jenesis.test build.jenesis.repository.format.pypi
 * @jenesis.test build.jenesis.repository.format.go
 * @jenesis.test build.jenesis.repository.format.nuget
 * @jenesis.test build.jenesis.repository.format.gems
 * @jenesis.test build.jenesis.repository.format.debian
 * @jenesis.test build.jenesis.repository.format.rpm
 * @jenesis.test build.jenesis.repository.format.cargo
 * @jenesis.test build.jenesis.repository.format.cocoapods
 * @jenesis.test build.jenesis.repository.format.composer
 * @jenesis.test build.jenesis.repository.format.conan
 * @jenesis.test build.jenesis.repository.format.conda
 * @jenesis.test build.jenesis.repository.format.huggingface
 * @jenesis.test build.jenesis.repository.format.oci.inventory
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.contract.ecosystem.test {
    // The contract itself, and the census ratchet - both shared kits, consumed rather than copied.
    requires build.jenesis.repository.format.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;

    // The blobs-namespace seam the coordinate suite drives.
    requires build.jenesis.repository.blobs;

    // The inventory's canonical spelling, which asks every installed layout.
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;

    // Every declared ecosystem format module, so the runtime discovery leg can see all fourteen. A module missing
    // here is exactly the blind spot the separate static leg exists to catch.
    requires build.jenesis.repository.format.npm;
    requires build.jenesis.repository.format.pypi;
    requires build.jenesis.repository.format.go;
    requires build.jenesis.repository.format.nuget;
    requires build.jenesis.repository.format.gems;
    requires build.jenesis.repository.format.debian;
    requires build.jenesis.repository.format.rpm;
    requires build.jenesis.repository.format.cargo;
    requires build.jenesis.repository.format.cocoapods;
    requires build.jenesis.repository.format.composer;
    requires build.jenesis.repository.format.conan;
    requires build.jenesis.repository.format.conda;
    requires build.jenesis.repository.format.huggingface;
    requires build.jenesis.repository.format.oci.inventory;
    requires build.jenesis.repository.format.winget;
    requires build.jenesis.repository.format.helm;
    requires build.jenesis.repository.format.apk;
    requires build.jenesis.repository.format.terraform;
    requires build.jenesis.repository.format.homebrew;
    requires build.jenesis.repository.format.ivy;
    requires build.jenesis.repository.format.swift;

    // The ar/tar containers a .deb and a .gem really are, written with the same library the formats read them with;
    // the zip and gzip shapes come from java.base.
    requires org.apache.commons.compress;

    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census enumerates what ServiceLoader really sees in this graph and
    // compares it against the source `provides` scan, so this module loads the SPI itself rather than only through the
    // RepositoryFormat.installed static. The same `uses`-in-a-test-module shape the free format contract carries.
    uses build.jenesis.repository.format.RepositoryFormat;

    // The withhold leg's screen. A publish/-namespace format is held by the interceptor chain answering `withheld`
    // rather than by a content-addressed marker, so without a discovered screen a held artifact here serves 200 and
    // a held revision stays enumerated. Every fixture in this module was a blobs/ format until one was not.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.contract.ecosystem.test.ContractHoldInterceptor;

}
