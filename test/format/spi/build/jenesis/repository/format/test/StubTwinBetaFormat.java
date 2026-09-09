package build.jenesis.repository.format.test;

import module java.base;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/** One of the two formats {@link SharedEcosystemTest} discovers sharing an ecosystem: it declares {@code Twin}, as
 *  {@link StubTwinAlphaFormat} does under a different name. Registered through {@code provides} rather than constructed,
 *  because what is under test is how a composition behaves when its DISCOVERED set holds two claimants for one
 *  coordinate space - a hand-built list would prove nothing about discovery. */
public class StubTwinBetaFormat implements RepositoryFormat, EcosystemLayout {

    @Override
    public String name() {
        return "twin-beta";
    }

    @Override
    public String ecosystem() {
        return StubTwinAlphaFormat.ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/twin-beta/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the discovery refusal dispatches no request");
    }
}
