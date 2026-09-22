package build.jenesis.repository.staging.store;

import module java.base;
import build.jenesis.repository.staging.StagingProvider;

/**
 * Discovers the artifact-store-backed staging lifecycle: always available when this module is installed - staging
 * needs no configuration beyond the repository's own store.
 */
public final class StoreStagingProvider implements StagingProvider {

    @Override
    public String name() {
        return "store";
    }

    @Override
    public Optional<Factory> create(UnaryOperator<String> config) {
        return Optional.of(StoreStaging::new);
    }
}
