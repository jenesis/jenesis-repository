package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.scope.Scopes;

/**
 * A node's running marker, {@code .system/nodes/<id>/running}: written when the node boots, removed when it shuts
 * down cleanly. A node that boots and finds its own marker already there did not shut down cleanly last time -
 * whatever it held in memory when it died (a deferred counter delta, a buffered derived write, an observer's work
 * after a commit) is gone, and that is exactly the case a walk of the store repairs; so the driver that finds the
 * marker {@linkplain Requests#request requests} one, and a healthy node that comes and goes cleanly never does.
 */
public final class RunningMarker {

    private static final Pattern SEGMENT = Pattern.compile("[^A-Za-z0-9_-]");

    private RunningMarker() {
    }

    /** Record that {@code nodeId} is running on {@code root}; {@code true} when it already was according to the
     *  store, which is to say the previous run of this node did not shut down cleanly. */
    public static boolean boot(ArtifactStore root, String nodeId) throws IOException {
        String key = key(nodeId);
        boolean unclean = root.readVersioned(key).isPresent();
        root.write(key, new ByteArrayInputStream(Instant.now().toString().getBytes(StandardCharsets.UTF_8)));
        return unclean;
    }

    /** Whether {@code nodeId} is recorded as running. */
    public static boolean running(ArtifactStore root, String nodeId) throws IOException {
        return root.readVersioned(key(nodeId)).isPresent();
    }

    /** The node is shutting down cleanly: remove its marker. */
    public static void clean(ArtifactStore root, String nodeId) throws IOException {
        root.delete(key(nodeId));
    }

    private static String key(String nodeId) {
        String segment = SEGMENT.matcher(nodeId == null ? "" : nodeId).replaceAll("_");
        if (segment.isBlank()) {
            throw new IllegalArgumentException("a node id is a non-blank name");
        }
        return Scopes.space(Scopes.NODES) + "/" + segment + "/running";
    }
}
