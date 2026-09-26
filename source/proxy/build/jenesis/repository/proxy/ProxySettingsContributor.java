package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the upstream fetch's throughput floor, read live by the {@link HttpFetcher} that applies it. */
public final class ProxySettingsContributor implements SettingsContributor {

    /** The setting's key. */
    public static final String FLOOR_KEY = "proxy-throughput-floor";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(FLOOR_KEY, "Proxy", "Upstream throughput floor",
                        "The least an upstream fetch must deliver over each minute spent waiting on it, in bytes, "
                                + "or it is abandoned as the idle timeout abandons one that goes silent. The idle "
                                + "timeout cannot end an upstream answering a byte at a time, which resets it with "
                                + "every byte and holds the fetch for as long as it likes; this does, while a large "
                                + "artifact on a slow but steady link still lands. 0 lifts it. Applies live.",
                        Setting.Kind.LONG, ScreenedHttpClient.THROUGHPUT_FLOOR_TEXT, true).advanced());
    }
}
