package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.store.PublicationObserver;

/**
 * How a fixture reaches its hook: through {@code ServiceLoader}, the way the product reaches it, rather than by
 * construction - a fixture holding its own instance would test a hook the deployment never resolves, and for the two
 * hooks whose package is exported to one named module only it could not construct one at all. A fresh load per call,
 * because each simulated process builds its own instance.
 *
 * <p>Two services, because the family really is two services: the after-commit observers and the pre-commit screens
 * ride the single {@code uses PublicationObserver} clause {@code Publication} splits by
 * {@code instanceof PublishInterceptor}, while a hold-release hook is discovered by the gate's own
 * {@code uses HoldReleaseObserver} clause and is not a {@code PublicationObserver} at all.
 */
public final class Discovered {

    private Discovered() {
    }

    /** The discovered {@link PublicationObserver} (or {@code PublishInterceptor}) whose class is {@code providerClass}. */
    public static PublicationObserver hook(String providerClass) {
        return one(ServiceLoader.load(PublicationObserver.class), providerClass, "PublicationObserver");
    }

    /** The discovered {@link HoldReleaseObserver} whose class is {@code providerClass}. */
    public static HoldReleaseObserver release(String providerClass) {
        return one(ServiceLoader.load(HoldReleaseObserver.class), providerClass, "HoldReleaseObserver");
    }

    private static <T> T one(ServiceLoader<T> loader, String providerClass, String service) {
        return loader.stream()
                .map(ServiceLoader.Provider::get)
                .filter(hook -> hook.getClass().getName().equals(providerClass))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(providerClass + " is not discoverable as a " + service
                        + " on this module graph, so its contract cannot run - a hook missing from the one `uses` "
                        + "clause is a hook the product would never see either"));
    }
}
