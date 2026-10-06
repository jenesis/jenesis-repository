package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The installed {@link RelianceProvider}, discovered once: the module graph fixes it for the JVM's life. */
final class InstalledReliance {

    static final Optional<RelianceProvider> PROVIDER = Providers.singleton("reliance",
            ServiceLoader.load(RelianceProvider.class));

    private InstalledReliance() {
    }
}
