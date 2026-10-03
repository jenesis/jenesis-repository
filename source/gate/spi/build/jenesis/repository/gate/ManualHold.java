package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The hold an operator places by hand: a {@link HoldKind} called {@code manual} whose subjects are who held the
 * version. Any version can be put in quarantine this way - a reviewer's doubt needs no finding behind it - and it is
 * released or discarded like any other hold. No sweep places or re-evaluates it, so its override is never read.
 */
public final class ManualHold {

    static final HoldKind KIND = HoldKind.of("manual");

    /** What a version held by hand is held for, as the review queue says it. */
    public static final String RULE = "Held by an operator";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ManualHold() {
    }

    /** Record that {@code actor} held a coordinate version for review. */
    public static void hold(ArtifactStore store, String ecosystem, String coordinate, String version, String actor)
            throws IOException {
        KIND.hold(store, ecosystem, coordinate, version, Set.of(WHITESPACE.matcher(actor.strip()).replaceAll("_")));
    }

    /** Who held a coordinate version by hand, or empty when no one did. */
    public static Optional<Set<String>> held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.held(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#overridden}: who held a coordinate version a human has since released. */
    public static Set<String> overridden(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return KIND.overridden(store, ecosystem, coordinate, version);
    }

    /** {@link HoldKind#holds}: whether a manual hold stands on the coordinate version {@code path} maps to. */
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
