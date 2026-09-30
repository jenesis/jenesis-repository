package build.jenesis.repository.search.web;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the browse / search / license-inventory read adapter to the repository server's
 * {@code ServerModuleProvider} discovery, so the server imports {@link SearchWebConfig} - and with it the
 * {@code /api/browse}, {@code /api/search} and {@code /api/licenses} endpoints - without naming the browse or search
 * surface anywhere. Toggled off by {@code jenrepo.search=false} (the {@code Features} convention), it
 * degrades exactly as if the module were absent from the image.
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
