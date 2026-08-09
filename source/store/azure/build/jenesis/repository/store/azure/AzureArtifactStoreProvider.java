package build.jenesis.repository.store.azure;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;

import module java.base;

/**
 * The {@code azure-blob} artifact-store backend over an Azure Blob Storage container. Selected with
 * {@code jenesis.repository.store=azure-blob}; configured by {@code JENESIS_AZURE_CONNECTION_STRING}
 * (a storage-account connection string, or the Azurite development string) and an optional
 * {@code JENESIS_AZURE_CONTAINER} (default {@code jenesis-repository}). The blob I/O and the conditional
 * compare-and-set semantics live in {@link AzureArtifactStore}.
 *
 * <p>The blob endpoint the connection string resolves to is required to be {@code https} unless
 * {@code JENESIS_AZURE_ALLOW_INSECURE_ENDPOINT=true} explicitly permits a plaintext one - the same &sect;13 screen the
 * {@code s3} and {@code gcs} siblings apply to their own endpoint keys, reached here through the connection string
 * because that is where this SDK carries the scheme. Azure's account key rides inside the very value that also selects
 * the transport, so a {@code DefaultEndpointsProtocol=http} puts the shared-key signature and every artifact byte on a
 * plaintext wire that no error will ever surface - a plaintext exchange succeeds.
 */
public final class AzureArtifactStoreProvider implements ArtifactStoreProvider {

    @Override
    public String name() {
        return "azure-blob";
    }

    @Override
    public Set<String> requiredConfig() {
        return Set.of("JENESIS_AZURE_CONNECTION_STRING");
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String connectionString = config.apply("JENESIS_AZURE_CONNECTION_STRING");
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalStateException(
                    "JENESIS_AZURE_CONNECTION_STRING is required for the azure-blob artifact store backend.");
        }
        secureEndpoint(blobEndpoint(connectionString), config.apply("JENESIS_AZURE_ALLOW_INSECURE_ENDPOINT"));
        String containerName = config.apply("JENESIS_AZURE_CONTAINER");
        if (containerName == null || containerName.isBlank()) {
            containerName = "jenesis-repository";
        }
        BlobServiceClient service = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
        BlobContainerClient container = service.getBlobContainerClient(containerName);
        try {
            container.createIfNotExists();
        } catch (BlobStorageException ignored) {
            // The container may already exist or the credentials may not permit creation; the operations
            // below surface a clear error if the container is truly unusable.
        }
        return new AzureArtifactStore(container);
    }

    /**
     * The blob endpoint the connection string resolves to, required to be {@code https} by default so the account key
     * and artifact bytes are not sent over a plaintext transport a MITM can read or tamper with. A plaintext
     * {@code http} endpoint - a local Azurite container, say - is an explicit opt-out: set
     * {@code JENESIS_AZURE_ALLOW_INSECURE_ENDPOINT=true}. This is the {@code s3}/{@code gcs} rule, spelled the same
     * way, for the one backend whose transport is not a config key of its own.
     *
     * <p>A {@code null} endpoint - a connection string declaring neither a {@code BlobEndpoint} nor a
     * {@code DefaultEndpointsProtocol} - is a shape the SDK itself refuses, so it is left to the SDK's own diagnostic
     * rather than answered with a second one. The screen judges the <em>scheme</em> only: whether the endpoint is
     * reachable, whether its certificate validates and whether the container exists are the client's business and
     * surface as its own errors.
     *
     * @throws IllegalStateException at resolution, before any client is built or any key is signed with (&sect;9).
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        if (endpoint == null) {
            return null;
        }
        URI override = URI.create(endpoint);
        String scheme = override.getScheme();
        boolean https = scheme != null && scheme.equalsIgnoreCase("https");
        if (!https && !Boolean.parseBoolean(allowInsecure)) {
            throw new IllegalStateException("JENESIS_AZURE_CONNECTION_STRING must resolve to an https:// blob endpoint"
                    + " (got '" + endpoint + "'), or the account key and every artifact byte travel in clear; set"
                    + " JENESIS_AZURE_ALLOW_INSECURE_ENDPOINT=true to allow a plaintext endpoint, e.g. a local Azurite"
                    + " container.");
        }
        return override;
    }

    /**
     * The blob endpoint a connection string resolves to, or {@code null} when it declares neither. An explicit
     * {@code BlobEndpoint} wins wherever it appears, because it is what blob traffic actually uses; otherwise
     * {@code DefaultEndpointsProtocol} carries the scheme, which is all the screen judges. The
     * {@code UseDevelopmentStorage=true} shorthand expands to Azurite's fixed plaintext loopback endpoint.
     *
     * <p>Public so its own test module can pin the extraction directly: unlike the {@code s3} and {@code gcs}
     * siblings, whose endpoint is a config key of its own, this backend's transport is buried in a value that also
     * carries the account key, and getting the extraction wrong would silently disarm the screen.
     */
    public static String blobEndpoint(String connectionString) {
        String protocol = null;
        boolean development = false;
        for (String part : connectionString.split(";")) {
            int split = part.indexOf('=');
            if (split < 0) {
                continue;
            }
            String name = part.substring(0, split).trim();
            String value = part.substring(split + 1).trim();
            if (name.equalsIgnoreCase("BlobEndpoint")) {
                return value;
            }
            if (name.equalsIgnoreCase("UseDevelopmentStorage")) {
                development = Boolean.parseBoolean(value);
            } else if (name.equalsIgnoreCase("DefaultEndpointsProtocol")) {
                protocol = value;
            }
        }
        if (development) {
            return "http://127.0.0.1:10000/devstoreaccount1";
        }
        return protocol == null ? null : protocol + "://blob.core.windows.net";
    }
}
