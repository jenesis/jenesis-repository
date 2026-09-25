package build.jenesis.repository.format.lifecycle.web;

import module java.base;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * Which installed formats surface a lifecycle mark, as {@code lifecycleMarks} on {@code /api/capabilities}: a mark is
 * refused on a repository of any other format, so a console or a CLI offers the action only where it will be seen.
 */
public final class LifecycleCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        return Map.of("lifecycleMarks", RepositoryFormat.installed().stream()
                .filter(RepositoryFormat::surfacesLifecycleMarks)
                .map(RepositoryFormat::name)
                .sorted()
                .toList());
    }
}
