package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.closure.spi.Reliance;
import build.jenesis.repository.closure.spi.RelianceProvider;
import build.jenesis.repository.store.ArtifactStore;

/** {@link Reliance} as the closure pass keeps it: the relied-on index ({@link ReliedOn}) of a repository and of its
 *  tenant's {@value ReliedOn#SPACE} space. */
public final class ReliedOnReliance implements RelianceProvider {

    @Override
    public Reliance over(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                         Function<String, Optional<ArtifactStore>> repositories) {
        return new Reliance() {
            @Override
            public boolean relied(String ecosystem, String coordinate, String version) throws IOException {
                return ReliedOn.relied(holder, tenant, ecosystem, coordinate, version);
            }

            @Override
            public String epoch() throws IOException {
                String own = ReliedOn.epoch(holder).current();
                return tenant.isEmpty() ? own : own + '/' + ReliedOn.epoch(tenant.get().scope(ReliedOn.SPACE)).current();
            }

            @Override
            public Page dependents(String ecosystem, String coordinate, String version, String after, int limit,
                                   Predicate<String> readable) throws IOException {
                return ReliedOn.pageAcross(holder, holderName, tenant, repositories, readable, ecosystem, coordinate,
                        version, after, limit);
            }
        };
    }
}
