package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Stamp;

/** The declared dependents as the closure pass keeps them ({@link DeclaredRows}), built as of the pass's last full
 *  pass over the repository - which visits every published version - and not before it. */
public final class DeclaredRowsProvider implements DependentsQueryProvider {

    @Override
    public DependentsQuery over(ArtifactStore store) {
        return new DependentsQuery() {
            @Override
            public DeclarationPage declarations(String ecosystem, String dependency, String cursor, int limit)
                    throws IOException {
                return DeclaredRows.page(store, ecosystem, dependency, cursor, limit);
            }

            @Override
            public Optional<Instant> declarationsBuiltAt() throws IOException {
                return new Stamp(store, ClosureTask.RESOLVE + "-full").read();
            }
        };
    }
}
