package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * Contributes the {@code dependents} capability flag to {@code /api/capabilities}: whether the reverse-dependency
 * index resolves. Absent, {@code /api/dependents} answers {@code 501} and the console hides the panel.
 */
public final class DependentsCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> effectiveConfig) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("dependents", DependentsQueryProvider.installed().isPresent());
        return flags;
    }
}
