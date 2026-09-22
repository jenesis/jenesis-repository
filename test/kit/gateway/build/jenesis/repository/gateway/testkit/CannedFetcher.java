package build.jenesis.repository.gateway.testkit;

import module java.base;

import build.jenesis.repository.format.ProxyFormat;

/**
 * A canned in-memory {@link ProxyFormat.Fetcher} for the enumeration walks: answers from a fixed URL-to-response
 * map (an unmapped URL is a transport failure) and records every requested URL, so a test asserts both what a walk
 * fetched and what it never touched. It is a {@link ProxyFormat.Fetcher.Buffered} - a degenerate upstream whose whole
 * answer is a small in-memory document, so the streaming and metadata legs are legitimately derived from the buffered
 * one and it says so in its type rather than by inheriting a default.
 */
public final class CannedFetcher implements ProxyFormat.Fetcher.Buffered {

    private final Map<String, ProxyFormat.Fetched> responses = new HashMap<>();

    public final List<String> urls = new ArrayList<>();

    public CannedFetcher on(String url, int status, byte[] body) {
        responses.put(url, new ProxyFormat.Fetched(status, body, Map.of()));
        return this;
    }

    public CannedFetcher on(String url, int status, String body) {
        return on(url, status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
        urls.add(url.toString());
        return Optional.ofNullable(responses.get(url.toString()));
    }
}
