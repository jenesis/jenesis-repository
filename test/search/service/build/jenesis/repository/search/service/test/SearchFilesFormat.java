package build.jenesis.repository.search.service.test;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A path-addressed format for the search suite, as a raw repository is: it owns {@code /files/...} and names no
 * coordinate, so what it holds is found by the start of its path. Never serves.
 */
public final class SearchFilesFormat implements RepositoryFormat {

    static final String PREFIX = "/files/";

    @Override
    public String name() {
        return "files";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the test format never serves");
    }
}
