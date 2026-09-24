package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the recent-logs viewer's dial, so {@code jenreg.logs-buffer} surfaces on the settings screens, the
 * {@code /api/settings} endpoint and the CLI beside the {@link LogRingBuffer} it sizes: the number of most recent
 * entries the ring retains before the oldest is evicted, defaulting to {@link LogRingBuffer#DEFAULT_CAPACITY}.
 * Restart-only ({@code live=false}) - the ring is sized once when the appender is attached at startup. The key is the
 * kebab-case {@code logs-buffer} that relaxed-binds to {@code RepositoryProperties.logsBuffer}.
 */
public final class LogsSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("logs-buffer", "Operations", "Recent-logs buffer size",
                        "How many most-recent log entries the in-memory recent-logs ring retains (the ring behind"
                                + " GET /api/logs and the operator GET /api/admin/logs) before the oldest is evicted.",
                        Setting.Kind.INTEGER, Integer.toString(LogRingBuffer.DEFAULT_CAPACITY), false));
    }
}
