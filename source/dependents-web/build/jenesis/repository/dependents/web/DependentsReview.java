package build.jenesis.repository.dependents.web;

import java.io.IOException;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.store.TenantScope;
import io.micrometer.observation.ObservationRegistry;

/**
 * What depends on a version, for the console, read through the tenant a call runs in - the same {@link Dependents}
 * answer the API gives. The console shows the whole tenant, so every repository's resolved dependents are read.
 *
 * <p>Read-only, so it takes the three-argument constructor: nothing here records an audit event.
 */
public class DependentsReview extends TenantScope {

    public DependentsReview(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        super(repositoryStore, current, observations);
    }

    /** What depends on {@code version} of {@code coordinate} of {@code ecosystem} in {@code repository}, each half
     *  one page resumed after its own cursor. */
    public Dependents.View dependents(String repository, String ecosystem, String coordinate, String version,
                                      String after, String declaredAfter) throws IOException {
        scope(repository);
        return Dependents.read(root.scope(tenant()), repository, ecosystem, coordinate, version, after, declaredAfter,
                Dependents.MAX_PAGE, _ -> true);
    }
}
