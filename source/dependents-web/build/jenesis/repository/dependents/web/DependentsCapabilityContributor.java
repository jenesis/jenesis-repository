package build.jenesis.repository.dependents.web;

import module java.base;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.store.Features;

/**
 * Contributes the {@code dependents} capability flag to {@code /api/capabilities}: whether this node serves what depends
 * on a version - the API and the console's Dependents screen - which {@code jenrepo.dependents=false} switches off.
 * Whether the declared index behind its declared half is installed is the answer's own to say.
 */
public final class DependentsCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        return Map.of("dependents", Features.enabled(configuration, "dependents"));
    }
}
