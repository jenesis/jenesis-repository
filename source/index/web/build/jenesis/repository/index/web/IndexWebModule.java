package build.jenesis.repository.index.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the published-index web adapter to the repository server's {@code ServerModuleProvider} discovery, so the
 * server imports {@link IndexWebConfig} - and with it the index descriptor and chunk endpoints - without naming the
 * index anywhere. With this module off the path the endpoints simply do not exist.
 */
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
