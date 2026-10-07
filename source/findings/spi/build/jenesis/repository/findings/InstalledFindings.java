package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The findings providers on the module path, held once discovered: a rendered version page and every pass that
 *  records findings ask for the provider. */
final class InstalledFindings {

    static final Providers.Discovered<FindingsProvider> DISCOVERED =
            new Providers.Discovered<>(() -> ServiceLoader.load(FindingsProvider.class));

    private InstalledFindings() {
    }
}
