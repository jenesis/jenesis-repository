package build.jenesis.repository.ui.admin.test;

import module java.base;

import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

/**
 * A console module whose nav contribution throws - the shape {@code CapabilityService} used to fan out over with no
 * containment.
 *
 * <p>It is registered for the whole module deliberately. The fan-out runs at <em>construction</em>, so before the
 * containment landed this provider would have failed every suite here at once by taking the Spring context down,
 * rather than costing one nav entry. That is the assertion: the suites beside this one still start.
 */
public final class HostileConsoleModule implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "hostile";
    }

    @Override
    public Class<?> configuration() {
        return HostileConsoleModule.class;
    }

    @Override
    public List<NavEntry> navEntries() {
        throw new IllegalStateException("this console module cannot say where its screens are");
    }
}
