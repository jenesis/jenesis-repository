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
import build.jenesis.repository.store.ConditionalWrites;
import build.jenesis.repository.store.Endpoints;
import build.jenesis.repository.store.Features;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.model.Bucket;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * The {@code gcs} artifact-store backend over a Cloud Storage bucket through the JSON API, selected with
 * {@code jenrepo.store=gcs} and configured by:
 * <ul>
 *   <li>{@code jenrepo.gcs.bucket} (required);</li>
 *   <li>{@code jenrepo.gcs.credentials} - a service-account key file; absent, Application Default Credentials
 *       ({@code GOOGLE_APPLICATION_CREDENTIALS}, a {@code gcloud} login, or the metadata server that makes GCE, GKE or
 *       Cloud Run keyless under Workload Identity); {@value #ANONYMOUS} sends none, which only an emulator
 *       accepts;</li>
 *   <li>{@code jenrepo.gcs.endpoint} - default {@code https://storage.googleapis.com}, {@code https} unless
 *       {@code jenrepo.gcs.allow-insecure-endpoint=true};</li>
 *   <li>{@code jenrepo.gcs.project} - what creating a missing bucket on first use needs; a deployment provisions its
 *       bucket out of band.</li>
 * </ul>
 * Every request rides the JDK's HTTP transport with a fresh exponential backoff on Google's documented retryable
 * responses.
 */
public final class GcsArtifactStoreProvider implements ArtifactStoreProvider {

    /** The one setting with no ambient fallback, so the one declared required; composed through
     *  {@link Features#key}. */
    public static final String BUCKET_KEY = Features.key("gcs.bucket");

    /** A service-account key file, or {@value #ANONYMOUS}; absent, the Application Default Credentials. */
    public static final String CREDENTIALS_KEY = Features.key("gcs.credentials");

    /** The {@link #CREDENTIALS_KEY} value that sends no credential - an emulator's setting, since Cloud Storage refuses
     *  an unauthenticated request. */
    public static final String ANONYMOUS = "none";

    /** The project a missing bucket is created in on first use; unset, the bucket must exist. */
    public static final String PROJECT_KEY = Features.key("gcs.project");

    /** Whether a conditional write may stream its body ({@code true} by default). Some listings, written under
     *  compare-and-set, are proportional to the repository; buffering puts such a document whole in memory on the write
     *  path. Turn it off only to work around a storage implementation, expecting the memory ceiling to fall with it. */
    public static final String STREAMING_WRITES_KEY = Features.key("gcs.streaming-writes");

    /** The config key a {@code gcs} endpoint is read from, named once for the screen's refusal and the resolution. */
    public static final String ENDPOINT_KEY = Features.key("gcs.endpoint");

    /** The config key that switches off the boot-time conditional-write probe ({@code false}); on by default. The probe
     *  refuses to start over an endpoint that ignores a write precondition, under which two nodes would silently lose
     *  each other's writes; off, the node warns on every start. */
    public static final String PROBE_KEY = Features.key("gcs.conditional-write-probe");


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
    public Set<String> config() {
        return Set.of(BUCKET_KEY, CREDENTIALS_KEY, PROJECT_KEY, STREAMING_WRITES_KEY, ENDPOINT_KEY, PROBE_KEY,
                ALLOW_INSECURE_KEY);
    }

    @Override
    public Set<String> requiredConfig() {
        // The credential may be ambient, so only the bucket is required.
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
        Storage storage = new Storage.Builder(new GcsTransport(), GsonFactory.getDefaultInstance(), new Requests(credentials))
                .setApplicationName("jenesis-repository")
                .setRootUrl(rootUrl)
                .build();
        String project = config.apply(PROJECT_KEY);
        if (project != null && !project.isBlank()) {
            ensureBucket(storage, project, bucket);
        }
        GcsSignedUrl signer = credentials instanceof ServiceAccountSigner able ? new GcsSignedUrl(able, root) : null;
        GcsArtifactStore store = new GcsArtifactStore(storage, bucket,
                !"false".equalsIgnoreCase(config.apply(STREAMING_WRITES_KEY)), signer);
        // The question every compare-and-set rests on, asked of every object-store endpoint at boot.
        try {
            ConditionalWrites.probe(store, "the GCS endpoint " + endpoint + " for bucket " + bucket, config.apply(PROBE_KEY));
        } catch (IOException failure) {
            throw new IllegalStateException("the gcs store could not be probed for conditional writes at boot - is bucket "
                    + bucket + " writable with these credentials? " + failure.getMessage(), failure);
        }
        return store;
    }

    /** The endpoint (the default, or an emulator), {@code https} unless
     *  {@code jenrepo.gcs.allow-insecure-endpoint=true}, so the bearer token and artifact bytes never cross a plaintext
     *  transport. The rule is {@link Endpoints#secure}, shared with the {@code s3} and {@code azure-blob} backends;
     *  this binds this backend's keys to it. */
    public static URI secureEndpoint(String endpoint, String allowInsecure) {
        return Endpoints.secure(ENDPOINT_KEY, endpoint, ALLOW_INSECURE_KEY, allowInsecure);
    }

    /** The credential the setting names - a key file, Application Default Credentials, or none for an emulator - scoped
     *  to object reads and writes where the type takes a scope. */
    private static GoogleCredentials credentials(String setting) {
        if (ANONYMOUS.equalsIgnoreCase(setting)) {
            return null;
        }
        GoogleCredentials credentials;
        try {
            if (setting == null || setting.isBlank()) {
                // The token exchange also goes over the product's HTTP client.
                credentials = GoogleCredentials.getApplicationDefault(GcsTransport::new);
            } else {
                try (InputStream in = Files.newInputStream(Path.of(setting))) {
                    credentials = GoogleCredentials.fromStream(in, GcsTransport::new);
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

    /** Create the bucket when it does not exist; an existing one, or one the credential may not create, is left as it
     *  is, and the operations that follow report a truly unusable bucket. */
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

    /** What every request carries: the bearer token, refreshed on a 401; timeouts sized for a large body; and Google's
     *  documented retry - a fresh exponential backoff on a 408, a 429, a 5xx or a dropped connection. Re-sending is
     *  safe: a conditional write is idempotent and the body is always re-readable. */
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
