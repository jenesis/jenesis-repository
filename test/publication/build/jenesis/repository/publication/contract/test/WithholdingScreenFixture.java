package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/** The {@link WithholdingScreen} fixture: the screen whose verdict lives on the read side, so it is the one that
 *  drives the retraction and read-purity clauses and the fail-closed direction of an unanswerable hold probe. */
final class WithholdingScreenFixture implements PublicationHookFixture.Interceptor {

    @Override
    public String hook() {
        return "kit-withholding-screen";
    }

    @Override
    public String providerClass() {
        return WithholdingScreen.class.getName();
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(WithholdingScreen.SPACE);
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        // A retroactive hold never diverts a fresh upload: it lets the publish through and retracts it later, which
        // is the whole reason the read side is re-consulted rather than latched.
        return Set.of(PublishInterceptor.Disposition.ACCEPT);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict) {
        if (verdict != PublishInterceptor.Disposition.ACCEPT) {
            throw new IllegalArgumentException(hook() + " cannot reach " + verdict + "; it holds on the read side");
        }
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) throws IOException {
        Keys.upsert(store, WithholdingScreen.HELD + "/" + Keys.slug(path), "held");
        return true;
    }

    @Override
    public List<String> reads() {
        return List.of(WithholdingScreen.HELD);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, WithholdingScreen.AUDIT);
    }
}
