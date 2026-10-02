package build.jenesis.repository.proxy;

import module java.base;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/** Describes the upstream fetch's throughput floor and deadline, read live by the {@link HttpFetcher}. */
public final class ProxySettingsContributor implements SettingsContributor {

    /** The throughput floor's key. */
    public static final String FLOOR_KEY = "proxy-throughput-floor";

    /** The deadline's key. */
    public static final String DEADLINE_KEY = "proxy-fetch-deadline";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(FLOOR_KEY, "Proxy", "Upstream throughput floor",
                        "The least an upstream fetch must deliver over each minute spent waiting on it, in bytes, or "
                                + "it is abandoned as the idle timeout abandons one that goes silent. The idle timeout "
                                + "cannot end an upstream answering a byte at a time, which resets it with every byte "
                                + "and holds the fetch for as long as it likes; this does, while a large artifact on a "
                                + "slow but steady link still lands. Zero lifts it. Applies live.",
                        Setting.Kind.LONG, ScreenedHttpClient.THROUGHPUT_FLOOR_TEXT, true).advanced(),
                new Setting(DEADLINE_KEY, "Proxy", "Upstream fetch deadline",
                        "The longest one upstream fetch may take, from the request to the last byte, before it is "
                                + "abandoned; zero sets no deadline. The throughput floor ends an upstream answering a "
                                + "byte at a time, but only a deadline ends one trickling just above it. Too short a "
                                + "deadline cuts off a legitimate large pull over a slow link, so size it for the "
                                + "largest artifact this proxy serves over the slowest link it should tolerate. "
                                + "Applies live.",
                        Setting.Kind.DURATION, ScreenedHttpClient.DEADLINE_TEXT, true).advanced());
    }
}
