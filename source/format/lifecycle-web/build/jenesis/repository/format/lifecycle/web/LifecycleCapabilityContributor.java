package build.jenesis.repository.format.lifecycle.web;

import module java.base;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * Which installed formats surface which lifecycle marks, as {@code lifecycleMarks} on {@code /api/capabilities}: each
 * format that shows one, with the marks its clients read ({@code deprecated}, {@code yanked}). A mark is refused on a
 * repository whose format does not show it, so a console or a CLI offers only the marks that will be seen.
 */
public final class LifecycleCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        Map<String, Object> formats = new TreeMap<>();
        for (RepositoryFormat format : RepositoryFormat.installed()) {
            List<String> marks = new ArrayList<>();
            if (format.surfacesDeprecation()) {
                marks.add("deprecated");
            }
            if (format.surfacesYank()) {
                marks.add("yanked");
            }
            if (!marks.isEmpty()) {
                formats.put(format.name(), List.copyOf(marks));
            }
        }
        return Map.of("lifecycleMarks", formats);
    }
}
