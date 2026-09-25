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
            repository.delete(Scopes.REPOSITORY);
            return Begun.RESUMED;
        }
        if (RepositoryDocument.read(repository).isEmpty()) {
            return Begun.ABSENT;
        }
        byte[] marker = ("started=" + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        if (!repository.writeVersioned(MARKER, marker, null)) {
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
     * {@link #purge} on a thread of its own, so the request that began the removal returns before a large repository is
     * gone; a purge that stops part way says so in the log, and the repository still reads as being deleted until it
     * is deleted again.
     */
    public static void purgeInBackground(ArtifactStore tenant, String repository, String name) {
        System.Logger logger = System.getLogger(RepositoryRemoval.class.getName());
        Thread.ofVirtual().name("repository-removal-" + name.replace('/', '-')).start(() -> {
            try {
                purge(tenant, repository);
                logger.log(System.Logger.Level.INFO, "Deleted repository {0} and everything it held.", name);
            } catch (IOException | RuntimeException failed) {
                logger.log(System.Logger.Level.WARNING, "Deleting repository " + name + " stopped part way; it still "
                        + "reads as being deleted, and deleting it again finishes it.", failed);
            }
        });
    }

    /**
     * Delete every object of {@code repository} in its {@code tenant}'s scope, a bounded page at a time, and the
     * marker last - so a removal that stops part way still reads as one and can be begun again.
     *
     * <p>From the tenant's scope rather than the repository's own, because a backend tidies the containers a delete
     * empties only inside the scope it deletes through: deleting through the repository's scope left its own directory
     * standing on the filesystem store, empty, and listed as a repository still.
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
