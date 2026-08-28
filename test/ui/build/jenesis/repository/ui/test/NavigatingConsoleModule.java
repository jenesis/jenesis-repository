package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

import module java.base;

/** A console module contributing one link at each access floor and section, so the nav rule has something to filter. */
public final class NavigatingConsoleModule implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "navigating";
    }

    @Override
    public Class<?> configuration() {
        return NavigatingConsoleModule.class;
    }

    @Override
    public List<NavEntry> navEntries() {
        return List.of(
                new NavEntry("Everyone", "/everyone", NavEntry.Access.USER),
                new NavEntry("Admins", "/admins", NavEntry.Access.ADMIN),
                new NavEntry("Operators", "/operators", NavEntry.Access.SUPERADMIN),
                new NavEntry("Settings", "/module-settings", NavEntry.Access.ADMIN, NavEntry.Section.ADMINISTRATION));
    }
}
