/**
 * The shared bounded feed client: the one mechanism every externally-sourced HTTP JSON feed - a vulnerability
 * advisory API, a known-exploited catalogue, an exploit-probability model, a maintainer-health dataset - rides
 * instead of hand-rolling its own client. It owns exactly the concerns that are the same for every vendor and are
 * where the recurring audit defects lived: {@code HttpClient} setup and timeouts, header (authentication) injection,
 * the non-200 branch, the whole-fetch deadline, bounded cursor pagination with an <em>explicit</em> cap exhaustion,
 * bounded response bodies, retry backoff, the fail-closed / fail-soft policy, the clean self-skip when a feed is not
 * configured, and - for a feed that mirrors a whole catalogue - the snapshot and its staleness stamp committed
 * together through the {@code ArtifactStore}, pointer-last, with the prior-good snapshot retained whenever a refresh
 * does not complete.
 *
 * <p>What it deliberately does <em>not</em> own is everything vendor-specific: the URLs, the credentials, the wire
 * shape, the field mapping and the ecosystem/coordinate mapping all stay with the feed implementation, which reaches
 * the client through an injected {@code FeedTransport} and {@code Clock} and hands its own {@code FeedClient.Reader}
 * in to fold each page. Because the transport, the clock and the store all arrive as arguments, the client discovers
 * no global state: a contract suite drives a whole feed from recorded responses with a transport that would throw on
 * a real socket, and a read path can be asserted to make no request at all.
 *
 * <p>This is a <strong>support module, not an SPI contract module</strong>. It carries the weight an SPI must not -
 * {@code java.net.http} and the store - so a {@code java.base}-light contract interface is never coupled to a
 * transport by requiring it. No module may {@code requires transitive} it; the feed client is depended on by the
 * implementation that fetches, never leaked through the seam that declares.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j 2.0.18
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.feed {
    requires build.jenesis.repository.store;
    requires java.net.http;
    requires org.slf4j;
    exports build.jenesis.repository.feed;
}
