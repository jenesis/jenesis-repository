package build.jenesis.repository.cache.server;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Puts the build cache's gate in the deployment settings catalogue, for the same reason the console's is there: the
 * cache used to be its own image, so not running it was the dial, and on one node an operator needs a documented
 * way to say a deployment serves artifacts and no cache.
 *
 * <p>Not live: the gate decides whether the cache's controller and its permit-all chain are registered, which is a
 * decision the context makes as it starts.
 */
public final class CacheNodeSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(CacheNode.GATE, "Build cache", "Build cache",
                "Whether this deployment serves the remote build cache. On by default: an image carrying the cache "
                        + "meant to serve it. Switched off, the cache's endpoint and the chain that permits it are "
                        + "not registered, so the node serves artifacts only. Applies on restart.",
                Setting.Kind.BOOLEAN, "true", false).gate());
    }
}
