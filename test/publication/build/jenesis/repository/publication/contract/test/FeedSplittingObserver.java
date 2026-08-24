package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

import module java.base;

/**
 * An observer that records on <em>both</em> feeds and keeps them apart - the shape needed to prove that a hook
 * conflating them is caught.
 *
 * <p>It exists because nothing else in the kit has both halves. A hook can only exhibit "a publish row from the
 * withhold leg" if it has a publish row to write and a withhold leg to write it from, and every real fixture is
 * missing one or the other: the forwarding and subtree-size hooks observe no withhold feed, and the index-retraction
 * hook has no publish row, because a publish is not a withhold transition for it. So the mutation was declared
 * against a property no fixture could falsify - not because no probe could see it, but because no subject could
 * exhibit it.
 *
 * <p>The two rows share a key space and differ in their <em>value</em>, deliberately: that is the case a key-level
 * assertion cannot separate, and the one a real upserting hook would present.
 */
public final class FeedSplittingObserver implements PublicationObserver {

    public static final String SPACE = "kitfeeds";

    /** What a publish records. */
    public static final String PUBLISHED = "published";

    /** What a withhold records - the same key, a different fact. */
    public static final String WITHHELD = "withheld";

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        Keys.upsert(store, SPACE + "/" + Keys.slug(artifact.path()), PUBLISHED);
    }

    @Override
    public void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // The whole point: a held artifact gets a row, and it is NOT the publish row. An observer that treated the
        // two feeds as one would write PUBLISHED here, which is exactly the mutation this fixture exists to catch.
        Keys.upsert(store, SPACE + "/" + Keys.slug(artifact.path()), WITHHELD);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        Keys.upsert(store, SPACE + "/" + Keys.slug(artifact.path()), PUBLISHED);
    }
}
