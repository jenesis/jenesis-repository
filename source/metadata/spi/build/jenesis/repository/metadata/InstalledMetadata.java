package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The metadata providers on the module path, held once discovered: every repository's inventory and every rendered
 *  version page asks for the provider. */
final class InstalledMetadata {

    static final Providers.Discovered<MetadataProvider> DISCOVERED =
            new Providers.Discovered<>(() -> ServiceLoader.load(MetadataProvider.class));

    private InstalledMetadata() {
    }
}
