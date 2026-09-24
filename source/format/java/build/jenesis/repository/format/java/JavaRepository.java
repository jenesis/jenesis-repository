package build.jenesis.repository.format.java;

import module java.base;

import build.jenesis.repository.format.CombinedFormat;

/**
 * The {@code java} repository type: Maven and the Jenesis module layout in one repository. A Maven library carrying a
 * module name is served from one blob both ways - at {@code /repository/<name>/maven/<group>/...} and at
 * {@code /repository/<name>/module/<module>/...} - which a repository of either format alone cannot do. The format
 * segment stays in the URL for this reason: without it a Maven group named {@code module} would shadow the module
 * layout.
 */
public final class JavaRepository implements CombinedFormat {

    @Override
    public String name() {
        return "java";
    }

    @Override
    public List<String> formats() {
        return List.of("maven", "jenesis");
    }
}
