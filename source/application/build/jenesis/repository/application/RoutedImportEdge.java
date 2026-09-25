package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.spi.ImportEdgeProvider;

/**
 * Claims the repository server's import edge for {@link ImportController}. Both edges answer
 * {@code POST /api/repository/import}, so they cannot both be registered, and this composition's is the
 * richer one - tenant-scoped, audited, and screened against the private-host and plaintext refusals - so a claim
 * through this SPI suppresses the server's own controller wherever this composition is present. Every routing names
 * the repository an import lands in, the fixed one included, so there is no deployment on which the server's edge
 * would be the better surface.
 */
public final class RoutedImportEdge implements ImportEdgeProvider {

    @Override
    public String name() {
        return "routed-import";
    }
}
