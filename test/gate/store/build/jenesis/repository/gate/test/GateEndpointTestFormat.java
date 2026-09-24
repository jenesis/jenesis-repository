package build.jenesis.repository.gate.test;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A test-only blobs-namespace {@link BlobLayout} standing in for the shape is about: a format whose coordinate
 * lives <em>inside</em> the artifact, so it screens under a push ENDPOINT that names no coordinate and serves the
 * artifact somewhere else entirely.
 *
 * <p>It exists to give {@code ComplianceScreen} an installed layout that answers the store-free
 * {@link BlobLayout#servedPaths(String, String)} overload, which is the seam the screen uses to key a screen-time
 * hold's audit row and held-subject record on the artifact rather than on the endpoint. Deliberately generic - the
 * behaviour under test is the gate's, and pinning it to NuGet here would prove only that one format's override.
 */
public final class GateEndpointTestFormat implements RepositoryFormat, BlobLayout {

    /** The ecosystem this layout owns - deliberately its OWN rather than the {@code test} ecosystem every other
     *  {@link GateTestInspector} route stamps, so installing this layout changes the hold-record path for exactly the
     *  one route that exercises it and for no sibling test. */
    static final String ECOSYSTEM = "gatetestendpoint";

    /** The push endpoint - one path every push of this format shares, and the only descriptor a screen sees. */
    static final String ENDPOINT = "/gatetest/endpoint/push";

    @Override
    public String name() {
        return "gatetestendpoint";
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return false;   // never serves; it is on the graph purely as a discovered layout
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        exchange.respond(404);
    }

    @Override
    public List<String> blobRoots() {
        return List.of("gatetest");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        String key = "gatetest/" + coordinate + "/" + version;
        return store.readVersioned(key).isPresent() ? List.of(key) : List.of();
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return Optional.empty();
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        return blobKeys(coordinate, version, store).isEmpty() ? List.of() : servedPaths(coordinate, version);
    }

    /** The served path this coordinate version WOULD occupy - derivable from the coordinate alone, which is the whole
     *  claim this overload makes and the reason the gate may key a hold's records on it before any layout has run. */
    @Override
    public List<String> servedPaths(String coordinate, String version) {
        return BlobLayout.addressable(coordinate, version)
                ? List.of("/gatetest/served/" + coordinate + "/" + version)
                : List.of();
    }
}
