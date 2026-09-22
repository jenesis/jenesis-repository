package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The retroactive license enforcement sweep's hold kind ({@code compliance/licenses}'s
 * {@code license-retro-enforce} pass): a {@link HoldKind} called {@code license} whose subjects are space-free
 * reason tokens - the SPDX ids a denied license matched, plus the literal {@code unknown} for an unknown-license
 * hold. A retroactive hold survives a crash idempotently, an operator's release sticks, and only a <em>new</em>
 * reason token (a newly-denied license, or the {@code unknown} bucket newly turned on) holds a released version
 * again; a policy loosening never auto-releases. The record is also the signal that a hold is a license auto-hold
 * rather than a publish-time gate or KEV hold, so a sweep gauge counts only its own holds.
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

    /** {@link HoldKind#holds}: whether the sweep holds a license record for the coordinate version {@code path} maps
     *  to. */
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
