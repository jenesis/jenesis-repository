package build.jenesis.repository.cache.storage.delegating;

import module java.base;
import build.jenesis.repository.cache.storage.ProjectPolicy;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * A build-cache project's policy ({@link ProjectPolicy}) as three live project settings, set for one project, a
 * tenant's projects or every project, the narrowest winning. Declared beside the one cache storage, which every
 * composition holding projects carries: the build cache applies them on every write and on its reaper's clock, the
 * console when an operator asks for a sweep. The project wizard asks the size cap and the unused-entry lifetime; the
 * sweep order is tuning.
 */
public final class ProjectSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(ProjectPolicy.SIZE, "Build cache project", "Size cap",
                        "How many bytes a project's entries may take together; past it the least recently used "
                                + "entries are evicted after a write, and by the reaper. 0 is no cap.",
                        Setting.Kind.LONG, "0", true, Setting.Scope.PROJECT).essential(),
                new Setting(ProjectPolicy.TTL, "Build cache project", "Unused-entry lifetime",
                        "How long an entry nobody has read or written is kept before the reaper removes it (P30D, "
                                + "30d); none keeps entries for ever.",
                        Setting.Kind.DURATION_OR_NONE, "", true, Setting.Scope.PROJECT).essential(),
                new Setting(ProjectPolicy.LRU, "Build cache project", "Evict least recently used first",
                        "Which entries a size-cap sweep evicts first: the least recently used when on, or the most "
                                + "recently used when switched off.",
                        Setting.Kind.BOOLEAN, "true", true, Setting.Scope.PROJECT).advanced());
    }

    /** A value the policy's own parse refuses: a negative cap, or a zero or negative lifetime. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        try {
            switch (setting.key()) {
                case ProjectPolicy.SIZE -> ProjectPolicy.size(value);
                case ProjectPolicy.TTL -> ProjectPolicy.ttl(value);
                default -> {
                }
            }
            return Optional.empty();
        } catch (IllegalArgumentException refused) {
            return Optional.of(refused.getMessage());
        }
    }
}
