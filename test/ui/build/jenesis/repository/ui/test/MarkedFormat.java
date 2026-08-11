package build.jenesis.repository.ui.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * A format claiming {@code /maven/} and declaring a mark of its own, discovered exactly like a real one so that the
 * booted console in {@code ConsoleE2ETest} renders a namespace through the <em>discovered</em> path - the browse
 * panel's no-arg constructor resolving {@code FormatMarks.installed()} through {@code ServiceLoader} - and not only
 * through the lookup a unit test hands in. No format module is on this console's graph otherwise, so without this the
 * booted page could only ever show the orphan answer.
 *
 * <p>It serves nothing: {@code handle} is never reached, because the console dispatches no artifact requests.
 */
public final class MarkedFormat implements RepositoryFormat {

    /** Deliberately unmistakable in a rendered page, and deliberately {@code currentColor} like every real mark. */
    static final String MARK = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" "
            + "stroke=\"currentColor\" data-mark=\"console-e2e\"><path d=\"M4 4h16v16H4z\"/></svg>";

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/");
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the console dispatches no artifact requests");
    }

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(IconResource.svg(MARK));
    }
}
