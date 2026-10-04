package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;

/**
 * A screen over an upstream, as the gate wraps the fetcher a fill serves through: every document fetched through it is
 * judged as the artifact the fill serves, which it records, while a document read {@linkplain #beside() beside} the
 * artifact goes to the upstream unjudged.
 */
final class JudgingScreen implements ProxyFormat.Fetcher {

    /** What the screen was asked to judge as the artifact, in order. */
    final List<URI> judged = new ArrayList<>();

    private final ProxyFormat.Fetcher upstream;

    JudgingScreen(ProxyFormat.Fetcher upstream) {
        this.upstream = upstream;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        judged.add(url);
        return upstream.fetch(url, requestHeaders);
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        judged.add(url);
        return upstream.download(url, requestHeaders);
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        judged.add(url);
        return upstream.head(url, requestHeaders);
    }

    @Override
    public ProxyFormat.Fetcher beside() {
        return upstream;
    }
}
