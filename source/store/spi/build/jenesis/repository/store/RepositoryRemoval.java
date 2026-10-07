package build.jenesis.repository.store;

import module java.base;
import build.jenesis.repository.scope.Scopes;

/**
 * Deleting a repository and everything it holds - the one removal the console, the API and the command line all make.
 *
 * <p><b>In two halves, because the second is as long as the repository is large.</b> {@link #begin} is the part a
 * request waits for: it writes a {@value #MARKER} marker into the repository's scope, create-if-absent, and then
 * deletes the repository's {@link RepositoryDocument}, so from that moment the repository answers no request, takes
 * no upload and cannot be created again under the same name. {@link #purge} is the rest, run off the request path:
 * a paged delete of every object in the scope, the marker last. An operator's screen therefore never waits on a
 * repository's size - it shows the repository as being deleted until the marker is gone.
 *
 * <p><b>Resumable, because a node can stop half way.</b> The marker outlives everything else in the scope, so a
 * removal interrupted by a crash leaves a repository that still reads as being deleted, and beginning it again -
 * the same Delete, from any surface - finds the marker, and purges what is left. Nothing resumes it by itself: a
 * deletion happens because somebody asked for it, never because something was found missing.
 *
 * <p><b>Readable while it runs.</b> {@link #status} reads the marker alone, so any surface can say whether a deletion
 * is running, stopped part way (the marker carries the reason, written when the purge gives up), or over - which
 * is the absence of both the marker and the repository's document, indistinguishable from a name that never held a
 * repository, since the store keeps no record of what it no longer holds.
 *
 * <p>What a repository was defined as - an upstream, a group - is a setting rather than an object in its scope, so
 * the caller forgets it; this class owns the scope alone.
 */
public final class RepositoryRemoval {

    /** The marker that says a repository is being deleted, at the root of its scope beside its document. */
    public static final String MARKER = ".removing";

    private RepositoryRemoval() {
    }

    /** What beginning a removal found. */
    public enum Begun {

        /** The repository existed and is now being deleted. */
        STARTED,

        /** It was already being deleted - a removal that stopped part way, which is resumed. */
        RESUMED,

        /** There is no repository by that name. */
        ABSENT
    }

    /**
     * Mark the repository whose scope {@code repository} is as being deleted and take away its document, so it stops
     * answering at once.
     */
    public static Begun begin(ArtifactStore repository) throws IOException {
        if (removing(repository)) {
            // Resumed: the marker is written afresh, so a reason a stopped purge left is not read as this one's.
            repository.write(MARKER, new ByteArrayInputStream(marker(Instant.now(), null)));
            repository.delete(Scopes.REPOSITORY);
            return Begun.RESUMED;
        }
        if (RepositoryDocument.read(repository).isEmpty()) {
            return Begun.ABSENT;
        }
        if (!repository.writeVersioned(MARKER, marker(Instant.now(), null), null)) {
            // Another request began it between the read and the write; the rest is the same.
            repository.delete(Scopes.REPOSITORY);
            return Begun.RESUMED;
        }
        repository.delete(Scopes.REPOSITORY);
        return Begun.STARTED;
    }

    /** Whether the repository whose scope this is is being deleted. */
    public static boolean removing(ArtifactStore repository) throws IOException {
        return repository.exists(MARKER);
    }

    /**
     * Where the deletion of the repository whose scope this is stands, from its marker and its document: two point
     * reads, whatever the repository holds.
     */
    public static Status status(ArtifactStore repository) throws IOException {
        Optional<ArtifactStore.Versioned> marker = repository.readVersioned(MARKER);
        if (marker.isEmpty()) {
            return new Status(RepositoryDocument.exists(repository) ? State.PRESENT : State.GONE, null, null);
        }
        Properties read = new Properties();
        read.load(new StringReader(new String(marker.get().content(), StandardCharsets.UTF_8)));
        String started = read.getProperty("started", "");
        Instant at;
        try {
            at = started.isBlank() ? null : Instant.parse(started);
        } catch (DateTimeParseException _) {
            at = null;
        }
        String failure = read.getProperty("failed", "");
        return failure.isBlank() ? new Status(State.RUNNING, at, null) : new Status(State.FAILED, at, failure);
    }

    /** Where a repository's deletion stands. */
    public enum State {

        /** The repository is there and nothing is deleting it. */
        PRESENT,

        /** A deletion has begun and its purge has not finished. */
        RUNNING,

        /** A purge stopped part way; deleting the repository again carries on from what is left. */
        FAILED,

        /** Neither the repository nor a deletion of it: a deletion that finished, or a name that never held one. */
        GONE
    }

    /** A deletion's {@code state}, when it began ({@code null} unless one is running or stopped) and why its purge
     *  stopped ({@code null} unless it did). */
    public record Status(State state, Instant startedAt, String failure) {
    }

    /** The marker's body: when the deletion began, and why its purge stopped once one has. */
    private static byte[] marker(Instant started, String failure) {
        String body = "started=" + started + "\n"
                + (failure == null ? "" : "failed=" + failure.replace('\n', ' ').replace('\r', ' ') + "\n");
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@link #purge} on a thread of its own, so the request that began the removal returns before a large repository is
     * gone; a purge that stops part way says so in the log, and the repository still reads as being deleted until it
     * is deleted again.
     */
    public static void purgeInBackground(ArtifactStore tenant, String repository, String name) {
        System.Logger logger = System.getLogger(RepositoryRemoval.class.getName());
        BackgroundJobs.start(tenant, "repository-removal-" + name.replace('/', '-'), () -> {
            try {
                purge(tenant, repository);
                logger.log(System.Logger.Level.INFO, "Deleted repository {0} and everything it held.", name);
            } catch (IOException | RuntimeException failed) {
                logger.log(System.Logger.Level.WARNING, "Deleting repository " + name + " stopped part way; it still "
                        + "reads as being deleted, and deleting it again finishes it.", failed);
                stopped(tenant, repository, failed);
            }
        });
    }

    /** Records in the marker why a purge stopped, keeping when it began, so {@link #status} can say so; best-effort,
     *  since a store that refused the purge may refuse this too, and the deletion then still reads as running. */
    static void stopped(ArtifactStore tenant, String repository, Exception failure) {
        String key = repository + "/" + MARKER;
        try {
            Status status = status(tenant.scope(repository));
            Instant started = status.startedAt() == null ? Instant.now() : status.startedAt();
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            tenant.write(key, new ByteArrayInputStream(marker(started, reason)));
        } catch (IOException | RuntimeException _) {
            // the marker still says the deletion began; deleting again resumes it either way
        }
    }

    /**
     * Delete every object of {@code repository} in its {@code tenant}'s scope, a bounded page at a time, and the
     * marker last - so a removal that stops part way still reads as one and can be begun again.
     *
     * <p>From the tenant's scope rather than the repository's own, because a backend tidies the containers a delete
     * empties only inside the scope it deletes through: deleting through the repository's scope would leave its own
     * directory standing on the filesystem store, empty, and listed as a repository still.
     */
    public static void purge(ArtifactStore tenant, String repository) throws IOException {
        String prefix = repository + "/";
        String marker = prefix + MARKER;
        String cursor = "";
        while (true) {
            List<String> keys = new ArrayList<>();
            ArtifactStore.Scan scan = tenant.scan(prefix, cursor, Documents.PAGE, listed -> keys.add(listed.key()));
            for (String key : keys) {
                if (!key.equals(marker)) {
                    tenant.delete(key);
                }
            }
            if (!scan.truncated()) {
                break;
            }
            cursor = scan.cursor().orElseThrow();
        }
        tenant.delete(marker);
    }
}
