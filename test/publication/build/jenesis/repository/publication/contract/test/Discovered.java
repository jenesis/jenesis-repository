package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.PublicationObserver;

/** How a fixture reaches its hook: through {@code ServiceLoader}, the way {@code Publication} reaches it, rather than
 *  by construction - a fixture holding its own instance would test a hook the deployment never resolves. A fresh load
 *  per call, because each simulated process builds its own instance. */
final class Discovered {

    private Discovered() {
    }

    static PublicationObserver hook(String providerClass) {
        return ServiceLoader.load(PublicationObserver.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(hook -> hook.getClass().getName().equals(providerClass))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(providerClass + " is not discoverable on this module "
                        + "graph, so its contract cannot run - the one `uses PublicationObserver` clause is what "
                        + "carries both roles, and a hook missing from it is a hook Publication would never see"));
    }
}
