package build.jenesis.repository.importer;

import build.jenesis.repository.format.PrivateHosts;
import build.jenesis.repository.format.ProxyFormat;

import module java.base;

/**
 * The one screen a migration's outbound requests pass, in two shapes over one set of rules: the <em>edge</em> shape
 * that judges the URL an operator submitted, and the <em>fetch</em> shape that judges every URL a source then hands
 * back. Both matter, and the second is the one that was missing.
 *
 * <h2>Why the fetch, and not the source</h2>
 * A migration source is a remote party <em>describing where to fetch from</em>. A Nexus components listing carries a
 * per-asset {@code downloadUrl}; a format's own index enumerates absolute coordinate URLs. Those URLs are
 * operator-supplied only at one remove, and a compromised or hostile incumbent controls them completely - so screening
 * only what the operator typed screens the one URL that was never the interesting one. Screening inside each connector
 * instead was the shape D-152 found: three connectors fetch listing-supplied URLs, two of them carried a screen, the
 * screens disagreed with each other (one skipped the asset, one failed the walk) and neither judged the transport, so
 * an {@code https} base whose listing answered {@code http://same-host/...} was <em>cross-origin</em> by scheme and
 * passed. Credentials were correctly withheld cross-origin, so nothing leaked; the artifact bytes were fetched in
 * cleartext and written into the hosted store with no integrity check behind them, which is a supply-chain
 * substitution rather than a disclosure.
 *
 * <p>So the screen sits at the fetch. {@link ProxyFormat.Fetcher} is the only transport a connector is allowed
 * ({@link ImportSourceProvider}'s contract clause 10: it "is handed the shared {@code Fetcher} and must use it -
 * wrapping it to add credentials is allowed, replacing it is not"), so a screen on that fetcher is one a connector
 * cannot forget, cannot disagree with, and cannot be written without. A connector added tomorrow arrives screened
 * without knowing this class exists. {@link ImportSourceProvider#open} is how a source is built, so an <em>edge</em>
 * cannot hand out an unscreened fetcher either.
 *
 * <h2>The rules</h2>
 * The fetch shape judges a URL against the one the operator authorised, so it needs no dial of its own - the
 * authorisation level is already stated by the submitted URL, which the edge shape screened under the dial:
 * <ol>
 *   <li><b>{@code http(s)} only.</b> A listing that names {@code file:}, {@code jar:} or {@code ftp:} is not
 *       describing a repository asset.</li>
 *   <li><b>No transport downgrade.</b> An {@code https} migration is never fetched from over cleartext. This is the
 *       half that was missing: it bites where the host half is silent, on a perfectly public host, and what it
 *       protects is not a secret (credentials are already withheld cross-origin) but <em>the bytes</em> - an active
 *       intermediary substitutes what the migration writes into the hosted store, and there is no independent
 *       integrity check behind an imported artifact to catch it (see below).</li>
 *   <li><b>No cross-origin internal host.</b> A URL on another origin must not resolve to a private, loopback,
 *       link-local, site-local, multicast, CGNAT or unique-local address - the shared {@link PrivateHosts} table the
 *       fetcher's redirect chain uses. Same-origin is exempt: it goes exactly where the operator already pointed the
 *       importer, which is what keeps an on-premises migration (a private-addressed base, opted in at the edge)
 *       resolving its own download URLs.</li>
 * </ol>
 * A refusal <b>fails the walk</b> rather than dropping the asset. A dropped asset is counted nowhere - not as
 * imported, not as skipped - so a migration off a source aiming its downloads at a metadata service reported
 * {@code completed} over an import that had silently taken nothing. The job records the refusal as its error and keeps
 * its cursor, so an operator reads what was refused and resumes once the source is fixed (&sect;9).
 *
 * <h2>What this is not</h2>
 * It is not an integrity check, and none is available. The only checksums a migration can see - the Nexus listing's
 * {@code checksum} block, Artifactory's {@code sha2}, a Maven repository's sibling {@code .sha1} - are served by the
 * same party that serves the bytes, so an intermediary that can substitute one substitutes the other. The transport
 * rule is therefore the whole of what stands between a migration and a substituted artifact, which is why it is not
 * gated on a dial of its own.
 */
public final class ImportScreen implements ProxyFormat.Fetcher {

    private final ProxyFormat.Fetcher delegate;
    private final URI authorised;

    private ImportScreen(ProxyFormat.Fetcher delegate, URI authorised) {
        this.delegate = delegate;
        this.authorised = authorised;
    }

    /**
     * Wrap {@code fetcher} so every URL a source fetches through it is screened against {@code authorised} - the URL
     * the operator submitted and the edge already screened. This is what {@link ImportSourceProvider#open} hands a
     * connector, and the reason a connector needs no screen of its own.
     */
    public static ProxyFormat.Fetcher around(ProxyFormat.Fetcher fetcher, URI authorised) {
        return fetcher == ProxyFormat.Fetcher.NONE || authorised == null
                ? fetcher                     // nothing to screen: NONE fetches nothing, and a source with no
                : new ImportScreen(fetcher, authorised);        // authorised URL never reaches a provider at all
    }

    /**
     * The reason a URL an import source handed back must not be fetched, or {@code null} when it may be - the three
     * rules above, in that order, so a refused URL pays a DNS resolution only for the rule that needs one.
     */
    public static String refusalReason(URI authorised, URI url) {
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "it is not an http(s) URL (scheme '" + scheme + "')";
        }
        if (!scheme.equalsIgnoreCase("https") && "https".equalsIgnoreCase(authorised.getScheme())) {
            return "it downgrades an https migration to cleartext (scheme '" + scheme + "'), so the bytes it "
                    + "delivers can be substituted on the path";
        }
        if (sameOrigin(authorised, url)) {
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
     * The reason the URL an operator <em>submitted</em> must be refused under the current dial, or {@code null} when
     * the migration may proceed - the edge shape, and the one leg that takes a dial. An {@link ImportRequest} carries
     * the incumbent's username and password when the operator supplies them, so a plaintext migration hands an
     * upstream credential to every observer on the path, and an unrestricted host turns the endpoint into a
     * server-side request against the deployment's own network (an SSRF).
     *
     * <p><b>One dial governs both halves.</b> {@code block-private-import-hosts} is the whole opt-out: an operator
     * able to permit cleartext but not internal hosts (or the reverse) is an operator who can send a credential in
     * the clear while the guard reads as on. An on-premises migration - typically both private-addressed <em>and</em>
     * plaintext - already sets that dial and is unaffected.
     *
     * <p><b>An unresolvable host stays admissible.</b> It cannot be reached, so it is not an SSRF vector, and the
     * source's own reachability probe gives the operator a better message than this screen masking it would. The
     * transport half is judged first, so a plaintext URL never pays a resolution and its operator is told about the
     * transport rather than about a host that was never the problem.
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

    /** Whether {@code url} shares the authorised URL's scheme <em>and</em> authority. The scheme is part of the origin
     *  on purpose: a same-host {@code http://} URL under an {@code https} migration is a different origin, and it is
     *  precisely the one this screen exists to refuse. */
    private static boolean sameOrigin(URI authorised, URI url) {
        return Objects.equals(authorised.getScheme(), url.getScheme())
                && Objects.equals(authorised.getRawAuthority(), url.getRawAuthority());
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
        // Declared, not derived: a decorator that let Buffered derive head() from download() would throw away the
        // transport's real HEAD and open a body to answer a question about metadata (ProxyFormat.Fetcher.Buffered).
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
