/**
 * Unit coverage for the lifecycle marks - deprecate and yank - over a real filesystem store.
 *
 * <p>These marks decide what a resolver is told about a version, so the interesting behaviour is in the edges: an
 * unrecognised state name, a mark cleared twice, a mark on a version that was never published. All of it runs
 * in-process against a temp directory, which is why it belongs in the fastest lane rather than behind the
 * per-ecosystem legs that observe the same marks through a real client.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.lifecycle
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.lifecycle.test {
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
