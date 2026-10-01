package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@code license-retro-enforce} sweep's hold kind: a {@link HoldKind} called {@code license} whose subjects are the
 * SPDX ids a denied licence matched, plus {@code unknown} for an unknown-licence hold. Only a new reason token holds a
 * released version again, and a policy loosening never auto-releases.
 */
public final class LicenseHold {

    static final HoldKind KIND = HoldKind.of("license");

    private LicenseHold() {
    }

    /** {@link HoldKind#hold}: the sweep is holding a coordinate version for the given reason tokens. */
    public static void hold(ArtifactStore store, String ecosystem, String coordinate, String version,
                            Collection<String> reasons) throws IOException {
        KIND.hold(store, ecosystem, coordinate, version, reasons);
    }

    /** {@link HoldKind#held}: the reason tokens recorded for a currently-held coordinate version. */
    public static Optional<Set<String>> held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.held(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#overridden}: the reason tokens a human has released for a coordinate version. */
    public static Set<String> overridden(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.overridden(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#holds}. */
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
}
