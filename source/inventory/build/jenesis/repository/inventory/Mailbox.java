package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A per-repository set of versions somebody asked a pass to look at again: one row per version at
 * {@code <root>/<sha-256 of the version>}, naming its ecosystem, coordinate and version as JSON, written blind where the
 * request is made - so a version asked about twice before a pass drains it is one row.
 *
 * <p>A row is a hint, never a record: it is removed before it is acted on ({@link #drain}), so a pass that fails after
 * removing it leaves the version to the full pass of its owner, which re-derives everything, and a row nobody drains is
 * removed with its version where the owner says so ({@link #forget}), so the space is bounded by the versions the
 * repository holds.
 */
public final class Mailbox {

    /** The versions whose standing changed since a pass last looked - a finding recorded, superseded or re-scored, a
     *  hold placed or ended - drained by the pass that tells what relies on a version to look again, and forgotten
     *  with the version on an eviction or when the reconcile finds its pointers gone. */
    public static final Mailbox CHANGED = new Mailbox("changed");

    private final String root;

    /** The mailbox whose rows live under {@code root} in each repository's store. */
    public Mailbox(String root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** The root of the rows, which the owner's storage manifest names. */
    public String root() {
        return root;
    }

    /** The row of {@code coordinate} at {@code version} of {@code ecosystem}. */
    String key(String ecosystem, String coordinate, String version) {
        return root + "/" + VersionRows.digest(ecosystem, coordinate, version);
    }

    /** Ask the pass draining this mailbox in {@code store} to look at {@code coordinate} at {@code version} of
     *  {@code ecosystem} again. */
    public void post(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        store.write(key(ecosystem, coordinate, version),
                new ByteArrayInputStream(VersionRows.encode(ecosystem, coordinate, version)));
    }

    /** Remove the row of a version the repository no longer holds, if it has one. */
    public void forget(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        String key = key(ecosystem, coordinate, version);
        if (store.exists(key)) {
            store.delete(key);
        }
    }

    /** What a pass does with one version it was asked about. */
    @FunctionalInterface
    public interface Visitor {

        void accept(StoreRepositoryInventory.Coordinate version) throws IOException;
    }

    /**
     * Remove up to {@code limit} rows, handing each version to {@code visitor} once its row is gone - so a request made
     * while the visitor runs writes a row of its own, which the next drain finds. A row that does not parse is removed
     * and passed over. Answers how many rows were removed, so a caller can tell a drain that met its limit.
     */
    public int drain(ArtifactStore store, int limit, Visitor visitor) throws IOException {
        List<String> names = new ArrayList<>();
        store.page(root, "", limit, names::add);
        for (String name : names) {
            String key = root + "/" + name;
            Optional<ArtifactStore.Versioned> row = store.readVersioned(key);
            store.delete(key);
            Optional<StoreRepositoryInventory.Coordinate> version = row.flatMap(read -> VersionRows.decode(read.content()));
            if (version.isPresent()) {
                visitor.accept(version.get());
            }
        }
        return names.size();
    }
}
