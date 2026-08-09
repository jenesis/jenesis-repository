package build.jenesis.repository.importer.contract.test;

import module java.base;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A minimal enumerable format, provided by this module so the {@code index} connector's {@code installed} lookup finds
 * one: {@code enumerate} reads a plain-text {@code index} document at the walk's root - one {@code <path> <url>} pair
 * per line - and streams a coordinate per line.
 *
 * <p>The index connector is the one whose enumeration belongs to a <em>format</em> rather than to itself, so its
 * contract legs need a format on the path. A stand-in is used rather than a shipped format because the shipped
 * enumerable format (OCI) would make every leg a canned registry conversation about manifests and digests, which tests
 * the OCI walk rather than the connector; here the connector's own paging, cursor, laziness and download screening are
 * what varies. The format is scenery: nothing about it is asserted.
 *
 * <p>It serves and proxies nothing, so it can never be reached by a request even in this module's graph.
 */
public final class ContractIndexedFormat implements RepositoryFormat, ProxyFormat {

    /** Distinct from every shipped format name, so this stand-in can never shadow one. */
    static final String NAME = "t203index";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean handles(String path) {
        return false;
    }

    @Override
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        exchange.respond(404);
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, Fetcher fetcher) {
        return false;
    }

    @Override
    public Stream<Coordinate> enumerate(Fetcher fetcher, URI upstream) throws IOException {
        URI index = upstream.resolve("index");
        Fetched fetched = fetcher.fetch(index, Map.of())
                .orElseThrow(() -> new IOException("No response from " + index));
        if (fetched.status() != 200) {
            throw new IOException("Index fetch failed (" + fetched.status() + ") for " + index);
        }
        return new String(fetched.body(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    int space = line.indexOf(' ');
                    return new Coordinate(line.substring(0, space), URI.create(line.substring(space + 1)));
                });
    }
}
