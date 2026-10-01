package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.spi.ImportEdgeProvider;

/**
 * Claims the repository server's import edge for {@link ImportController}. Both edges answer
 * {@code POST /api/repository/import}, and this one is tenant-scoped, audited and screened against private hosts and
 * plaintext, so its claim suppresses the server's own controller wherever this composition is present.
 */
public final class RoutedImportEdge implements ImportEdgeProvider {

    @Override
    public String name() {
        return "routed-import";
    }
}
