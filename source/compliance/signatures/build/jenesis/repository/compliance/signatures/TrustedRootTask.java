package build.jenesis.repository.compliance.signatures;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The pass that keeps this deployment's Sigstore trusted root current: it fetches the document
 * {@code signature-sigstore-trusted-root-url} names and stores it where {@link FetchedTrustedRoot} reads it, off the
 * request path.
 *
 * <p>The dial is empty by default and the pass does not run, so nothing is fetched until an operator sets it, for
 * instance to {@value #DEFAULT_URL}, the document the Sigstore project publishes. Fetching over HTTPS trusts that host;
 * the TUF repository serves the same root signed by the project's keys, which this pass does not yet read, so an
 * operator wanting more points it at a mirror. A pasted {@code signature-sigstore-trusted-root} wins and nothing is
 * fetched.
 *
 * <p>A fetched root re-judges nothing: a version recorded as chaining to no root stays so until a late sidecar or a
 * re-publish re-derives it, which the setting's text says.
 */
public final class TrustedRootTask implements MaintenanceTask {

    static final String NAME = "sigstore-trusted-root";

    /** Where the trusted root is fetched from; empty, the default, means no fetch and no pass. */
    static final String URL = "signature-sigstore-trusted-root-url";

    /** The public-good instance's published root, which the setting's text names. */
    static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/sigstore/root-signing/main/targets/trusted_root.json";

    static final IntervalSetting INTERVAL = IntervalSetting.of("signature-sigstore-trusted-root-interval", "P1D");

    /** The most of a document this pass reads; a root is tens of kilobytes. */
    static final int LARGEST = 1024 * 1024;

    /** One fetch of the document at a URL: its bytes, or empty where the host answered that it has none. */
    @FunctionalInterface
    public interface Fetcher {

        Optional<byte[]> fetch(URI url) throws IOException;
    }

    private final Duration interval;
    private final Fetcher fetcher;

    public TrustedRootTask(Duration interval) {
        this(interval, TrustedRootTask::download);
    }

    public TrustedRootTask(Duration interval, Fetcher fetcher) {
        this.interval = interval;
        this.fetcher = fetcher;
    }

    /** Whether a deployment fetches a root at all: only once a URL is set. */
    static boolean enabled(UnaryOperator<String> config) {
        return !url(config).isBlank();
    }

    static String url(UnaryOperator<String> config) {
        String configured = config == null ? null : config.apply(URL);
        return configured == null ? "" : configured.trim();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        String url = url(context.config());
        if (url.isBlank()) {
            return;
        }
        String pasted = context.config().apply(ConfiguredSignerTrust.SIGSTORE_ROOT);
        if (pasted != null && !pasted.isBlank()) {
            // A pasted root is used, so nothing is fetched.
            return;
        }
        Optional<byte[]> fetched = fetcher.fetch(URI.create(url));
        if (fetched.isEmpty()) {
            throw new IOException("the trusted root at " + url + " was not served");
        }
        // A document the installed Sigstore verifier cannot read is refused, so a working root is never replaced by
        // a login page.
        byte[] root = SignatureScheme.installed(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE)
                .flatMap(sigstore -> sigstore.trustMaterial(fetched.get()))
                .orElseThrow(() -> new IOException("what " + url + " served is not a Sigstore trusted root this "
                        + "build can read"));
        ArtifactStore store = context.store();
        Optional<ArtifactStore.Versioned> current = store.readVersioned(FetchedTrustedRoot.DOCUMENT);
        if (current.isPresent() && Arrays.equals(current.get().content(), root)) {
            context.gauge("jenrepo.signature.trusted-root-bytes", "The size of the Sigstore trusted root held",
                    Map.of("source", "fetched"), root.length);
            return;   // unchanged: no write, so a daily pass over an unchanging document costs one read
        }
        store.writeVersioned(FetchedTrustedRoot.DOCUMENT, root,
                current.map(ArtifactStore.Versioned::token).orElse(null));
        context.gauge("jenrepo.signature.trusted-root-bytes", "The size of the Sigstore trusted root held",
                Map.of("source", "fetched"), root.length);
    }

    /** The default fetch over the JDK's client; a {@code 404} is a host saying it has none, anything else a failure. */
    private static Optional<byte[]> download(URI url) throws IOException {
        HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .redirectsWithinPrivateNetwork().build();
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, BoundedBody.ofByteArray(url, LARGEST));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching the trusted root from " + url, interrupted);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("the trusted root at " + url + " answered HTTP " + response.statusCode());
        }
        return Optional.of(response.body());
    }
}
