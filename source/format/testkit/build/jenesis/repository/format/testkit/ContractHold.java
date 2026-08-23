package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;

/**
 * How the kit places a hold on a {@code publish/}-namespace path, so the withhold-on-enumeration clause can be
 * asserted before <em>and</em> after the hold rather than only over an already-held version.
 *
 * <p>The two serving namespaces are held by two different mechanisms, and that is the SPI's design rather than an
 * accident: a {@code blobs/}-namespace format (OCI here, most downstream formats) serves straight from
 * {@code blobs/<hash>}, so it is retracted by the content-addressed {@link Withheld} marker; a {@code publish/}-
 * namespace format serves through a pointer, so it is retracted by a {@code PublishInterceptor} answering
 * {@code withheld} - the seam a downstream compliance screen implements and the core ships empty.
 *
 * <p>A fixture therefore declares which mechanism its format is retracted by, and this class owns the pointer-side
 * one: {@link #mark} records the hold as a store object and {@link #is} reads it back. The interceptor itself lives in
 * the driving test module (it must be {@code ServiceLoader}-declared to reach {@code Publication}'s discovered chain,
 * and a testkit module provides no services); it is a two-line delegation to {@link #is}, so the convention - the key
 * shape, the idempotency, the per-store scoping - lives here once and every fixture in either repository shares it.
 *
 * <p>Per-store, not static: the marker is an object in the very store the format serves from, so two contract runs in
 * one JVM cannot leak a hold into each other and a hold survives exactly as long as its store does.
 */
public final class ContractHold {

    /** The store prefix of the convention, {@code contract-hold/<request path>}. Traversal-free and outside every
     *  format's namespace, so it neither collides with a served pointer nor shows up in an enumeration. */
    public static final String ROOT = "contract-hold";

    private ContractHold() {
    }

    /** Hold the request path: from now on the interceptor chain answers {@code withheld} for it, so the path serves
     *  {@code 404} and leaves every enumeration screened through {@code ServableNames}. Idempotent. */
    public static void mark(ArtifactStore store, String requestPath) throws IOException {
        if (store.writeVersioned(key(requestPath), new byte[0], null)) {
            // A hold announces itself the way every product hold does, so a stored listing retracts the path.
            Publication.notifyWithheld(ArtifactDescriptor.at(null, requestPath), store);
        }
    }

    /** Lift the hold. Idempotent. */
    public static void clear(ArtifactStore store, String requestPath) throws IOException {
        store.delete(key(requestPath));
        Publication.notifyWithholdCleared(ArtifactDescriptor.at(null, requestPath), store);
    }

    /** Whether this request path is held - the read a {@code PublishInterceptor#withheld} delegates to. */
    public static boolean is(ArtifactStore store, String requestPath) {
        return requestPath != null && ArtifactStore.traversalFree(requestPath) && store.exists(key(requestPath));
    }

    private static String key(String requestPath) {
        return requestPath.startsWith("/") ? ROOT + requestPath : ROOT + "/" + requestPath;
    }
}
