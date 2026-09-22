package build.jenesis.repository.ui.admin.test;

import module java.base;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * A contributor reporting every flag the console gates a surface on, standing in for the six feature modules that
 * ship one each. None of those modules is on this test module's path, and that is the point: a gate that derived
 * these flags from the provider registries - as the console did - answers {@code false} for all six here, because
 * the registries are empty. Only a gate that reads the contribution pipeline sees them.
 */
public final class ContributedFlags implements CapabilityContributor {

    /** The flags the console reads out of the merge, each named exactly as its owning module contributes it. */
    public static final List<String> FLAGS = List.of("audit", "gc", "scan", "provenance", "dependents", "search");

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        Map<String, Object> flags = new LinkedHashMap<>();
        FLAGS.forEach(flag -> flags.put(flag, true));
        return flags;
    }
}
