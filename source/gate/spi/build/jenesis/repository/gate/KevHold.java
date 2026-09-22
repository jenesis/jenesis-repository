package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The known-exploited hold kind: a {@link HoldKind} called {@code kev} whose subjects are the CVEs the CISA catalogue
 * names, written by the retroactive {@code kev-enforce} sweep ({@code compliance/scan}) and by the publish-time gate
 * alike - the gate's known-exploited finding carries the kind and the CVE, and the screen writes the record when it
 * quarantines. A hold therefore survives a crash idempotently, an operator's release sticks whichever side held, and
 * only a <em>new, different</em> KEV CVE holds a released version again; a KEV delisting never auto-releases. The
 * record is also the signal that a hold is a known-exploited one, so a sweep gauge counts only its own holds.
 *
 * <p>This class used to recover a publish-time hold's CVEs from the quarantine log's reason text by regular
 * expression, because the gate wrote no record; the finding names its kind now, so the recovery and the duplicated
 * reason-prefix literal it matched on are gone.
 */
public final class KevHold {

    static final HoldKind KIND = HoldKind.of("kev");

    private KevHold() {
    }

    /** {@link HoldKind#hold}: a sweep or the gate is holding a coordinate version for the given known-exploited
     *  CVEs. */
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

    /** {@link HoldKind#cleared}: the continuous re-analysis pass auto-released the hold because every recorded CVE is
     *  off the known-exploited catalogues or its advisory was retracted. */
    public static void cleared(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        KIND.cleared(store, ecosystem, coordinate, version);
    }
}
