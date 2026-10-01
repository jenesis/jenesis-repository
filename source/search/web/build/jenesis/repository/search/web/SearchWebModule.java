package build.jenesis.repository.search.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the browse, search and licence-inventory adapter to the server's {@code ServerModuleProvider} discovery, so
 * it imports {@link SearchWebConfig} without naming those endpoints. {@code jenrepo.search=false} switches it off,
 * exactly as if the module were absent.
 */
public final class SearchWebModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public Class<?> configuration() {
        return SearchWebConfig.class;
    }
}
