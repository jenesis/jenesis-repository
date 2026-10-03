package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the bound on one proxied artifact, read live by {@link ProxyRelay#fill}. */
public final class ProxyArtifactSettingsContributor implements SettingsContributor {

    /** The bound's key. */
    public static final String LIMIT_KEY = "proxy-artifact-limit";

    /** The bound's default, in bytes: a hundred gibibytes, above any artifact a registry serves today - a model's
     *  weights included - and far below what fills a store's disk. */
    public static final String LIMIT_TEXT = "107374182400";

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(LIMIT_KEY, "Proxy", "Largest proxied artifact",
                "The most bytes one artifact fetched from an upstream may have before the fill is abandoned and "
                        + "nothing of it is cached or served, so an upstream answering with an endless body cannot "
                        + "fill the store. Proxied indexes have a bound of their own. Zero lifts it. Applies live.",
                Setting.Kind.LONG, LIMIT_TEXT, true).advanced());
    }
}
