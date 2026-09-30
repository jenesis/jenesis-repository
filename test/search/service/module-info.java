/**
 * The one search in process, over a real filesystem store: a lookup by the start of a name reads a bounded page of
 * what the repository keeps sorted - as typed or in lower case, a held version screened out, resumable by a cursor
 * that reaches every match exactly once, cut short visibly when its budget runs out, and costing the same number of
 * store operations however much else the repository holds - and a repository whose full-text search is on answers
 * from its index, led by the name lookup and screened the same way. The setting's default has a test of its own: what
 * the catalogue declares, which wizards ask it, and what a repository with nothing set answers. A minimal format
 * discovered through {@code ServiceLoader} places the coordinates; the index is a stand-in, since the real one is
 * tested beside it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.search.service
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.search.service.test {
    requires build.jenesis.repository.search;
    requires build.jenesis.repository.search.service;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.search.service.test.SearchTestFormat;
}
