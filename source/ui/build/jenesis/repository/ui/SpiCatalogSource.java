package build.jenesis.repository.ui;

import build.jenesis.repository.observation.SpiCatalog;

import module java.base;

/**
 * Where the installed-providers screen gets its catalogue.
 *
 * <p>The screen renders {@link SpiCatalog}, which is the module graph's own answer, and a deployment that knows more
 * than the graph does contributes it here rather than through a second screen. That is the whole reason the seam
 * exists: this console and the admin console had a catalogue page each, the second one richer because it could read
 * stored settings, and keeping two pages in step was left to whoever noticed. One page now, and what a deployment can
 * add to it is a {@link SpiCatalog.Decoration}.
 *
 * <p>The default reports the graph undecorated - every installed implementation is on, because with no stored
 * configuration there is nothing that could have turned one off.
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
