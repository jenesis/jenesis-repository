/**
 * Focused unit tests for the shared bounded feed client, driven entirely from injected transports, an injected clock
 * and an injected retry pause, so every claim - the caps, the backoff schedule, the fail modes, the whole-fetch
 * deadline and the snapshot/staleness commit - is exercised without a socket and without a sleep. The snapshot legs
 * run against the real filesystem {@code ArtifactStore} rooted at a JUnit {@code @TempDir}, so persistence is
 * asserted through the store SPI rather than against a mock: the pointer-last commit, the retained prior-good
 * snapshot, the compare-and-set arbitration of two racing refreshers, and the read-path guarantee that rendering a
 * stored snapshot reaches no transport at all.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.feed
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.feed.test {
    requires build.jenesis.repository.feed;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
