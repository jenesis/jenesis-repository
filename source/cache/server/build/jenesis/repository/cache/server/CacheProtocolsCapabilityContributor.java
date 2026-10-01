package build.jenesis.repository.cache.server;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * The build tools this node's cache serves, as {@code cacheProtocols} on {@code /api/capabilities}: one entry per
 * installed protocol, in name order, with the {@code endpoint} a client of that tool is pointed at -
 * {@code /build/<tenant>} followed by the protocol's own root, with {@code <tenant>} and {@code <project>} left for
 * the reader to fill in. The console's build-cache pages and {@code jenrepo capabilities} read this one answer.
 *
 * <p>Empty where the cache is switched off ({@code jenrepo.build-cache=false}): its endpoint is then not registered,
 * so a protocol on the module path is served by nothing and listing it would point a build at a 404.
 */
public final class CacheProtocolsCapabilityContributor implements CapabilityContributor {

    /** The key the list is served under. */
    public static final String KEY = "cacheProtocols";

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        String gate = configuration.apply(CacheNode.GATE);
        if (gate != null && !Boolean.parseBoolean(gate.strip())) {
            return Map.of(KEY, List.of());
        }
        List<Map<String, String>> protocols = new ArrayList<>();
        for (CacheProtocol protocol : CacheProtocol.installed()) {
            protocols.add(Map.of("name", protocol.name(),
                    "endpoint", CacheController.ROOT + "<tenant>" + protocol.endpoint()));
        }
        return Map.of(KEY, List.copyOf(protocols));
    }
}
