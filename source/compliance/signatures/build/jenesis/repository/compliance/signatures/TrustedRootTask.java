package build.jenesis.repository.compliance.signatures;

import module java.base;
import module java.net.http;
import build.jenesis.repository.compliance.SignatureScheme;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The pass that keeps this deployment's Sigstore trusted root current: it fetches the document
 * {@code signature-sigstore-trusted-root-url} names - the public-good root by default - and stores it where
 * {@link FetchedTrustedRoot} reads it, so a bundle is verified against a root that was fetched off the request
 * path rather than against whatever a verification could reach at the moment it ran.
 *
 * <h2>Nothing is fetched until a URL is set</h2>
 *
 * The dial is empty by default and this pass does not run, so a stock deployment makes no outbound call: a
 * repository is not a thing that should reach the internet because it was installed. An operator who wants the
 * public-good instance sets the dial to {@value #DEFAULT_URL}, which is the document the Sigstore project
 * publishes and the value the setting's own text names so it can be copied rather than remembered.
 *
 * <p><b>What that URL trusts, when it is set.</b> Taking the document over HTTPS trusts that host and the
 * organisation behind it to serve the real thing, which is a weaker statement than the one the document itself
 * can make: the same root is served through a TUF repository ({@code https://tuf-repo-cdn.sigstore.dev}), where
 * it is signed by the project's own keys and a client verifies those signatures against a root of trust it was
 * shipped with. The library this build already carries has that client, and teaching this pass the TUF form is
 * the next thing it should learn; an operator wanting the stronger statement today points the dial at their own
 * mirror of the document.
 *
 * <p><b>A pasted root wins, and then nothing is fetched.</b> {@code signature-sigstore-trusted-root} is the
 * operator's own document - a self-hosted Fulcio's, or the public one pinned by hand - and a deployment that set
 * it has said what it verifies against; the pass makes no outbound call at all in that case either.
 *
 * <h2>What a fetched root does not do</h2>
 *
 * It re-judges nothing by itself. A version screened before a root was held carries a recorded summary saying its
 * bundle chained to no root; the retroactive sweep re-judges recorded summaries under the policy as it stands but
 * re-verifies no bytes, so such a version stays as it was recorded until something re-derives it - a late sidecar,
 * a re-publish. That is a deliberate limit of the sweep rather than of this pass, and it is stated in the setting's
 * own text so an operator switching the root on does not read an old UNTRUSTED as today's answer.
 */
public final class TrustedRootTask implements MaintenanceTask {

    static final String NAME = "sigstore-trusted-root";

    /** Where the trusted root is fetched from; empty - which is what a deployment that says nothing has - means
     *  no fetch and no pass. */
    static final String URL = "signature-sigstore-trusted-root-url";

    /** The public-good instance's published root: not a default, but the value the setting's text names for an
     *  operator who wants it, and what the fetch is designed around. */
    static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/sigstore/root-signing/main/targets/trusted_root.json";

    static final IntervalSetting INTERVAL = IntervalSetting.of("signature-sigstore-trusted-root-interval", "P1D");

    /** The most of a document this pass reads. A trusted root is tens of kilobytes; a megabyte is generous, and a
     *  host answering with something larger is answering with something else. */
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
            // The operator said what they verify against. Fetching anyway would make an outbound call whose answer
            // nothing reads, which is the one thing a quiet deployment must not do behind its own configuration.
            return;
        }
        Optional<byte[]> fetched = fetcher.fetch(URI.create(url));
        if (fetched.isEmpty()) {
            throw new IOException("the trusted root at " + url + " was not served");
        }
        // A host that answered with a login page, an error document or a root this build cannot read is a host
        // that did not answer. Storing it would replace a working root with something no bundle verifies against,
        // and the failure would surface as every bundle going untrusted rather than as this pass. The Sigstore
        // verifier reads it, through the scheme seam, so this pass carries no Sigstore library of its own; with no
        // verifier installed nothing could verify against the root either, and it is refused the same way.
        byte[] root = SignatureScheme.installed(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE)
                .flatMap(sigstore -> sigstore.trustMaterial(fetched.get()))
                .orElseThrow(() -> new IOException("what " + url + " served is not a Sigstore trusted root this "
                        + "build can read"));
        ArtifactStore store = context.store();
        Optional<ArtifactStore.Versioned> current = store.readVersioned(FetchedTrustedRoot.DOCUMENT);
        if (current.isPresent() && Arrays.equals(current.get().content(), root)) {
            context.gauge("jenreg.signature.trusted-root-bytes", "The size of the Sigstore trusted root held",
                    Map.of("source", "fetched"), root.length);
            return;   // unchanged: no write, so a daily pass over an unchanging document costs one read
        }
        store.writeVersioned(FetchedTrustedRoot.DOCUMENT, root,
                current.map(ArtifactStore.Versioned::token).orElse(null));
        context.gauge("jenreg.signature.trusted-root-bytes", "The size of the Sigstore trusted root held",
                Map.of("source", "fetched"), root.length);
    }

    /** The default fetch over the JDK's client; a {@code 404} is a host saying it has none, anything else a failure. */
    private static Optional<byte[]> download(URI url) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
        byte[] body = response.body();
        if (body.length > LARGEST) {
            throw new IOException("the document at " + url + " is larger than the " + LARGEST + "-byte bound");
        }
        return Optional.of(body);
    }
}
