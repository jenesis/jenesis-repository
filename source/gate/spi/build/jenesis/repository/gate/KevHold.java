package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The known-exploited hold kind: a {@link HoldKind} called {@code kev} whose subjects are the catalogued CVEs, written
 * by the {@code kev-enforce} sweep and by the publish-time gate, whose finding carries the kind and the CVE. An
 * operator's release sticks whichever side held, and only a new, different KEV CVE holds a released version again.
 */
public final class KevHold {

    static final HoldKind KIND = HoldKind.of("kev");

    private KevHold() {
    }

    /** {@link HoldKind#hold} for the given known-exploited CVEs. */
    public static void hold(ArtifactStore store, String ecosystem, String coordinate, String version,
                            Collection<String> cves) throws IOException {
        KIND.hold(store, ecosystem, coordinate, version, cves);
    }

    /** {@link HoldKind#held}: the known-exploited CVEs recorded for a currently-held coordinate version. */
    public static Optional<Set<String>> held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.held(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#overridden}: the known-exploited CVEs a human has released for a coordinate version. */
    public static Set<String> overridden(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.overridden(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#holds}: whether a KEV record stands on the coordinate version {@code path} maps to. */
    public static boolean holds(ArtifactStore store, String path) throws IOException {
        return KIND.holds(store, path);
    }

    /** {@link HoldKind#onReleased}. */
    public static void onReleased(ArtifactStore store, String path) throws IOException {
        KIND.onReleased(store, path);
    }

    /** {@link HoldKind#onDiscarded}. */
    public static void onDiscarded(ArtifactStore store, String path) throws IOException {
        KIND.onDiscarded(store, path);
    }

    /** {@link HoldKind#cleared}, when every recorded CVE left the catalogues or its advisory was retracted. */
    public static void cleared(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        KIND.cleared(store, ecosystem, coordinate, version);
    }
}
