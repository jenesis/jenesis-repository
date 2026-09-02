package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Provides the {@link StoreArtifactWalk} reference implementation as {@code paged-descent} - the default selection
 * when no {@code jenreg.walk} names another.
 *
 * <p><b>The feature name is not {@code store}, though the walk descends the store's own key layout, because a
 * provider name <em>is</em> a configuration key</b>. {@code Features} spends one namespace on two shapes:
 * {@code jenreg.<spi>=<name>} selects a singleton implementation and
 * {@code jenreg.<name>=false} switches a discovered one off. A walk named {@code store} therefore keyed
 * its toggle to {@code jenreg.store} - the artifact store's own selection key, which every deployment
 * already sets ({@code application.properties} binds it to {@code ${JENREG_STORE:filesystem}}). The two never
 * disagreed only because a backend name is not the literal {@code false}: setting the documented off-switch for this
 * walk would have selected an artifact-store backend called {@code false} and refused to boot (&sect;9), so the
 * toggle could not be used at all. {@code paged-descent} names what the walk does - bounded {@code startAfter} paging
 * over an ordered depth-first descent - and owns its own key.
 *
 * <p>Settings, read through the config lookup:
 * {@code jenreg.walk.checkpoint} items per cursor commit (default 1000), {@code jenreg.walk.segments} target
 * segments per pass (default 32), {@code jenreg.walk.ttl} claim lease seconds (default 900 - a checkpoint stride
 * must renew within it, so scale the two together). A malformed value fails loudly rather than walking with a
 * silently-wrong stride.
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
        StoreArtifactWalk.install(walk);                        // the discovered observability reads this one
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
