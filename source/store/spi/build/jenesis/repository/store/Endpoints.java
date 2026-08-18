package build.jenesis.repository.store;

import module java.base;

/**
 * The transport screen every endpoint-configured artifact-store backend applies before it builds a client: an
 * endpoint an operator points a backend at must be {@code https}, unless that operator explicitly opts out for a
 * local emulator. Without it a single mistyped scheme sends the backend's credentials - an S3 SigV4 signature, a GCS
 * HMAC secret, an Azure account key - and every artifact byte over a plaintext transport a MITM can read and tamper
 * with, and nothing anywhere says so, because a plaintext exchange succeeds.
 *
 * <p><strong>One mechanism, three key spellings (&sect;2).</strong> The rule was written out three times - once in
 * {@code S3ArtifactStoreProvider}, once in {@code GcsArtifactStoreProvider}, once in
 * {@code AzureArtifactStoreProvider} - each parsing the {@link URI}, testing the scheme, reading the opt-out through
 * {@link Boolean#parseBoolean} and composing its own refusal message, so the three refusals read differently for the
 * same defect and a fourth backend would have arrived with a fourth wording (D-023). What genuinely differs between
 * the backends is only <em>which config keys</em> carry the endpoint and the opt-out, so that is what a caller passes
 * and everything else lives here. A backend module cannot reach a sibling backend's copy - each exports its package
 * only to its own test module, and a store backend must not depend on another store backend for a five-line predicate
 * - so the home is the SPI module all three already require. The downstream edition's cache backends share the same
 * mechanism, under the same rule and the same opt-out spellings, through their own SPI module's {@code Endpoints}.
 *
 * <p>The screen is deliberately about the <em>scheme</em> only. Whether the endpoint is reachable, whether its
 * certificate validates and whether the bucket or container exists are the client's business and surface as its own
 * errors; this refuses the one thing no error will ever surface.
 */
public final class Endpoints {

    private Endpoints() {
    }

    /**
     * The endpoint an operator configured, required to be {@code https}. A plaintext {@code http} endpoint - a local
     * MinIO, LocalStack, storage-emulator or Azurite container - is an explicit opt-out: set the config key named by
     * {@code allowKey} to {@code true}.
     *
     * @param endpointKey   the config key the endpoint was read from - or, for a backend whose scheme rides inside a
     *                      wider value, the key that value came from - named in the diagnostic so an operator knows
     *                      which of the deployment's settings is being refused.
     * @param endpoint      the configured endpoint, or {@code null} when the configuration declares none. A
     *                      {@code null} answers {@code null}: a backend that could not resolve an endpoint at all has
     *                      no scheme to judge, and its client's own diagnostic is the better one - this screen never
     *                      invents a second.
     * @param allowKey      the config key that opts out, named in the diagnostic so the refusal is actionable.
     * @param allowInsecure the opt-out's configured value; anything but {@code true} keeps the screen on.
     * @return the endpoint as a {@link URI}, so a caller screens and parses in one step and cannot use an unscreened
     *         one by accident, or {@code null} when {@code endpoint} was {@code null}.
     * @throws IllegalStateException when the endpoint is not {@code https} and the opt-out is not set - at
     *         resolution, before any client is built or any credential is signed with (&sect;9).
     */
    public static URI secure(String endpointKey, String endpoint, String allowKey, String allowInsecure) {
        if (endpoint == null) {
            return null;
        }
        URI override = URI.create(endpoint);
        String scheme = override.getScheme();
        boolean https = scheme != null && scheme.equalsIgnoreCase("https");
        if (!https && !Boolean.parseBoolean(allowInsecure)) {
            throw new IllegalStateException(endpointKey + " must resolve to an https:// endpoint (got '" + endpoint
                    + "'), or the backend's credentials and every artifact byte travel in clear; set " + allowKey
                    + "=true to allow a plaintext endpoint, e.g. a local MinIO, LocalStack, storage-emulator or "
                    + "Azurite container.");
        }
        return override;
    }
}
