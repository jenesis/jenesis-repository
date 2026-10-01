package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServedAliases;

/**
 * When a module's latest view moves: to a version at least as high as the one it names now, so it follows the highest
 * version published rather than the last - a backport of 1.4.1 published after 2.0.0 leaves it on 2.0.0. The version
 * it names now is read off the origin its alias records, the version folder that origin's file sits in, which is how
 * both a {@code /module/} publish and a Maven one lay a version out. Versions order as Java module versions do; one
 * that does not parse as such orders as text against the other. Two higher versions published at the same moment
 * both pass and the later link wins, which a further publish of either corrects.
 */
final class LatestView {

    private LatestView() {
    }

    /** Whether publishing {@code version} moves the latest view {@code latest} to it. */
    static boolean takes(ArtifactStore store, String latest, String version) throws IOException {
        Optional<String> origin = ServedAliases.origin(store, latest);
        if (origin.isEmpty()) {
            return true;
        }
        String[] segments = origin.get().split("/");
        return segments.length < 2 || order(version, segments[segments.length - 2]) >= 0;
    }

    private static int order(String left, String right) {
        try {
            return ModuleDescriptor.Version.parse(left).compareTo(ModuleDescriptor.Version.parse(right));
        } catch (IllegalArgumentException _) {
            return left.compareTo(right);
        }
    }
}
