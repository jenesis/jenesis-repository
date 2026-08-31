package build.jenesis.repository.store.gcs;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import module java.base;

/**
 * The {@code gcs} artifact-store backend over a Google Cloud Storage bucket, through GCS's
 * S3-compatible XML API on the modular AWS SDK v2 - no Google SDK is added to the closure. Selected
 * with {@code jenreg.store=gcs}; configured by {@code jenreg.gcs.bucket} (required) and
 * an HMAC key pair {@code jenreg.gcs.access-key-id} / {@code jenreg.gcs.secret-access-key} (a
 * secret; issued under Cloud Storage &gt; Settings &gt; Interoperability), with an optional
 * {@code jenreg.gcs.endpoint} (default {@code https://storage.googleapis.com}; point it elsewhere
 * for an emulator, but it must be {@code https} unless {@code jenreg.gcs.allow-insecure-endpoint=true}
 * explicitly permits a plaintext one) and {@code jenreg.gcs.region} (the SigV4 scope region, default
 * {@code auto} as GCS documents). When the key pair is absent the standard AWS chain is consulted, which keeps the provider
 * drivable end to end from a test through an injected config lookup. Two SDK defaults are dialled back for GCS, which
 * does not decode aws-chunked request bodies: the flexible-checksum integrity protections become {@code WHEN_REQUIRED}
 * (their trailing checksums ride aws-chunked encoding) and chunked payload signing is disabled outright, so every
 * upload is a plain body with a whole-payload signature. The blob I/O and the generation-based conditional writes live
 * in {@link GcsArtifactStore}.
 */
public final class GcsArtifactStoreProvider implements ArtifactStoreProvider {

    /** The one setting with no ambient fallback, so it is the one this backend declares as required config.
     *  Composed through {@link Features#key} rather than written out, so the namespace has a single definition. */
    public static final String BUCKET_KEY = Features.key("gcs.bucket");

    /**
     * Whether a conditional write may stream its body ({@code true} by default).
     *
     * <p><b>Setting this to {@code false} restores a heap cost, and that is the whole of what it does.</b> A
     * listing is one object written under compare-and-set, and some listings are proportional to the repository.
     * Streaming the write is what keeps such a document out of memory; buffering puts it back, whole, on the path
     * that writes it. Turn this off to work around a storage implementation, never for anything else, and expect
     * the repository's memory ceiling to fall with it.
     */
    public static final String STREAMING_WRITES_KEY = Features.key("gcs.streaming-writes");

    /** The config key a {@code gcs} endpoint is read from - named here so the screen's refusal and the resolution
     *  that applies it cannot drift into naming different keys. */
    public static final String ENDPOINT_KEY = Features.key("gcs.endpoint");

    /** The config key that opts {@link #ENDPOINT_KEY} out of the https-only transport screen. */
    public static final String ALLOW_INSECURE_KEY = Features.key("gcs.allow-insecure-endpoint");

    @Override
    public String name() {
        return "gcs";
    }

    @Override
    public Set<String> requiredConfig() {
        // The HMAC pair may come from the ambient AWS chain, so only the bucket is required configuration.
        return Set.of(BUCKET_KEY);
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String bucket = ArtifactStoreProvider.required(config, BUCKET_KEY, "gcs");
        String region = config.apply(Features.key("gcs.region"));
        if (region == null || region.isBlank()) {
            region = "auto";
        }
        String endpoint = config.apply(ENDPOINT_KEY);
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "https://storage.googleapis.com";
        }
        URI override = secureEndpoint(endpoint, config.apply(ALLOW_INSECURE_KEY));
        S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(credentials(config))
                .endpointOverride(override)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .build();
        // The presigner mints direct-fetch GET URLs (ArtifactStore.presign) over the same GCS S3-compatible endpoint,
        // region, credentials and path-style as the client, so a presigned URL points at the same host the client uses.
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials(config))
                .endpointOverride(override)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception ignored) {
            // A GCS bucket is provisioned out of band (the XML API's create needs a project header the
            // S3 dialect cannot carry), and on any endpoint the bucket may already exist or the
            // credentials may not permit creation; the operations below surface a clear error if the
            // bucket is truly unusable.
        }
        return new GcsArtifactStore(s3, presigner, bucket,
                !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)));
    }

    /**
     * The endpoint (the {@code storage.googleapis.com} default, or an emulator override), required to be {@code https}
     * by default so the HMAC secret and artifact bytes are not sent over a plaintext transport a MITM can read or
     * tamper with. A plaintext {@code http} emulator endpoint is an explicit opt-out: set
     * {@code jenreg.gcs.allow-insecure-endpoint=true}.
     *
     * <p>The rule itself is {@link Endpoints#secure}, shared with the {@code s3} and {@code azure-blob} backends
     *; what is this backend's own is the pair of config keys it names, and this method is where they are
     * bound to the screen.
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /**
     * The static HMAC pair when both {@code jenreg.gcs.access-key-id} and
     * {@code jenreg.gcs.secret-access-key} are present in the config lookup, otherwise the standard
     * AWS chain (environment, profile, instance role).
     */
    private static AwsCredentialsProvider credentials(UnaryOperator<String> config) {
        String accessKey = config.apply(Features.key("gcs.access-key-id"));
        String secretKey = config.apply(Features.key("gcs.secret-access-key"));
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
