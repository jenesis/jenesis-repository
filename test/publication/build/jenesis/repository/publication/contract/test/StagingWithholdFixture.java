package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.ServedOnly;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code StagingWithholdInterceptor}: the screen that holds the whole {@code /staging/} subtree out of serving.
 *
 * <p><b>What the {@code reads()} leg reveals here: nothing, and that is the answer.</b> This screen's verdict is a
 * single {@code path.startsWith("/staging/")} - it does not read the store at all, its {@code withheld} does not even
 * declare {@code throws IOException}, and the {@code store} parameter is ignored. So its declared reads are empty, and
 * the kit turns that into a different obligation rather than a skip: a screen that consults no state must also declare
 * no verdict but {@code ACCEPT}, because a {@code QUARANTINE} or {@code REJECT} has to come from somewhere. This
 * screen answers that honestly - it has no verdict side at all - and the pairing is what would catch it the day it
 * grows a read without declaring one.
 *
 * <p>It also writes nothing, anywhere, on any leg. Its declared namespaces are the staging module's own two prefixes,
 * which its {@code StoreStaging} sibling owns; this hook contributes not one key to them.
 */
final class StagingWithholdFixture implements PublicationHookFixture.Interceptor, ServedOnly {

    @Override
    public String hook() {
        return "staging-withhold";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.staging.store.StagingWithholdInterceptor";
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of("staging-state", "staging-lock");
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        return Set.of(PublishInterceptor.Disposition.ACCEPT);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict) {
        if (verdict != PublishInterceptor.Disposition.ACCEPT) {
            throw new IllegalArgumentException(hook() + " has no verdict side; it can only reach " + verdict
                    + " never");
        }
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) {
        // No durable state can make this screen hold a path: the verdict is the path's own prefix, and the kit
        // publishes under /kit/, never under /staging/. Answering false is the kit's "this screen has no read side
        // to arrange" - which routes the retraction clause through the kit's own probe instead.
        return false;
    }

    @Override
    public List<String> reads() {
        return List.of();
    }

    /** No state at all: this screen's only say is {@code withheld}, computed from the request path, so there is
     *  nothing for it to record on any leg or for any artifact - not just for the one the kit publishes. */
    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) {
        return Map.of();   // the screen writes nothing on any leg
    }

    @Override
    public String whyServedOnly() {
        return "its projection is empty: the screen writes nothing on any leg, so there is no surface a row appended "
                + "under a variant subject could appear on";
    }
}
