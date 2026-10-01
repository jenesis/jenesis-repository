package build.jenesis.repository.search.lucene;

import module java.base;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * Contributes the {@code search} capability flag to {@code /api/capabilities}: whether a full-text index is installed
 * for a repository to switch on. Without one every repository answers by name.
 */
public final class SearchCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> effectiveConfig) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("search", SearchQueryProvider.installed().isPresent());
        return flags;
    }
}
