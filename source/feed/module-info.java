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
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.pin tools.jackson.databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.release 25
 */
module build.jenesis.repository.feed {
    requires build.jenesis.repository.store;
    requires java.net.http;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.feed;
}
