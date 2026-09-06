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
        return boot(root, nodeId, process());
    }

    /**
     * {@link #boot(ArtifactStore, String)} on behalf of the named process. A marker names the process that wrote
     * it, and only a marker another process left behind is an unclean shutdown: a node that boots twice inside one
     * process - a test's second Spring context over the same store, a driver restarted in place - finds its own
     * marker standing, and that is the same process still running, not a crash. A crash that came back with the
     * same process id is told apart by the process's start instant, which the id alone would not.
     */
    public static boolean boot(ArtifactStore root, String nodeId, String process) throws IOException {
        String key = key(nodeId);
        Optional<ArtifactStore.Versioned> standing = root.readVersioned(key);
        boolean unclean = standing.isPresent() && !process.equals(processOf(standing.get()));
        root.write(key, new ByteArrayInputStream((process + "\n" + Instant.now()).getBytes(StandardCharsets.UTF_8)));
        return unclean;
    }

    /** This process's identity as a marker records it: its id and its start instant. */
    public static String process() {
        ProcessHandle current = ProcessHandle.current();
        return current.pid() + "@" + current.info().startInstant().map(Instant::toString).orElse("unknown");
    }

    private static String processOf(ArtifactStore.Versioned marker) {
        String body = new String(marker.content(), StandardCharsets.UTF_8);
        int end = body.indexOf('\n');
        return end < 0 ? body.trim() : body.substring(0, end).trim();
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
