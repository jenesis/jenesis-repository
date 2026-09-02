package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** The {@link AuditingScreen} fixture: the screen that also overrides the inherited observer leg, so it is the one
 *  that puts a propagating {@code committed} and a contained {@code onPublished} on the same instance. */
class AuditingScreenFixture implements PublicationHookFixture.Interceptor {

    @Override
    public String hook() {
        return "kit-auditing-screen";
    }

    @Override
    public String providerClass() {
        return AuditingScreen.class.getName();
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(AuditingScreen.SPACE);
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        return Set.of(PublishInterceptor.Disposition.ACCEPT);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict) {
        if (verdict != PublishInterceptor.Disposition.ACCEPT) {
            throw new IllegalArgumentException(hook() + " renders no verdict of its own; it audits the chain's");
        }
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) {
        return false;
    }

    @Override
    public List<String> reads() {
        // It votes on nothing, so it has no verdict-bearing read to fault - and no way to degrade to ACCEPT, since
        // ACCEPT is the only answer it ever gives.
        return List.of();
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> projection = new TreeMap<>();
        Keys.rows(store, AuditingScreen.COMMITTED).forEach((key, body) -> projection.put("committed/" + key, body));
        Keys.rows(store, AuditingScreen.OBSERVED).forEach((key, body) -> projection.put("observed/" + key, body));
        return projection;
    }
}
