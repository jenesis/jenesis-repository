package build.jenesis.repository.compliance.signatures;

import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.compliance.ComplianceSettings;
import module java.base;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;

/**
 * Asks an attestation store for the bundles it holds for an artifact that was just published, by the artifact's
 * digest, and keeps the answer beside it as the sidecar its format reads as evidence. The shape exists for the
 * ecosystems whose provenance lives in a store keyed by digest rather than beside the artifact: Homebrew attests
 * every bottle its CI builds through GitHub Artifact Attestations, which are fetched from
 * {@code https://api.github.com/orgs/Homebrew/attestations/sha256:<digest>} and which no bottle domain ever serves.
 * A proxied artifact's companions are fetched beside the fill; a published one has nothing to fetch beside, only a
 * digest to ask with, which is why this is an observer of the publish rather than a companion of it.
 *
 * <p>Off unless {@code signature-attestation-lookup} names a store for the artifact's ecosystem, one
 * {@code <ecosystem> = <url>} per line, the way the pins are written. A store answering 404 holds nothing for the
 * digest, which is absence; any other failure is logged and the publish is unaffected, since the lookup is best
 * effort and the artifact's own verdict does not wait on it. An answer naming no bundle is not kept. What is kept
 * goes where the format serves the sidecar from - through its {@link BlobLayout#servingKey serving key} for a
 * format that keeps its pointers in a root of its own, else linked at the sidecar's path - and is then announced
 * as a publish of its own, so the completion observer re-derives the artifact's verdict over it exactly as it
 * does for a signature that arrives one request after its artifact.
 */
public final class AttestationLookupObserver implements PublicationObserver {

    /** The stores to ask, {@code <ecosystem> = <url>} per line; empty by default, so nothing is asked. */
    static final String STORES = "signature-attestation-lookup";

    /** The value a Homebrew mirror sets: GitHub's attestation store for the Homebrew organisation. */
    static final String HOMEBREW_STORE = "Homebrew = https://api.github.com/orgs/Homebrew/attestations/";

    /** The suffix the answer is kept under, beside the artifact. */
    static final String SIDECAR = ".attestations.json";

    private static final System.Logger LOGGER = System.getLogger(AttestationLookupObserver.class.getName());

    /** One lookup by digest: the store's answer, empty when it holds nothing for the digest, and a failure to ask
     *  thrown. */
    @FunctionalInterface
    public interface Fetcher {
        Optional<byte[]> fetch(URI url) throws IOException;
    }

    private final Fetcher fetcher;

    public AttestationLookupObserver() {
        this(AttestationLookupObserver::http);
    }

    /** Over a fetcher of the test's choosing, so a lookup is driven without a network. */
    public AttestationLookupObserver(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (artifact.path() == null || artifact.hash() == null || artifact.path().endsWith(SIDECAR)) {
            return;
        }
        Optional<URI> base = store(ComplianceSettings.lookup(), artifact.ecosystem());
        if (base.isEmpty()) {
            return;
        }
        String sidecar = artifact.path() + SIDECAR;
        RepositoryFormat format = null;
        for (RepositoryFormat installed : RepositoryFormat.installed()) {
            if (installed instanceof ArtifactSignatures signatures
                    && signatures.expects(artifact.path()).stream()
                            .anyMatch(expected -> expected.scheme() == ArtifactSignatures.Scheme.SIGSTORE_BUNDLE)
                    && signatures.covers(sidecar).isPresent()) {
                format = installed;
                break;
            }
        }
        if (format == null || present(format, sidecar, store)) {
            return;   // no format reads a bundle beside this artifact, or one is already kept there
        }
        // Spelled, not resolved: "sha256:" reads as a scheme to URI.resolve, which would answer the bare digest.
        URI url = URI.create(base.get() + "sha256:" + artifact.hash());
        Optional<byte[]> answer;
        try {
            answer = fetcher.fetch(url);
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "The attestation store {0} could not be asked about {1}: {2}",
                    url, artifact.path(), failure.toString());
            return;
        }
        if (answer.isEmpty() || answer.get().length > ArtifactSignatures.Material.LARGEST_SIGNATURE
                || !namesABundle(answer.get())) {
            return;
        }
        keep(format, sidecar, answer.get(), store);
        new Publication(store).published(ArtifactDescriptor.at(format.name(), sidecar));
    }

    /** The store the dial names for an ecosystem, resolved so a base without a trailing slash still resolves its
     *  digest beneath it. */
    static Optional<URI> store(UnaryOperator<String> config, String ecosystem) {
        String configured = config == null || ecosystem == null ? null : config.apply(STORES);
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        for (String entry : configured.split("[,\\n]")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (ecosystem.equalsIgnoreCase(entry.substring(0, equals).strip())) {
                String url = entry.substring(equals + 1).strip();
                if (url.isEmpty()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(URI.create(url.endsWith("/") ? url : url + "/"));
                } catch (IllegalArgumentException malformed) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Whether an answer names at least one bundle: GitHub's {@code attestations} list, a bare array, or a bundle -
     *  an empty list is the store's "nothing for this digest" and is not kept. */
    static boolean namesABundle(byte[] answer) {
        String text = new String(answer, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("[")) {
            return text.length() > 2 && !text.substring(1, text.length() - 1).isBlank();
        }
        return text.startsWith("{") && (text.contains("\"bundle\"") || text.contains("\"mediaType\""));
    }

    private static boolean present(RepositoryFormat format, String sidecar, ArtifactStore store) throws IOException {
        if (format instanceof BlobLayout layout) {
            Optional<String> key = layout.servingKey(sidecar, store);
            if (key.isPresent()) {
                return new Blobs(store).exists(key.get());
            }
        }
        return new Publication(store).blob(sidecar).isPresent();
    }

    private static void keep(RepositoryFormat format, String sidecar, byte[] body, ArtifactStore store)
            throws IOException {
        if (format instanceof BlobLayout layout) {
            Optional<String> key = layout.servingKey(sidecar, store);
            if (key.isPresent()) {
                new Blobs(store).write(key.get(), body);
                return;
            }
        }
        Publication publication = new Publication(store);
        publication.link(sidecar, publication.storeBlob(new ByteArrayInputStream(body)), body.length);
    }

    private static Optional<byte[]> http(URI url) throws IOException {
        HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("Accept", "application/vnd.github+json, application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted asking " + url, interrupted);
        }
        if (response.statusCode() == 200) {
            return Optional.of(response.body());
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        throw new IOException(url + " answered " + response.statusCode());
    }
}
