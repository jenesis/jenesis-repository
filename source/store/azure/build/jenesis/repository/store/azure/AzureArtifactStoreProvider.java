package build.jenesis.repository.store.azure;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;

import module java.base;

/**
 * The {@code azure-blob} artifact-store backend over an Azure Blob Storage container. Selected with
 * {@code jenesis.repository.store=azure-blob}; configured by {@code jenesis.repository.azure-blob.connection-string}
 * (a storage-account connection string, or the Azurite development string) and an optional
 * {@code jenesis.repository.azure-blob.container} (default {@code jenesis-repository}). The blob I/O and the
 * conditional compare-and-set semantics live in {@link AzureArtifactStore}.
 *
 * <p>The blob endpoint the connection string resolves to is required to be {@code https} unless
 * {@code jenesis.repository.azure-blob.allow-insecure-endpoint=true} explicitly permits a plaintext one - the same
 * &sect;13 screen the {@code s3} and {@code gcs} siblings apply to their own endpoint keys, reached here through the
 * connection string because that is where this SDK carries the scheme. Azure's account key rides inside the very value
 * that also selects the transport, so a {@code DefaultEndpointsProtocol=http} puts the shared-key signature and every
 * artifact byte on a plaintext wire that no error will ever surface - a plaintext exchange succeeds.
 */
public final class AzureArtifactStoreProvider implements ArtifactStoreProvider {

    /** The config key an {@code azure-blob} connection string - and with it the blob endpoint's scheme - is read
     *  from, named here so the screen's refusal and the resolution that applies it cannot drift apart. */
    public static final String CONNECTION_STRING_KEY = Features.key("azure-blob.connection-string");

    /** The blob container, defaulted when unset. */
    public static final String CONTAINER_KEY = Features.key("azure-blob.container");

    /** The config key that opts the endpoint {@link #CONNECTION_STRING_KEY} resolves to out of the https-only
     *  transport screen. */
    public static final String ALLOW_INSECURE_KEY = Features.key("azure-blob.allow-insecure-endpoint");

    @Override
    public String name() {
        return "azure-blob";
    }

    @Override
    public Set<String> requiredConfig() {
        return Set.of(CONNECTION_STRING_KEY);
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String connectionString = ArtifactStoreProvider.required(config, CONNECTION_STRING_KEY, "azure-blob");
        secureEndpoint(blobEndpoint(connectionString), config.apply(ALLOW_INSECURE_KEY));
        String containerName = config.apply(CONTAINER_KEY);
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
     * {@code jenesis.repository.azure-blob.allow-insecure-endpoint=true}. This is the {@code s3}/{@code gcs} rule,
     * spelled the same way, for the one backend whose transport is not a config key of its own.
     *
     * <p>A {@code null} endpoint - a connection string declaring neither a {@code BlobEndpoint} nor a
     * {@code DefaultEndpointsProtocol} - is a shape the SDK itself refuses, so it is left to the SDK's own diagnostic
     * rather than answered with a second one. The screen judges the <em>scheme</em> only: whether the endpoint is
     * reachable, whether its certificate validates and whether the container exists are the client's business and
     * surface as its own errors.
     *
     * <p>The rule itself is {@link Endpoints#secure}, shared with the {@code s3} and {@code gcs} backends (D-023);
     * what is this backend's own is the pair of config keys it names - and the fact that the endpoint is
     * <em>extracted</em> from one of them rather than read from it - so this method is where the two are bound to the
     * screen.
     *
     * @throws IllegalStateException at resolution, before any client is built or any key is signed with (&sect;9).
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(CONNECTION_STRING_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
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
