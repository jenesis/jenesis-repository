/**
 * Tests of the full-text search index and its pass over a real filesystem artifact store, in process: a pass builds
 * an index from seeded publishes and a query finds them by coordinate segment, by prefix and by what the manifest said
 * about the package; the generation round-trips through the store; a second pass's compare-and-set cutover races
 * safely; the reader picks up a new generation and holds an unchanged one; an unbuilt or format-mismatched index
 * reports no usable index; one tenant's query never sees another's; a repository with full-text search off costs the
 * pass nothing and an idle pass writes nothing; and what one publish and one rebuild cost the store is held by an
 * operation count. The walk-riding rebuild is exercised over the {@code store} reference walk: it serves exactly what
 * the streaming rebuild serves, a crash mid-pass commits nothing and the next pass restarts the enumeration, a second
 * walk instance takes a dead worker's pass over without ever committing the partial view, a live foreign claim defers
 * the build, and a split pass is caught by the single-holder guard.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.search.lucene
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.search.lucene.test {
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.search.lucene;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format;
    // The raw format, because a path-addressed artifact only exists where a format serves one: `pathAddressed`
    // asks which installed format handles the path and whether it owns a coordinate space at all, so a fixture
    // that links a /raw/ pointer into a composition carrying no raw format is describing a path nothing serves.
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.search.lucene.test.FakeLicensedInspector;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.search.lucene.test.FakeLicensedFormat;
}
