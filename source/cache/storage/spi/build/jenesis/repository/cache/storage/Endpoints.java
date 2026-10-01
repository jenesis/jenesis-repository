package build.jenesis.repository.cache.storage;

import module java.base;

/**
 * The transport screen an endpoint-configured cache backend applies before building a client: the endpoint must be
 * {@code https} unless the operator opts out for a local emulator. Otherwise one mistyped scheme sends the backend's
 * credentials and every cached byte over plaintext, and nothing fails.
 *
 * <p>The rule and its opt-out spelling are the artifact store's ({@code JENREPO_S3_ALLOW_INSECURE_ENDPOINT},
 * {@code JENREPO_GCS_ALLOW_INSECURE_ENDPOINT}, read with {@link Boolean#parseBoolean}), so one variable governs both
 * stores. It is stated here, beside {@link Names}, because a cache backend must not depend on an artifact-store
 * backend. Only the scheme is screened: reachability and certificates surface as the client's own errors, while a
 * plaintext exchange never fails.
 */
public final class Endpoints {

    private Endpoints() {
    }

    /**
     * The endpoint override, required to be {@code https}. A plaintext endpoint - a local MinIO, LocalStack or Azurite
     * - is an explicit opt-out: {@code allowInsecure} set to {@code true}.
     *
     * @param endpointKey the config key the endpoint was read from, named in the diagnostic
     * @param endpoint the configured endpoint
     * @param allowKey the config key that opts out, named in the diagnostic
     * @param allowInsecure the opt-out's value; anything but {@code true} keeps the screen on
     * @return the endpoint as a {@link URI}, so screening and parsing are one step
     * @throws IllegalStateException when the endpoint is not {@code https} and the opt-out is not set, before any
     *     client is built
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

    /** The relaxed-binding variable spelling of a deployment key ({@code jenrepo.s3.endpoint} is
     *  {@code JENREPO_S3_ENDPOINT}), so a diagnostic names both. Pass a namespaced key. */
    public static String variable(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
