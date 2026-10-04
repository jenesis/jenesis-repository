package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The marker a format stamps once a repository has taken a hosted upload, which its read paths key on: a proxy
 * repository carries none, so its listings miss locally and the upstream's are relayed. Each format chooses where the
 * marker sits, beside the tree it marks so no listing of that tree surfaces it.
 */
public final class HostedMarker {

    private static final byte[] MARKED = "1".getBytes(StandardCharsets.UTF_8);

    private HostedMarker() {
    }

    /** Stamp the marker at {@code key} once, by compare-and-set against absence; a lost race means a peer set it. */
    public static void mark(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, MARKED, null);
        }
    }
}
