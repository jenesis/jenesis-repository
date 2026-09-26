package build.jenesis.repository.format.contract.ecosystem.test;

import java.util.concurrent.atomic.AtomicBoolean;
import build.jenesis.repository.format.testkit.ContractHold;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The screen this module's withhold legs hold a {@code publish/}-namespace version with - the twin of the one the
 * free contract module carries, and needed here for the same reason it is needed there.
 *
 * <p>Every fixture in this module used to be a {@code blobs/}-namespace format, retracted by the content-addressed
 * {@code withheld/<hash>} marker that any test can write, so no interceptor was needed and none was declared. A
 * {@code publish/}-namespace format is retracted by the interceptor chain answering {@code withheld} instead, and
 * with no discovered screen there is no way to hold one at all: the first such fixture to land here served its
 * held artifact {@code 200} and left its held revision in the enumeration, with nothing pointing at the cause.
 *
 * <p><b>A twin rather than a shared class, and that is JPMS rather than carelessness.</b> A
 * {@code provides ... with} names an implementation that must live in the providing module, so this cannot be one
 * class in the kit however much one would prefer it. What IS shared is the convention behind it - the key shape,
 * the per-store scoping, the idempotency - which lives once in {@link ContractHold}; this is the two-line
 * delegation that makes the convention take effect on this module's graph.
 *
 * <p>It holds nothing no check asked it to: {@code withheld} answers only for a path a check held, and
 * {@code assess} answers {@code QUARANTINE} only while a check has switched {@link #QUARANTINE_UPLOADS} on - how a
 * check sees what a format's own publish does with an upload the screen holds.
 */
public final class ContractHoldInterceptor implements PublishInterceptor {

    /** While set, every upload a format screens is held for review. A check sets it around one upload and clears it. */
    public static final AtomicBoolean QUARANTINE_UPLOADS = new AtomicBoolean();

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) {
        return QUARANTINE_UPLOADS.get() ? Disposition.QUARANTINE : Disposition.ACCEPT;
    }

    @Override
    public boolean withheld(String path, ArtifactStore store) {
        return ContractHold.is(store, path);
    }
}
