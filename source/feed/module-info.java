/**
 * The shared bounded feed client every externally-sourced HTTP JSON feed rides: it owns timeouts and the whole-fetch
 * deadline, header injection, the non-200 branch, bounded cursor pagination, bounded bodies, retry backoff, the
 * fail-closed / fail-soft policy, the self-skip of an unconfigured feed, and, for a mirrored catalogue, the snapshot
 * and its staleness stamp committed together through the {@code ArtifactStore}. The vendor-specific half stays with the
 * feed, which hands in its {@code FeedTransport}, {@code Clock} and {@code FeedClient.Reader}, so nothing global is
 * discovered.
 *
 * <p>A <strong>support module, not an SPI contract module</strong>: it carries {@code java.net.http} and the store, so
 * no module may {@code requires transitive} it; the implementation that fetches depends on it, never the seam that
 * declares.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.feed {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.net;
    requires build.jenesis.repository.store;
    requires java.net.http;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.feed;
}
