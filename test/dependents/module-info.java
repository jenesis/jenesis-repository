/**
 * Tests of the reverse-dependency index and its sweep over a real filesystem artifact store: an artifact's
 * CycloneDX SBOM is inverted into a sharded "who depends on X" index, the transitive tree (not just direct edges)
 * is recorded, a removed artifact drops out and its emptied shard is compacted on the next rebuild, a non-jar or
 * SBOM-less blob is skipped rather than derailing the sweep, and the pass + its settings are discovered and gated
 * through ServiceLoader. No network and no framework - the index is exercised end to end through the store SPI.
 * The walk-riding sweep is exercised over the {@code store} reference walk: the shared-walk rebuild commits exactly
 * the buffered recompute's shards, a mid-pass crash resumes from the committed cursor without re-parsing pre-cursor
 * blobs, and a second walk instance takes a dead worker's segment over and finishes the merge. The console's dependents
 * screen is driven through its controller.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.dependents
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependents.test {
    requires build.jenesis.repository.dependents;
    // The console's dependents screen, driven through its controller over the web kit's in-process repositories.
    requires build.jenesis.repository.dependents.web;
    requires build.jenesis.repository.web.testkit;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.dependents.spi;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
