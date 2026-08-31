package build.jenesis.repository.store.s3;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import module java.base;

/**
 * The {@code s3} artifact-store backend over any S3-compatible bucket (AWS S3, GCS via the XML API,
 * MinIO, LocalStack). Selected with {@code jenreg.store=s3}; configured by
 * {@code jenreg.s3.bucket} (required), {@code jenreg.s3.region} (default {@code us-east-1}) and
 * an optional {@code jenreg.s3.endpoint} (an S3-compatible endpoint, enabling path-style access; required
 * to be {@code https} unless {@code jenreg.s3.allow-insecure-endpoint=true} explicitly permits a plaintext
 * one). Credentials come from the standard AWS chain (environment, profile or instance role) unless
 * {@code jenreg.s3.access-key-id} and {@code jenreg.s3.secret-access-key} are both supplied through
 * the config lookup, in which case those static keys are used - the path a self-hosted S3-compatible store (MinIO,
 * Ceph) takes, and the seam that lets a test drive {@code create()} end to end against a container through an injected
 * config lookup, without touching the process environment. Every object is written server-side encrypted: SSE-S3
 * (AES256) by default, or {@code aws:kms} when an optional {@code jenreg.s3.sse-kms-key-id} names a key -
 * encryption cannot be turned off. The blob I/O and the conditional compare-and-set semantics live in {@link
 * S3ArtifactStore}.
 */
public final class S3ArtifactStoreProvider implements ArtifactStoreProvider {

    /** The one setting with no ambient fallback, so it is the one this backend declares as required config.
     *  Composed through {@link Features#key} rather than written out, so the namespace has a single definition. */
    public static final String BUCKET_KEY = Features.key("s3.bucket");

    /** The config key an {@code s3} endpoint override is read from - named here so the screen's refusal and the
     *  resolution that applies it cannot drift into naming different keys. */
    public static final String ENDPOINT_KEY = Features.key("s3.endpoint");

    /** The config key that opts {@link #ENDPOINT_KEY} out of the https-only transport screen. */
    public static final String ALLOW_INSECURE_KEY = Features.key("s3.allow-insecure-endpoint");

    /**
     * Whether a conditional write may stream its body ({@code true} by default).
     *
     * <p><b>Setting this to {@code false} restores a heap cost, and that is the whole of what it does.</b> A
     * listing is one object written under compare-and-set, and some listings are proportional to the repository -
     * a catalogue, a Simple index, a folder page. Streaming the write is what keeps such a document out of memory;
     * buffering puts it back, whole, on the path that writes it. Turn this off to work around a storage
     * implementation, never to change anything else, and expect the repository's memory ceiling to fall with it.
     *
     * <p>It exists because "S3-compatible" is a spectrum. AWS, MinIO and Azurite are all proven against the store
     * contract's streamed compare-and-set, but Ceph, Wasabi and older MinIO builds implement the conditional
     * headers to varying degrees, and an operator meeting one of those needs a way past it that is not a fork.
     */
    public static final String STREAMING_WRITES_KEY = Features.key("s3.streaming-writes");

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public Set<String> requiredConfig() {
        // The credentials may come from the ambient AWS chain (environment, profile, instance role), so only the
        // bucket is required configuration.
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
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(credentials(config));
        // The presigner mints direct-fetch GET URLs (ArtifactStore.presign); it must sign against the same region,
        // credentials and endpoint/path-style as the client, or a presigned URL would point at the wrong host.
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
            // The bucket may already exist or the credentials may not permit creation; the operations
            // below surface a clear error if the bucket is truly unusable.
        }
        // Server-side encryption is always on: SSE-S3 (AES256) by default, upgraded to aws:kms with the operator's
        // key when s3.sse-kms-key-id is supplied. There is no key that turns encryption off. The presigner
        // rides alongside so this store can also mint direct-fetch GET URLs (RD-1 presign).
        String kmsKeyId = config.apply(Features.key("s3.sse-kms-key-id"));
        return new S3ArtifactStore(s3, presigner, bucket, kmsKeyId,
                !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)));
    }

    /**
     * The endpoint override, required to be {@code https} by default so credentials and artifact bytes are not sent
     * over a plaintext transport a MITM can read or tamper with. A plaintext {@code http} endpoint - a local MinIO or
     * LocalStack container, say - is an explicit opt-out: set
     * {@code jenreg.s3.allow-insecure-endpoint=true}.
     *
     * <p>The rule itself is {@link Endpoints#secure}, shared with the {@code gcs} and {@code azure-blob} backends
     *; what is this backend's own is the pair of config keys it names, and this method is where they are
     * bound to the screen.
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /**
     * Static keys when both {@code jenreg.s3.access-key-id} and
     * {@code jenreg.s3.secret-access-key} are present in the config lookup, otherwise the standard AWS chain
     * (environment, profile, instance role).
     */
    private static AwsCredentialsProvider credentials(UnaryOperator<String> config) {
        String accessKey = config.apply(Features.key("s3.access-key-id"));
        String secretKey = config.apply(Features.key("s3.secret-access-key"));
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
