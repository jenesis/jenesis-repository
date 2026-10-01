package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.observation.SpiCatalog;

/**
 * Where the installed-providers screen gets its catalogue: the module graph's {@link SpiCatalog}, which a deployment
 * that knows more (stored settings) decorates through a {@link SpiCatalog.Decoration}. The default is undecorated:
 * with no stored configuration, every installed implementation is on.
 */
@FunctionalInterface
public interface SpiCatalogSource {

    /** The deployment's plug-in surface, grouped by SPI. */
    List<SpiCatalog> catalog() throws IOException;

    /** The undecorated catalogue of the running module layer. */
    static SpiCatalogSource ofModuleGraph() {
        return SpiCatalog::current;
    }
}
