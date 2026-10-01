package build.jenesis.repository.importer;

import module java.base;

import build.jenesis.repository.net.Origins;
import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.format.ProxyFormat;

/**
 * The one screen a migration's outbound requests pass: the <em>edge</em> shape judging the URL an operator submitted,
 * and the <em>fetch</em> shape judging every URL a source hands back.
 *
 * <h2>Why at the fetch</h2>
 * A source is a remote party describing where to fetch from: a Nexus listing's per-asset {@code downloadUrl}, an
 * index's absolute coordinate URLs. A hostile incumbent controls those URLs completely, so screening only what the
 * operator typed screens the uninteresting one, and per-connector screens drift apart. {@link ProxyFormat.Fetcher} is
 * the only transport a connector may use ({@link ImportSourceProvider} clause 10), so a screen on it is one a connector
 * cannot forget or disagree with, and {@link ImportSourceProvider#open} is how an edge builds a source.
 *
 * <h2>The rules</h2>
 * The fetch shape judges a URL against the one the operator authorised, so it needs no dial of its own:
 * <ol>
 *   <li><b>{@code http(s)} only.</b> A {@code file:}, {@code jar:} or {@code ftp:} URL describes no repository
 *       asset.</li>
 *   <li><b>No transport downgrade.</b> An {@code https} migration never fetches over cleartext, which protects the
 *       bytes: an intermediary could otherwise substitute what is written into the hosted store, with no integrity
 *       check behind an import to catch it.</li>
 *   <li><b>No cross-origin internal host.</b> A URL on another origin must not resolve to a private, loopback,
 *       link-local, site-local, multicast, CGNAT or unique-local address ({@link PrivateHosts}). Same-origin is exempt,
 *       going where the operator already pointed, which keeps an on-premises migration working.</li>
 * </ol>
 * A refusal <b>fails the walk</b> rather than dropping the asset, which would be counted nowhere and report a completed
 * import that took nothing. The job records the refusal and keeps its cursor.
 *
 * <h2>What this is not</h2>
 * It is not an integrity check, and none is available: the checksums a migration sees are served by the party serving
 * the bytes. The transport rule is therefore all that stands against a substituted artifact, so it has no dial.
 */
public final class ImportScreen implements ProxyFormat.Fetcher {

    private final ProxyFormat.Fetcher delegate;
    private final URI authorised;

    private ImportScreen(ProxyFormat.Fetcher delegate, URI authorised) {
        this.delegate = delegate;
        this.authorised = authorised;
    }

    /** Wrap {@code fetcher} so every URL a source fetches is screened against {@code authorised}, the URL the operator
     *  submitted and the edge screened; what {@link ImportSourceProvider#open} hands a connector. A missing
     *  {@code authorised} throws rather than leaving the transport unscreened. {@link ProxyFormat.Fetcher#NONE} reaches
     *  no network and is not wrapped. */
    public static ProxyFormat.Fetcher around(ProxyFormat.Fetcher fetcher, URI authorised) {
        if (fetcher == ProxyFormat.Fetcher.NONE) {
            return fetcher;                              // answers every leg empty; there is nothing to screen
        }
        if (authorised == null) {
            throw new IllegalArgumentException("An import cannot be screened without the URL the operator submitted");
        }
        return new ImportScreen(fetcher, authorised);
    }

    /** The reason a URL a source handed back must not be fetched, or {@code null}: the three rules in order, so only
     *  the rule that needs it pays a DNS resolution. */
    public static String refusalReason(URI authorised, URI url) {
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "it is not an http(s) URL (scheme '" + scheme + "')";
        }
        if (!scheme.equalsIgnoreCase("https") && "https".equalsIgnoreCase(authorised.getScheme())) {
            return "it downgrades an https migration to cleartext (scheme '" + scheme + "'), so the bytes it "
                    + "delivers can be substituted on the path";
        }
        if (Origins.same(authorised, url)) {
            return null;            // exactly where the operator pointed the importer, at the level they authorised
        }
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            return "it names no host";
        }
        return PrivateHosts.resolvesToPrivate(host)
                ? "it is a cross-origin URL to a private, loopback or cloud-metadata host"
                : null;
    }

    /**
     * The reason the URL an operator submitted must be refused under the current dial, or {@code null}: the edge shape,
     * the one leg taking a dial. A request may carry the incumbent's credentials, so plaintext exposes them, and an
     * unrestricted host makes the endpoint an SSRF against the deployment's own network.
     *
     * <p><b>One dial governs both halves</b>, {@code block-private-import-hosts}: separate dials would let an operator
     * permit cleartext while the guard reads as on. An on-premises migration, usually private and plaintext, sets that
     * dial.
     *
     * <p><b>An unresolvable host stays admissible</b>: it cannot be reached, and the source's own probe reports it
     * better. The transport is judged first, so a plaintext URL pays no resolution.
     */
    public static String refusalReason(String url, boolean blockPrivateHosts) {
        if (!blockPrivateHosts) {
            return null;                                 // the single explicit opt-out, and it covers both halves
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return "the URL is malformed";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "the URL is not an http(s) URL (scheme '" + scheme + "')";
        }
        if (!scheme.equalsIgnoreCase("https")) {
            return "the URL is not https (scheme '" + scheme + "')";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "the URL names no host";
        }
        return PrivateHosts.resolvesToPrivate(host)
                ? "the host resolves to a private, loopback, link-local or cloud-metadata address"
                : null;
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
        return delegate.fetch(screen(url), requestHeaders);
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
        return delegate.download(screen(url), requestHeaders);
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
        // Declared rather than derived from download(), which would open a body to answer a metadata question.
        return delegate.head(screen(url), requestHeaders);
    }

    private URI screen(URI url) throws IOException {
        String refusal = refusalReason(authorised, url);
        if (refusal != null) {
            throw ImportFailure.protocol("Refusing to fetch " + url + " for a migration from " + authorised
                    + ": " + refusal);
        }
        return url;
    }

    @Override
    public String toString() {
        return "ImportScreen[" + authorised + " -> " + delegate + "]";
    }
}
