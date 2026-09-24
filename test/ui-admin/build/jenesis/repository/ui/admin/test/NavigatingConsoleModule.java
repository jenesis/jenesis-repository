package build.jenesis.repository.ui.admin.test;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

/**
 * A console module contributing one link at each access floor and in two groups, so the nav rule has something to
 * filter.
 *
 * <p>It also counts how often it is asked, which is what makes the contract's "never re-discovered on the request
 * path" clause checkable rather than a sentence. The count is of {@link #navEntries()} calls rather than of
 * instantiations, because either one recurring per render is the defect.
 */
public final class NavigatingConsoleModule implements ConsoleModuleProvider {

    private static final AtomicInteger ASKED = new AtomicInteger();

    /** How many times any instance of this module has been asked for its links since {@link #forget()}. */
    public static int asked() {
        return ASKED.get();
    }

    /** Resets the meter, so a test counts only what it provoked. */
    public static void forget() {
        ASKED.set(0);
    }

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
        ASKED.incrementAndGet();
        return List.of(
                new NavEntry("Everyone", "/everyone", NavEntry.Access.USER, NavEntry.Group.OPERATIONS),
                new NavEntry("Admins", "/admins", NavEntry.Access.ADMIN, NavEntry.Group.OPERATIONS),
                new NavEntry("Operators", "/operators", NavEntry.Access.SUPERADMIN, NavEntry.Group.OPERATIONS),
                new NavEntry("Settings", "/module-settings", NavEntry.Access.ADMIN, NavEntry.Group.SETTINGS));
    }
}
