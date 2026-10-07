package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The hook of one hold kind: a {@link HoldReleaseObserver} that owns the key space of a {@link HoldKind}'s hold records
 * and answers whether it holds a path, which every automated release and the accepted-re-publish guard ask of every
 * kind. A hook with no records of its own - one that only reacts to a discard - is a plain
 * {@link HoldReleaseObserver} and is never asked. Both methods are abstract: a kind that answered no hold by default
 * would drop out of the guard, and one named by its class would match no record.
 *
 * <p>The contract is {@link HoldReleaseObserver}'s; what follows is this role's.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Identity.</b> {@link #kind()} is the {@code <kind>} segment of the hook's {@code holds/<kind>/} and
 *     {@code overrides/<kind>/} keys, unique across the installed hooks: two hooks of one kind share those records,
 *     so one's release would clear the other's hold, and the fan-out refuses them.</li>
 * <li><b>Error visibility.</b> {@link #holds} raises a read it cannot complete, never answering {@code false} for
 *     it: {@code false} lets an artifact out.</li>
 * <li><b>Read purity.</b> {@link #holds} reads the kind's own record for the path's coordinate version and writes
 *     nothing.</li>
 * </ol>
 */
public interface HoldKindObserver extends HoldReleaseObserver {

    /** The kind token of this hook's hold records, the {@code <kind>} segment of its
     *  {@code holds/<kind>/<eco>/<coord>/<ver>} keys, so an automated release of one kind can ask whether another
     *  still holds. */
    String kind();

    /** Whether this kind holds a retroactive record for {@code path}'s coordinate version, which the
     *  accepted-re-publish guard asks of every kind. */
    boolean holds(ArtifactStore store, String path) throws IOException;
}
