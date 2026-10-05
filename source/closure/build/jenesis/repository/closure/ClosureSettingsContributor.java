package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces whether a repository resolves its published versions' closures, asked by the repository wizard, and the
 * cadence of the pass that resolves them.
 */
public final class ClosureSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(ClosureTask.SETTING, "Compliance", "Resolve dependency closures",
                        "Resolve each version published here to the transitive closure a build through this "
                                + "repository resolves: its dependencies, theirs, and so on, each the newest held "
                                + "version its requirement admits. It reads only what the repository holds and fetches "
                                + "nothing, so it states what builds through the repository already fetched, and a "
                                + "dependency it does not hold is listed as unresolved. The closure is how a version "
                                + "published here is screened: through the copies it relies on.",
                        Setting.Kind.BOOLEAN, ClosureTask.DEFAULT, true, Setting.Scope.REPOSITORY).essential(),
                new Setting(ClosureTaskProvider.INTERVAL.key(), "Compliance", "Closure resolution interval",
                        "How often newly published versions are resolved to their closures. A version waits at most "
                                + "this long after its publish; one published before resolution was switched on is "
                                + "resolved on the next full pass.",
                        Setting.Kind.DURATION, ClosureTaskProvider.INTERVAL.fallbackText(), true).advanced());
    }
}
