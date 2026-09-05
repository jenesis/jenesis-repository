package build.jenesis.repository.store.gcs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Bucket;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * The {@code gcs} artifact-store backend over a Google Cloud Storage bucket, through GCS's JSON API on Google's
 * API client. Selected with {@code jenreg.store=gcs}; configured by {@code jenreg.gcs.bucket} (required), an
 * optional {@code jenreg.gcs.credentials} (a service-account key file; absent, the Application Default Credentials
 * are used - {@code GOOGLE_APPLICATION_CREDENTIALS}, a {@code gcloud} login, or the metadata server that makes a
 * deployment on GCE, GKE or Cloud Run keyless under Workload Identity; the literal {@value #ANONYMOUS} sends no
 * credential at all, which only an emulator accepts), an optional {@code jenreg.gcs.endpoint} (default
 * {@code https://storage.googleapis.com}; point it at an emulator, but it must be {@code https} unless
 * {@code jenreg.gcs.allow-insecure-endpoint=true} explicitly permits a plaintext one), and an optional
 * {@code jenreg.gcs.project}, which is what creating the bucket on first use needs - a deployment provisions its
 * bucket out of band, an emulator does not. Every request rides the JDK's own HTTP transport with a fresh
 * exponential backoff on the responses Google documents as retryable.
 */
public final class GcsArtifactStoreProvider implements ArtifactStoreProvider {

    /** The one setting with no ambient fallback, so it is the one this backend declares as required config.
     *  Composed through {@link Features#key} rather than written out, so the namespace has a single definition. */
    public static final String BUCKET_KEY = Features.key("gcs.bucket");

    /** A service-account key file, or {@value #ANONYMOUS}; absent, the Application Default Credentials. */
    public static final String CREDENTIALS_KEY = Features.key("gcs.credentials");

    /** The value of {@link #CREDENTIALS_KEY} that sends no credential at all - an emulator's setting, never a
     *  deployment's, because Cloud Storage refuses an unauthenticated request. */
    public static final String ANONYMOUS = "none";

    /** The project the bucket is created in on first use when it does not exist yet; unset, the bucket must exist. */
    public static final String PROJECT_KEY = Features.key("gcs.project");

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

    static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";

    /** The one OAuth scope object reads and writes need. */
    private static final String SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";

    @Override
    public String name() {
        return "gcs";
    }

    @Override
    public Set<String> requiredConfig() {
        // The credential may come from the ambient Application Default Credentials, so only the bucket is required.
        return Set.of(BUCKET_KEY);
    }

    @Override
    public ArtifactStore create(UnaryOperator<String> config) {
        String bucket = ArtifactStoreProvider.required(config, BUCKET_KEY, "gcs");
        String endpoint = config.apply(ENDPOINT_KEY);
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = DEFAULT_ENDPOINT;
        }
        URI root = secureEndpoint(endpoint, config.apply(ALLOW_INSECURE_KEY));
        GoogleCredentials credentials = credentials(config.apply(CREDENTIALS_KEY));
        String rootUrl = root.toString().endsWith("/") ? root.toString() : root + "/";
        Storage storage = new Storage.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), new Requests(credentials))
                .setApplicationName("jenesis-repository")
                .setRootUrl(rootUrl)
                .build();
        String project = config.apply(PROJECT_KEY);
        if (project != null && !project.isBlank()) {
            ensureBucket(storage, project, bucket);
        }
        GcsSignedUrl signer = credentials instanceof ServiceAccountSigner able ? new GcsSignedUrl(able, root) : null;
        return new GcsArtifactStore(storage, bucket, !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)), signer);
    }

    /**
     * The endpoint (the {@code storage.googleapis.com} default, or an emulator override), required to be {@code https}
     * by default so the bearer token and artifact bytes are not sent over a plaintext transport a MITM can read or
     * tamper with. A plaintext {@code http} emulator endpoint is an explicit opt-out: set
     * {@code jenreg.gcs.allow-insecure-endpoint=true}.
     *
     * <p>The rule itself is {@link Endpoints#secure}, shared with the {@code s3} and {@code azure-blob} backends;
     * what is this backend's own is the pair of config keys it names, and this method is where they are bound to
     * the screen.
     */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /** The credential the setting names: a key file, the Application Default Credentials, or none for an emulator;
     *  scoped to object reads and writes where the credential type takes a scope. */
    private static GoogleCredentials credentials(String setting) {
        if (ANONYMOUS.equalsIgnoreCase(setting)) {
            return null;
        }
        GoogleCredentials credentials;
        try {
            if (setting == null || setting.isBlank()) {
                credentials = GoogleCredentials.getApplicationDefault();
            } else {
                try (InputStream in = Files.newInputStream(Path.of(setting))) {
                    credentials = GoogleCredentials.fromStream(in);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The gcs store has no usable credential: set " + CREDENTIALS_KEY
                    + " to a service-account key file, provide Application Default Credentials"
                    + " (GOOGLE_APPLICATION_CREDENTIALS, a gcloud login or the metadata server), or set it to '"
                    + ANONYMOUS + "' for an emulator", e);
        }
        return credentials.createScopedRequired() ? credentials.createScoped(List.of(SCOPE)) : credentials;
    }

    /** Create the bucket when it does not exist; one that does, or one the credential may not create, is left as
     *  it is - the operations that follow say clearly when a bucket is truly unusable. */
    private static void ensureBucket(Storage storage, String project, String bucket) {
        try {
            storage.buckets().insert(project, new Bucket().setName(bucket)).execute();
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() != 409 && e.getStatusCode() != 403) {
                throw new IllegalStateException("Could not create bucket " + bucket + " in project " + project, e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not create bucket " + bucket + " in project " + project, e);
        }
    }

    /**
     * What every request carries: the credential's bearer token, refreshed by the auth adapter on a 401; timeouts
     * sized for a large body; and Google's documented retry - a fresh exponential backoff per request on a 408, a
     * 429 or a 5xx, and on a dropped connection. A conditional write is idempotent, so re-sending it is safe, and an
     * unconditional one re-sends the same spooled bytes; the store never hands the client a body it cannot re-read.
     */
    static final class Requests implements HttpRequestInitializer {

        private static final int RETRIES = 6;

        private final HttpCredentialsAdapter credentials;

        Requests(GoogleCredentials credentials) {
            this.credentials = credentials == null ? null : new HttpCredentialsAdapter(credentials);
        }

        @Override
        public void initialize(HttpRequest request) throws IOException {
            if (credentials != null) {
                credentials.initialize(request);
            }
            HttpUnsuccessfulResponseHandler refresh = request.getUnsuccessfulResponseHandler();
            HttpBackOffUnsuccessfulResponseHandler backoff = new HttpBackOffUnsuccessfulResponseHandler(backOff())
                    .setBackOffRequired(response -> {
                        int status = response.getStatusCode();
                        return status == 408 || status == 429 || status / 100 == 5;
                    });
            request.setUnsuccessfulResponseHandler((sent, response, supportsRetry) ->
                    (refresh != null && refresh.handleResponse(sent, response, supportsRetry))
                            || backoff.handleResponse(sent, response, supportsRetry));
            request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(backOff()));
            request.setNumberOfRetries(RETRIES);
            request.setConnectTimeout(20_000);
            request.setReadTimeout(120_000);
        }

        private static ExponentialBackOff backOff() {
            return new ExponentialBackOff.Builder()
                    .setInitialIntervalMillis(250)
                    .setMaxIntervalMillis(8_000)
                    .setMaxElapsedTimeMillis(60_000)
                    .build();
        }
    }
}
