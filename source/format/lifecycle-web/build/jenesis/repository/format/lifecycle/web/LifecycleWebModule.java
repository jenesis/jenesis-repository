package build.jenesis.repository.format.lifecycle.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the version-lifecycle web adapter to the server's {@code ServerModuleProvider} discovery, so it imports
 * {@link LifecycleWebConfig} without naming the endpoints. {@code jenrepo.lifecycle=false} switches it off, exactly as
 * if the module were absent.
 */
public final class LifecycleWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "lifecycle";
    }

    @Override
    public Class<?> configuration() {
        return LifecycleWebConfig.class;
    }
}
