package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.RepositoryRoutingProvider;
import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.store.Features;

/**
 * Claims the repository server's import edge for {@link ImportController}, the one that names the repository it
 * imports into, when the deployment actually routes repositories.
 *
 * <p>Two routes cannot both be the import surface: the server's own is repository-less
 * ({@code POST /repository/admin/import}) and this composition's names one
 * ({@code POST /repository/<repo>/admin/import}), and the repository-less literal would shadow the scoped route.
 * So a claim through this SPI suppresses the server's controller entirely.
 *
 * <p><strong>The claim used to be made on module presence, and that stopped being a signal.</strong> While this
 * composition shipped only in one edition, "this module is here" meant "repositories are routed here". The
 * composition is shared now, so the claim is made on the thing it actually depends on: a routing provider being
 * installed. Without one there is a single artifact space and no repository to name, so the server's own edge is
 * the right surface and this one declines - which is the same answer as not being installed at all.
 */
public final class RoutedImportEdge implements ImportEdgeProvider {

    @Override
    public String name() {
        return "routed-import";
    }

    @Override
    public boolean applicable() {
        return Features.selection(RepositoryRoutingProvider.SETTING)
                .filter(routing -> !routing.equalsIgnoreCase(RepositoryRoutingProvider.FIXED))
                .isPresent();
    }
}
