package build.jenesis.repository.cache.storage;

import module java.base;

/**
 * The transport screen every endpoint-configured cache-storage backend applies before it builds a client: an
 * endpoint an operator points a backend at must be {@code https}, unless that operator explicitly opts out for a
 * local emulator. Without it a single mistyped scheme sends the backend's credentials - an S3 SigV4 signature, a GCS
 * HMAC secret, an Azure account key - and every cached byte over a plaintext transport a MITM can read and tamper
 * with, and nothing anywhere says so.
 *
 * <p><strong>This is the artifact store's rule, not a second one (&sect;2).</strong> {@code S3ArtifactStoreProvider} and
 * {@code GcsArtifactStoreProvider} already refuse a non-https endpoint override unless
 * {@code JENREG_S3_ALLOW_INSECURE_ENDPOINT} / {@code JENREG_GCS_ALLOW_INSECURE_ENDPOINT} is {@code true}, and the
 * rule, the opt-out spelling and the {@link Boolean#parseBoolean} reading of it are reproduced here verbatim so one
 * environment variable governs both stores of a deployment that runs them side by side (the combined image runs
 * exactly that). The store's own copy cannot be called: {@code build.jenesis.repository.store.s3} exports its
 * package only to its own test module, and a cache backend must not take a compile-time dependency on an
 * artifact-store backend to reach a five-line predicate. So the <em>mechanism</em> is stated once here, in the SPI
 * module the four backends already share with {@link Names}, rather than a fourth time in each backend - the same
 * choice, and for the same reason, as the shared {@link Names} write-path screens.
 *
 * <p>The screen is deliberately about the <em>scheme</em> only. Whether the endpoint is reachable, whether its
 * certificate validates and whether the bucket exists are the client's business and surface as its own errors; this
 * refuses the one thing no error will ever surface, because a plaintext exchange succeeds.
 */
public final class Endpoints {

    private Endpoints() {
    }

    /**
     * The endpoint override, required to be {@code https}. A plaintext {@code http} endpoint - a local MinIO,
     * LocalStack or Azurite container - is an explicit opt-out: set {@code allowInsecure} (the value of
     * {@code allowKey}) to {@code true}.
     *
     * @param endpointKey   the config key the endpoint was read from, named in the diagnostic so an operator knows
     *                      which of the deployment's endpoints is being refused.
     * @param endpoint      the configured endpoint.
     * @param allowKey      the config key that opts out, named in the diagnostic so the refusal is actionable.
     * @param allowInsecure the opt-out's configured value; anything but {@code true} keeps the screen on.
     * @return the endpoint as a {@link URI}, so a caller screens and parses in one step and cannot use an unscreened
     *         one by accident.
     * @throws IllegalStateException when the endpoint is not {@code https} and the opt-out is not set - at
     *         resolution, before any client is built or any credential is signed with (&sect;9).
     */
    public static URI secure(String endpointKey, String endpoint, String allowKey, String allowInsecure) {
        URI override = URI.create(endpoint);
        String scheme = override.getScheme();
        boolean https = scheme != null && scheme.equalsIgnoreCase("https");
        if (!https && !Boolean.parseBoolean(allowInsecure)) {
            throw new IllegalStateException(endpointKey + " (" + variable(endpointKey)
                    + ") must be an https:// endpoint (got '" + endpoint + "'), or the backend's credentials and every"
                    + " cached byte travel in clear; set " + allowKey + " (" + variable(allowKey)
                    + ")=true to allow a plaintext endpoint, e.g. a local MinIO, LocalStack or Azurite container.");
        }
        return override;
    }

    /** The relaxed-binding environment-variable spelling of a DEPLOYMENT key
     *  ({@code jenreg.s3.endpoint} is {@code JENREG_S3_ENDPOINT}), so a diagnostic names the
     *  key in both the spellings an operator may have used. Pass a namespaced key - a bare setting name would derive
     *  a variable no deployment sets. */
    public static String variable(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
