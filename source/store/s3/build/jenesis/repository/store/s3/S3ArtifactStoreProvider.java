package build.jenesis.repository.store.s3;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.ConditionalWrites;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.net.http.aws.AwsHttpClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The {@code s3} artifact-store backend over any S3-compatible bucket (AWS S3, GCS through the XML API, MinIO,
 * LocalStack), selected with {@code jenrepo.store=s3} and configured by {@code jenrepo.s3.bucket} (required),
 * {@code jenrepo.s3.region} (default {@code us-east-1}) and an optional {@code jenrepo.s3.endpoint} (enabling
 * path-style access; {@code https} unless {@code jenrepo.s3.allow-insecure-endpoint=true}). Credentials come from the
 * standard AWS chain unless {@code jenrepo.s3.access-key-id} and {@code jenrepo.s3.secret-access-key} are both supplied
 * through the config lookup - the path a self-hosted store (MinIO, Ceph) takes, and how a test drives {@code create()}
 * without touching the environment. Every object is encrypted server-side: SSE-S3 by default, {@code aws:kms} when
 * {@code jenrepo.s3.sse-kms-key-id} names a key; encryption cannot be turned off. Blob I/O and compare-and-set are
 * {@link S3ArtifactStore}'s.
 */
public final class S3ArtifactStoreProvider implements ArtifactStoreProvider {

    /** The one setting with no ambient fallback, so the one declared required; composed through
     *  {@link Features#key}. */
    public static final String BUCKET_KEY = Features.key("s3.bucket");

    /** The config key an {@code s3} endpoint override is read from, named once for the screen and the resolution. */
    public static final String ENDPOINT_KEY = Features.key("s3.endpoint");

    /** The config key that opts {@link #ENDPOINT_KEY} out of the https-only transport screen. */
    public static final String ALLOW_INSECURE_KEY = Features.key("s3.allow-insecure-endpoint");

    /** The config key that switches off the boot-time conditional-write probe ({@code false}); on by default. The probe
     *  refuses to start over an endpoint that ignores a write precondition, under which two nodes would silently lose
     *  each other's writes; off, the node warns on every start. */
    public static final String PROBE_KEY = Features.key("s3.conditional-write-probe");


    /**
     * Whether a conditional write may stream its body ({@code true} by default). Some listings written under
     * compare-and-set are proportional to the repository - a catalogue, a Simple index, a folder page - and buffering
     * puts such a document whole in memory on the write path. Turn it off only to work around a storage implementation,
     * expecting the memory ceiling to fall with it.
     *
     * <p>"S3-compatible" is a spectrum: AWS, MinIO and Azurite pass the store contract's streamed compare-and-set,
     * while Ceph, Wasabi and older MinIO builds implement the conditional headers to varying degrees.
     */
    public static final String STREAMING_WRITES_KEY = Features.key("s3.streaming-writes");

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public String where() {
        return "in S3";
    }

    @Override
    public Set<String> config() {
        return Set.of(BUCKET_KEY, ENDPOINT_KEY, ALLOW_INSECURE_KEY, PROBE_KEY, STREAMING_WRITES_KEY,
                Features.key("s3.region"), Features.key("s3.sse-kms-key-id"), Features.key("s3.access-key-id"),
                Features.key("s3.secret-access-key"));
    }

    @Override
    public Set<String> requiredConfig() {
        // The credentials may be ambient, so only the bucket is required.
        return Set.of(BUCKET_KEY);
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String bucket = ArtifactStoreProvider.required(config, BUCKET_KEY, "s3");
        String region = config.apply(Features.key("s3.region"));
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClient(new AwsHttpClient())
                .credentialsProvider(credentials(config));
        // The presigner signs against the client's region, credentials and endpoint/path style, or its URLs would point
        // at the wrong host.
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials(config));
        String endpoint = config.apply(ENDPOINT_KEY);
        if (endpoint != null && !endpoint.isBlank()) {
            URI override = secureEndpoint(endpoint, config.apply(ALLOW_INSECURE_KEY));
            builder.endpointOverride(override).forcePathStyle(true);
            presignerBuilder.endpointOverride(override)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        S3Client s3 = builder.build();
        S3Presigner presigner = presignerBuilder.build();
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception ignored) {
            // The bucket may exist or creation may not be permitted; the operations below report a truly unusable one.
        }
        // Encryption is always on: SSE-S3 by default, aws:kms with the operator's key when s3.sse-kms-key-id is
        // supplied.
        String kmsKeyId = config.apply(Features.key("s3.sse-kms-key-id"));
        S3ArtifactStore store = new S3ArtifactStore(s3, presigner, bucket, kmsKeyId,
                !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)));
        // Every compare-and-set rests on the endpoint refusing a write whose precondition fails, which not every
        // S3-compatible endpoint does: asked once at boot, answered by refusing to start.
        try {
            ConditionalWrites.probe(store, endpoint == null || endpoint.isBlank() ? "the S3 endpoint for bucket " + bucket
                    : "the S3-compatible endpoint " + endpoint, config.apply(PROBE_KEY));
        } catch (IOException failure) {
            store.close();
            throw new IllegalStateException("the s3 store could not be probed for conditional writes at boot - is bucket "
                    + bucket + " writable with these credentials? " + failure.getMessage(), failure);
        } catch (RuntimeException refused) {
            store.close();
            throw refused;
        }
        return store;
    }

    /** The endpoint override, {@code https} unless {@code jenrepo.s3.allow-insecure-endpoint=true} (a local MinIO or
     *  LocalStack, say), so credentials and artifact bytes never cross a plaintext transport. The rule is
     *  {@link Endpoints#secure}, shared with {@code gcs} and {@code azure-blob}; this binds this backend's keys to
     *  it. */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /** Static keys when both {@code jenrepo.s3.access-key-id} and {@code jenrepo.s3.secret-access-key} are in the
     *  config lookup, otherwise the standard AWS chain (environment, profile, instance role). */
    private static AwsCredentialsProvider credentials(UnaryOperator<String> config) {
        String accessKey = config.apply(Features.key("s3.access-key-id"));
        String secretKey = config.apply(Features.key("s3.secret-access-key"));
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
