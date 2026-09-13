/**
 * Focused unit tests for what is <em>particular</em> to the default filesystem artifact-store backend, against a real
 * store rooted at a JUnit {@code @TempDir} (resolved through
 * {@link build.jenesis.repository.store.ArtifactStoreProvider}, which also covers the ServiceLoader discovery of the
 * filesystem provider): deletion that tidies empty parent directories, immediate-child listing that hides an atomic
 * write's in-flight temp file, a last-modified token that advances strictly inside one clock tick, concurrent
 * compare-and-set increments that never lose one another, owner-only file and directory permissions, a racing delete
 * read as absence, bounded paging over a huge directory, and rejection of a key that escapes the store root on the
 * read path. The cross-backend {@link build.jenesis.repository.store.ArtifactStore} contract itself lives in the
 * shared {@code StoreContract} kit and runs against this backend from {@code test/store/contract}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.filesystem
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.store.filesystem.test {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
