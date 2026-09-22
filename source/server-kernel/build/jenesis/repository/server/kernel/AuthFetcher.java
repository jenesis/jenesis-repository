package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.upstream.UpstreamCredentialSource;

/**
 * A {@link ProxyFormat.Fetcher} that fills in the upstream {@code Authorization} header (from the discovered
 * {@link UpstreamCredentialSource}) before delegating to the real HTTP fetcher, so the language-format pull-through proxies can
 * reach a private upstream. The fetcher already carries a request-headers map; this sets the
 * Authorization for the target host when one is configured and the caller has not supplied its own.
 *
 * <p>It is a <b>decorator</b>, so it is never a {@link ProxyFormat.Fetcher.Buffered}: all three legs - the buffered
 * {@code fetch}, the streaming {@code download} and the metadata {@code head} - are delegated to the transport below
 * with the credential filled in, and none is derived from another. A credential wrapper that derived {@code download}
 * or {@code head} would discard the real streaming and {@code HEAD} legs of the fetcher it wraps and substitute
 * whole-body derivations, collapsing a deployment's streaming path with no line of its own code saying so (&sect;1).
 * The credential is added to a {@code HEAD} exactly as to a {@code GET}: a private upstream answers a metadata probe
 * only when the probe is authorized, so an un-augmented {@code head} would report a {@code 401} for an artifact the
 * very next {@code download} fetches successfully.
 */
public final class AuthFetcher implements ProxyFormat.Fetcher {

    private final ProxyFormat.Fetcher delegate;
    private final UpstreamCredentialSource credentials;

    public AuthFetcher(ProxyFormat.Fetcher delegate, UpstreamCredentialSource credentials) {
        this.delegate = delegate;
        this.credentials = credentials;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> headers) throws IOException {
        return delegate.fetch(url, augment(url, headers));
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> headers) throws IOException {
        // Delegate to the streaming download of the real fetcher (never the buffering default), so a large proxied
        // artifact copies network-to-store without being materialised whole - the credential wrapper must not
        // silently collapse the streaming leg back onto the buffered fetch (Principle 1: stream, never buffer).
        return delegate.download(url, augment(url, headers));
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> headers) throws IOException {
        // Delegate to the real HTTP HEAD of the fetcher below - with the credential filled in, exactly as the other
        // two legs get it - never to a derivation. Deriving head from download would open (though never read) the
        // body of an artifact whose size was all the caller wanted, answering a metadata question by starting a body
        // transfer, and would throw away the transport's real HEAD in the process (Principle 1: stream, never buffer;
        // a HEAD answers from metadata).
        return delegate.head(url, augment(url, headers));
    }

    /** The request headers with the upstream credential for {@code url} filled in where the caller has not already set it. */
    private Map<String, String> augment(URI url, Map<String, String> headers) {
        Map<String, String> credential = credentials.headers(url);
        if (credential.isEmpty()) {
            return headers;
        }
        Map<String, String> augmented = new LinkedHashMap<>(headers);
        credential.forEach(augmented::putIfAbsent);
        return augmented;
    }
}
