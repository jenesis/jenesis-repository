package build.jenesis.repository.cache.server;

import module java.base;

import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Puts the build cache's gate in the deployment settings catalogue: the cache is no image of its own an operator can
 * leave unrun, so a node needs a documented way to serve artifacts without a cache. Not live: the gate decides at
 * startup whether the cache's controller and chain are registered.
 */
public final class CacheNodeSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(CacheNode.GATE, "Build cache", "Build cache",
                "Whether this deployment serves the remote build cache. On by default: an image carrying the cache "
                        + "meant to serve it. Switched off, the cache's endpoint and the chain that permits it are "
                        + "not registered, so the node serves artifacts only. Applies on restart.",
                Setting.Kind.BOOLEAN, "true", false).gate().standard());
    }
}
