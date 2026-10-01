package build.jenesis.repository.format.java;

import module java.base;

import build.jenesis.repository.format.CombinedFormat;

/**
 * The {@code java} repository type: Maven and the Jenesis module layout in one repository. A Maven library carrying a
 * module name is served from one blob both ways - {@code /repository/<name>/maven/<group>/...} and
 * {@code /repository/<name>/module/<module>/...}. The format segment stays in the URL so a Maven group named
 * {@code module} cannot shadow the module layout.
 *
 * <p><b>Maven drives publication.</b> A {@code java} repository takes Maven publishes only; the {@code /module/} and
 * {@code /artifact/} views are derived from them, and a Jenesis {@code PUT} into them is refused. A repository taking
 * Jenesis publishes is a {@code jenesis} one.
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

    @Override
    public List<String> publishers() {
        return List.of("maven");
    }
}
