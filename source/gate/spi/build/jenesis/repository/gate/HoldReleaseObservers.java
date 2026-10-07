package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The hold-release hooks on the module path, held once discovered: a publish and every path a hold is asked about
 *  consult them. */
final class HoldReleaseObservers {

    static final Providers.Discovered<HoldReleaseObserver> DISCOVERED =
            new Providers.Discovered<>(() -> ServiceLoader.load(HoldReleaseObserver.class));

    private HoldReleaseObservers() {
    }
}
