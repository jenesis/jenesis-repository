package build.jenesis.repository.store.azure;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ConditionalWrites;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;

/**
 * The {@code azure-blob} artifact-store backend over an Azure Blob Storage container, selected with
 * {@code jenrepo.store=azure-blob} and configured by {@code jenrepo.azure-blob.connection-string} (a storage-account or
 * Azurite connection string) and an optional {@code jenrepo.azure-blob.container} (default {@code jenesis-repository}).
 * The blob I/O and conditional writes are {@link AzureArtifactStore}'s.
 *
 * <p>The blob endpoint the connection string resolves to must be {@code https} unless
 * {@code jenrepo.azure-blob.allow-insecure-endpoint=true} - the screen the {@code s3} and {@code gcs} backends apply,
 * reached through the connection string because that is where this SDK carries the scheme. The account key rides in the
 * same value that selects the transport, so {@code DefaultEndpointsProtocol=http} would put the shared-key signature
 * and every artifact on a plaintext wire with no error to surface it.
 */
public final class AzureArtifactStoreProvider implements ArtifactStoreProvider {

    /** The config key the connection string - and with it the endpoint's scheme - is read from, named once for the
     *  screen and the resolution. */
    public static final String CONNECTION_STRING_KEY = Features.key("azure-blob.connection-string");

    /** Whether a conditional write may stream its body ({@code true} by default). Some listings, written under
     *  compare-and-set, are proportional to the repository; buffering puts such a document whole in memory on the write
     *  path. Turn it off only to work around a storage implementation, expecting the memory ceiling to fall with it. */
    public static final String STREAMING_WRITES_KEY = Features.key("azure-blob.streaming-writes");

    /** The blob container, defaulted when unset. */
    public static final String CONTAINER_KEY = Features.key("azure-blob.container");

    /** The config key that opts the endpoint {@link #CONNECTION_STRING_KEY} resolves to out of the https-only
     *  screen. */
    public static final String ALLOW_INSECURE_KEY = Features.key("azure-blob.allow-insecure-endpoint");

    /** The config key that switches off the boot-time conditional-write probe ({@code false}); on by default. The probe
     *  refuses to start over an endpoint that ignores a write precondition, under which two nodes would silently lose
     *  each other's writes; off, the node warns on every start. */
    public static final String PROBE_KEY = Features.key("azure-blob.conditional-write-probe");


    @Override
    public String name() {
        return "azure-blob";
    }

    @Override
    public Set<String> config() {
        return Set.of(CONNECTION_STRING_KEY, STREAMING_WRITES_KEY, CONTAINER_KEY, ALLOW_INSECURE_KEY, PROBE_KEY);
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
                .httpClient(new AzureTransport())
                .buildClient();
        BlobContainerClient container = service.getBlobContainerClient(containerName);
        try {
            container.createIfNotExists();
        } catch (BlobStorageException ignored) {
            // The container may exist or creation may not be permitted; the operations below report a truly unusable
            // one.
        }
        AzureArtifactStore store = new AzureArtifactStore(container,
                !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)));
        // The question every compare-and-set rests on, asked of every object-store endpoint at boot.
        try {
            ConditionalWrites.probe(store, "the blob endpoint for container " + containerName, config.apply(PROBE_KEY));
        } catch (IOException failure) {
            throw new IllegalStateException("the azure-blob store could not be probed for conditional writes at boot - "
                    + "is container " + containerName + " writable with these credentials? " + failure.getMessage(), failure);
        }
        return store;
    }

    /**
     * The blob endpoint, {@code https} unless {@code jenrepo.azure-blob.allow-insecure-endpoint=true} (a local Azurite,
     * say), so the account key and artifact bytes never cross a plaintext transport.
     *
     * <p>A {@code null} endpoint - a connection string declaring neither {@code BlobEndpoint} nor
     * {@code DefaultEndpointsProtocol} - is left to the SDK's own diagnostic. Only the scheme is judged; reachability,
     * certificates and the container are the client's to report.
     *
     * <p>The rule is {@link Endpoints#secure}, shared with {@code s3} and {@code gcs}; this binds this backend's keys,
     * the endpoint being extracted from one of them.
     *
     * @throws IllegalStateException at resolution, before any client is built or any key is signed with.
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(CONNECTION_STRING_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /**
     * The blob endpoint a connection string resolves to, or {@code null} when it declares neither. An explicit
     * {@code BlobEndpoint} wins wherever it appears, since blob traffic uses it; otherwise
     * {@code DefaultEndpointsProtocol} carries the scheme. {@code UseDevelopmentStorage=true} expands to Azurite's
     * plaintext loopback endpoint.
     *
     * <p>Public so its test module can pin the extraction: the transport is buried in a value that also carries the
     * account key, and a wrong extraction would silently disarm the screen.
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
