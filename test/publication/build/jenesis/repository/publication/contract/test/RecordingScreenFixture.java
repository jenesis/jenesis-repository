package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/** The {@link RecordingScreen} fixture: the screen that can reach every disposition from durable state, so it is the
 *  one that drives the verdict legs and the "does not degrade to ACCEPT when its own store is down" clause. */
final class RecordingScreenFixture implements PublicationHookFixture.Interceptor {

    @Override
    public String hook() {
        return "kit-recording-screen";
    }

    @Override
    public String providerClass() {
        return RecordingScreen.class.getName();
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(RecordingScreen.SPACE);
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        return EnumSet.allOf(PublishInterceptor.Disposition.class);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict)
            throws IOException {
        Keys.upsert(store, RecordingScreen.VERDICTS + "/" + Keys.slug(artifact.path()), verdict.name());
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) {
        return false;    // this screen votes at publish time only; the read side is WithholdingScreen's
    }

    @Override
    public List<String> reads() {
        return List.of(RecordingScreen.VERDICTS);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> projection = new TreeMap<>();
        Keys.rows(store, RecordingScreen.SEEN).forEach((key, body) -> projection.put("seen/" + key, body));
        Keys.rows(store, RecordingScreen.COMMITTED).forEach((key, body) -> projection.put("committed/" + key, body));
        return projection;
    }
}
