package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * The files each version holds, as the version's own record lists them. A blobs-namespace format records a file's
 * pointer key here before it links the file, and reads a version's keys back when an eviction deletes them or a hold
 * withholds them - one list per version, in the version's document, rather than an index of each format's own.
 *
 * <p>Recording comes first, so no linked file is missing from its version's list; a crash between the two leaves a
 * listed key whose pointer never landed, which a reader skips by its pointer and the inventory's reconcile removes with
 * the record of a version nothing serves. Reading never writes.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> Concurrent records of one version's files all land: a record is a union, under the
 *     version document's compare-and-set.</li>
 * <li><b>Idempotency / replay.</b> Recording a key the version already lists changes nothing.</li>
 * <li><b>Absence sentinel.</b> {@link #listed} answers empty for a version whose files were never recorded - one
 *     published before its format recorded them - and its caller then finds the keys by a walk of its own; an empty
 *     list is a version that listed files and now has none.</li>
 * <li><b>Selection failure.</b> {@code OPTIONAL_UNIQUE}: with no implementation installed, {@link #installed} is
 *     {@link #NONE}, which records nothing and lists nothing, so every caller walks; two installed is an error.</li>
 * <li><b>Durability / delivery.</b> A record has landed when the call returns, so a caller that records before it
 *     links leaves no linked file unlisted; a failure propagates and the caller links nothing.</li>
 * <li><b>Read purity.</b> {@link #listed} never writes - a listed key whose pointer is gone is the caller's to skip,
 *     never this face's to delete.</li>
 * <li><b>Bounded work.</b> {@link #listed} is one point read of the version's document.</li>
 * </ol>
 */
public interface VersionFiles {

    /** Record that {@code key} is one of a version's files. */
    void record(ArtifactStore store, String ecosystem, String coordinate, String version, String key)
            throws IOException;

    /** The keys of a version's files as its record lists them, or empty when its files were never recorded. */
    Optional<List<String>> listed(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException;

    /** The absent implementation: records nothing and lists nothing. */
    VersionFiles NONE = new VersionFiles() {
        @Override
        public void record(ArtifactStore store, String ecosystem, String coordinate, String version, String key) {
        }

        @Override
        public Optional<List<String>> listed(ArtifactStore store, String ecosystem, String coordinate,
                                             String version) {
            return Optional.empty();
        }
    };

    /** The installed implementation, or {@link #NONE}. Held, since every publish of a blobs-namespace format asks and
     *  what is installed does not change while the process runs. */
    static VersionFiles installed() {
        return InstalledVersionFiles.FILES;
    }
}

/** The installed {@link VersionFiles}, resolved once per process. */
final class InstalledVersionFiles {

    static final VersionFiles FILES = Providers.singleton("version files", ServiceLoader.load(VersionFiles.class))
            .orElse(VersionFiles.NONE);

    private InstalledVersionFiles() {
    }
}
