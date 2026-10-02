package build.jenesis.repository.index.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/** Contributes {@link IndexWebConfig}, and with it the index descriptor and chunk endpoints, to the server. */
public final class IndexWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "index";
    }

    @Override
    public Class<?> configuration() {
        return IndexWebConfig.class;
    }
}
