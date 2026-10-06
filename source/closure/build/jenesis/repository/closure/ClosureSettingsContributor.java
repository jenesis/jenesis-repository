package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces whether a repository resolves its published versions' closures, asked by the repository wizard, the
 * cadence of the pass that resolves them, and every how many of its passes one visits every version.
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
                                + "published here is screened: through the copies it relies on. The same pass keeps "
                                + "the versions declaring a dependency on a package, which the Dependents screen "
                                + "lists.",
                        Setting.Kind.BOOLEAN, ClosureTask.DEFAULT, true, Setting.Scope.REPOSITORY).essential(),
                new Setting(ClosureTaskProvider.INTERVAL.key(), "Compliance", "Closure resolution interval",
                        "How often newly published versions are resolved to their closures. A version waits at most "
                                + "this long after its publish; one published before resolution was switched on is "
                                + "resolved on the next full pass.",
                        Setting.Kind.DURATION, ClosureTaskProvider.INTERVAL.fallbackText(), true).advanced(),
                new Setting(ClosureTask.FULL_EVERY, "Compliance", "Closure full pass every",
                        "Every Nth closure pass visits every published version: it resolves one that has no closure, "
                                + "re-derives what each inherits from the copies it relies on, rewrites an index row a "
                                + "lost write left out and removes the rows no closure names any more. The passes "
                                + "between visit what was published since, and "
                                + "what a copy's new finding or hold changed. A full pass costs a few store reads per "
                                + "published version and per package its closure reaches, whether or not anything "
                                + "changed, so this is a cost dial: unset, a week at the hourly cadence.",
                        Setting.Kind.LONG, String.valueOf(ClosureTask.DEFAULT_FULL_EVERY), true).advanced());
    }
}
