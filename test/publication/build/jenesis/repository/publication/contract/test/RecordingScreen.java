package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

import module java.base;

/**
 * The verdict archetype: a screen that votes from durable state and records both what it saw and what the chain
 * decided.
 *
 * <p>Two of its properties are the ones the contract's stranger clauses exist for. It records in {@code assess},
 * which is only sound because {@code assess} is <b>not</b> short-circuited by a {@code REJECT} - every screen is
 * still asked, so this record really does see every artifact. And it reads its verdict through
 * {@code readVersioned} rather than {@code exists}: a store outage must reach it as a failure, because
 * {@link ArtifactStore#exists} answers {@code false} rather than throwing and a screen keyed on it could not tell
 * "nothing against this artifact" from "I could not check", which is precisely the degradation to a default
 * {@code ACCEPT} clause 7 forbids.
 */
public final class RecordingScreen implements PublishInterceptor {

    /** The key space this screen owns. */
    public static final String SPACE = "kitscreen";

    /** Where a deployment's earlier decision about a coordinate lives - what {@code assess} reads. */
    public static final String VERDICTS = SPACE + "/verdict";

    /** What this screen saw, one row per artifact, including the ones the chain rejected. */
    public static final String SEEN = SPACE + "/seen";

    /** What the chain decided, one row per artifact. */
    public static final String COMMITTED = SPACE + "/committed";

    /** How many instances have been constructed. The census reads it to assert that the discovered chain is loaded
     *  once at {@code Publication} class load and cached for the process, rather than re-resolved per publication. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public RecordingScreen() {
        CONSTRUCTIONS.incrementAndGet();
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        // readVersioned, never exists: a backend outage has to arrive here as an exception, or this screen would
        // answer "nothing against it" for an artifact it never managed to look up.
        Optional<String> recorded = Keys.read(content.store(), VERDICTS + "/" + Keys.slug(artifact.path()));
        Keys.upsert(content.store(), SEEN + "/" + Keys.slug(artifact.path()), artifact.path());
        return recorded.map(Disposition::valueOf).orElse(Disposition.ACCEPT);
    }

    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        // An upsert, because committed is called again on every replay - including the replay that repairs a first
        // attempt which crashed between this notification and the visibility write.
        Keys.upsert(store, COMMITTED + "/" + Keys.slug(artifact.path()), disposition.name());
    }
}
