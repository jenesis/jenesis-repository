package build.jenesis.repository.format.lifecycle.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the version-lifecycle web adapter to the repository server's {@code ServerModuleProvider} discovery, so the
 * server imports {@link LifecycleWebConfig} - and with it the {@code /api/lifecycle} deprecate/yank endpoints - without
 * naming the lifecycle surface anywhere. Toggled off by {@code jenreg.lifecycle=false} (the {@code Features}
 * convention), it degrades exactly as if the module were absent from the image.
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
