package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The reliance providers on the module path, held once discovered: every rendered version page and every closure
 *  pass asks for the reliance. */
final class InstalledReliance {

    static final Providers.Discovered<RelianceProvider> DISCOVERED =
            new Providers.Discovered<>(() -> ServiceLoader.load(RelianceProvider.class));

    private InstalledReliance() {
    }
}
