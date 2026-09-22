package build.jenesis.repository.upstream.store;

import module java.base;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;

/**
 * Discovers the store-backed credential source; the snapshot refresh window follows the shared
 * {@code settings-refresh-millis} (default thirty seconds).
 */
public final class StoreUpstreamCredentialsProvider implements UpstreamCredentialSourceProvider {

    @Override
    public String name() {
        return "store";
    }

    @Override
    public Optional<UpstreamCredentialSource> create(ArtifactStore root, UnaryOperator<String> config)
            throws IOException {
        String refresh = config.apply("settings-refresh-millis");
        // The master key(s) that envelope-encrypt an upstream credential at rest are the same deploy-time bootstrap
        // infra the SECRET settings use: read as the allowlisted config key "secrets-key"
        // - i.e. the env var JENREG_SECRETS_KEY via Spring relaxed binding - so a store-read attacker
        // recovers only ciphertext. A malformed value fails fast here (at startup), naming the variable (§9).
        return Optional.of(new StoreUpstreamCredentials(root, Duration.ofMillis(
                refresh == null || refresh.isBlank() ? 30_000L : Long.parseLong(refresh.trim())),
                SecretCipher.of(config.apply("secrets-key"))));
    }
}
