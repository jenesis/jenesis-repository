package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.closure.spi.RetiredClosures;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.EvictionObserver;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What the closure keeps of a version outside its document, taken back as the version is evicted: its declared rows,
 * which this repository holds, at once, and its closure {@linkplain RetiredClosures retired}, so the closure pass here
 * takes back the rows it wrote in the repositories it reached.
 */
public final class ClosureEvictionObserver implements EvictionObserver {

    @Override
    public void evicting(ArtifactStore repository, String ecosystem, String coordinate, String version,
                         MetadataDocument document) throws IOException {
        DeclaredRows.forget(repository, ecosystem, coordinate, version,
                DependencySection.declared(document.section(DependencySection.TAG)).orElse(List.of()));
        RetiredClosures.retire(repository, ecosystem, coordinate, version, document);
    }
}
