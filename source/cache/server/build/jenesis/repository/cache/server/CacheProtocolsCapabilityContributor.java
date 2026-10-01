package build.jenesis.repository.cache.server;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * The build tools this node's cache serves, as {@code cacheProtocols} on {@code /api/capabilities}: one entry per
 * installed protocol in name order, with the {@code endpoint} a client of that tool is pointed at,
 * {@code /build/<tenant>} and the protocol's root, {@code <tenant>} and {@code <project>} left to fill in. Empty where
 * the cache is switched off, since its endpoint is then not registered.
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
