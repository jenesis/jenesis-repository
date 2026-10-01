package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Provides {@link StoreArtifactWalk} as {@code paged-descent}, the default when {@code jenrepo.walk} names no other.
 *
 * <p>Not named {@code store}: a provider name is a configuration key. {@code jenrepo.<spi>=<name>} selects a singleton
 * and {@code jenrepo.<name>=false} switches a discovered one off, so a walk named {@code store} would put its toggle on
 * {@code jenrepo.store} - the artifact-store selection - where {@code false} would select a backend named
 * {@code false}. {@code paged-descent} names what it does and owns its key.
 *
 * <p>Settings: {@code jenrepo.walk.checkpoint}, items per cursor commit (default 1000); {@code jenrepo.walk.segments},
 * target segments per pass (default 32); {@code jenrepo.walk.ttl}, claim lease seconds (default 900 - a checkpoint
 * stride must renew within it, so scale the two together). A malformed value fails loudly.
 */
public final class StoreWalkProvider implements WalkProvider {

    @Override
    public String name() {
        return "paged-descent";
    }

    @Override
    public Optional<ArtifactWalk> create(UnaryOperator<String> config) {
        StoreArtifactWalk walk = new StoreArtifactWalk(
                integer(config, "walk.checkpoint", 1000),
                integer(config, "walk.segments", 32),
                Duration.ofSeconds(integer(config, "walk.ttl", 900)),
                Clock.systemUTC());
        return Optional.of(walk);
    }

    private static int integer(UnaryOperator<String> config, String key, int fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an integer: " + key + "=" + value, e);
        }
    }
}
