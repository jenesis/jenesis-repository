package build.jenesis.repository.format.ivy;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;

/**
 * A module's revisions, as the document an Ivy resolver discovers them from.
 *
 * <h2>Why this exists at all, which is the whole difference from Maven</h2>
 *
 * <p>Maven publishes a {@code maven-metadata.xml} naming a coordinate's versions. Ivy publishes nothing of the
 * kind: a resolver asked for {@code 1.+} or {@code latest.release} <b>lists the module directory</b> and picks
 * from what it sees. That is one of the two differences between the formats that actually reaches a repository
 * server, and it is why an Ivy repository without this serves every pinned revision correctly and silently fails
 * every dynamic one.
 *
 * <p>So the listing is a stored document maintained on the write path, not a rendering: a publish adds the one
 * revision, and a read streams the document as it is. The on-read generation survives as the document's
 * <em>generator</em> - the first materialisation for a repository published to before this existed, and the
 * repair path afterwards - never as the read path.
 *
 * <h2>What a withheld revision must do to it</h2>
 *
 * <p>Leave it. A revision listed after its bytes are withheld is a revision {@code 1.+} <em>selects</em> and then
 * fails to download, which is worse than one that was never offered: the resolution succeeds, the build fails, and
 * the failure names a download rather than a hold. So the generator skips what is withheld and
 * {@link IvyListingObserver} re-decides the one entry when a hold, a release or a removal happens off the publish
 * path.
 */
final class IvyListings {

    /** The directory-listing document a resolver parses. Deliberately the plainest HTML that every Ivy resolver
     *  has read since the format existed - an anchor per revision, its own name as the text - because this is the
     *  one document whose consumer is a parser nobody here controls. */
    private static final String PROLOGUE = "<html><body>\n";

    private static final String EPILOGUE = "</body></html>\n";

    static final StoredListing.Codec REVISIONS = new StoredListing.Codec() {

        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            for (String line : new String(document, StandardCharsets.UTF_8).split("\n")) {
                String revision = revisionOf(line);
                if (revision != null) {
                    entries.put(revision, entry(revision));
                }
            }
            return entries;
        }

        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            StringBuilder document = new StringBuilder(PROLOGUE);
            for (byte[] entry : entries.values()) {
                document.append(new String(entry, StandardCharsets.UTF_8));
            }
            return document.append(EPILOGUE).toString().getBytes(StandardCharsets.UTF_8);
        }
    };

    private final ArtifactStore store;

    IvyListings(ArtifactStore store) {
        this.store = store;
    }

    /** One revision's line, which is also the entry the codec stores it as - so splitting and joining are inverse
     *  by construction rather than by two functions agreeing. */
    private static byte[] entry(String revision) {
        return ("<a href=\"" + revision + "/\">" + revision + "/</a>\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The revision an anchor names, or null for a line that is not one. */
    private static String revisionOf(String line) {
        int href = line.indexOf("href=\"");
        if (href < 0) {
            return null;
        }
        int end = line.indexOf('"', href + 6);
        if (end < 0) {
            return null;
        }
        String target = line.substring(href + 6, end);
        return target.endsWith("/") ? target.substring(0, target.length() - 1) : null;
    }

    static String listing(String organisation, String module) {
        return "ivy/" + organisation + "/" + module;
    }

    StoredListing.Spec spec(String organisation, String module) {
        return StoredListing.Spec.materialising(listing(organisation, module), REVISIONS,
                () -> generate(organisation, module));
    }

    /** Add a revision to the module's listing - what a publish under it obliges. */
    void published(String organisation, String module, String revision) throws IOException {
        StoredListing.put(store, spec(organisation, module), revision, entry(revision));
    }

    /**
     * Re-decide one revision by asking the store: listed when something under it still serves, absent otherwise.
     *
     * <p>Only correct where the store <em>can</em> answer that - the repair, and a lifecycle mark, both of which
     * run against the serving store. A hold notifies through the store it was written to rather than the one the
     * interceptor chain wraps, so it takes {@link #withdraw} instead: see the observer, where the difference is
     * recorded with the measurement behind it.
     */
    void refresh(String organisation, String module, String revision) throws IOException {
        if (servable(organisation, module, revision)) {
            published(organisation, module, revision);
        } else {
            StoredListing.remove(store, spec(organisation, module), revision);
        }
    }

    /** Take a revision out of its module's listing, because the transition said so rather than because the store
     *  was asked. {@code module} is {@code [organisation, module]}, as the observer resolved it. */
    void withdraw(String[] module, String revision) throws IOException {
        StoredListing.remove(store, spec(module[0], module[1]), revision);
    }

    /** Put it back, for the release that lifts a hold. */
    void restore(String[] module, String revision) throws IOException {
        published(module[0], module[1], revision);
    }

    /**
     * The module's revisions as the store has them - the generator, and therefore also the repair.
     *
     * <p>A revision counts when at least one file under it serves. A revision whose every file is withheld is a
     * revision a resolver must not select, and one whose directory is empty was never a revision at all.
     */
    private SortedMap<String, byte[]> generate(String organisation, String module) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String revision : store.list(IvyFormat.publishPrefix(organisation, module))) {
            if (servable(organisation, module, revision)) {
                entries.put(revision, entry(revision));
            }
        }
        return entries;
    }

    /**
     * Regenerate this listing if it is one of ours, which is the repair half of maintaining it on the write path.
     *
     * <p>A listing key here is {@code ivy/<organisation>/<module>} and nothing else, so recognising one is exact
     * rather than a guess - and a key that is not ours is declined rather than rebuilt into something wrong.
     */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length != 3 || !segments[0].equals("ivy")) {
            return false;
        }
        StoredListing.rebuild(store, spec(segments[1], segments[2]));
        return true;
    }

    /** Whether anything under this revision would serve: at least one pointer that is not withheld. */
    private boolean servable(String organisation, String module, String revision) throws IOException {
        Publication publication = new Publication(store);
        String prefix = IvyFormat.publishPrefix(organisation, module) + "/" + revision;
        for (String file : store.list(prefix)) {
            if (publication.locate("/" + IvyFormat.REQUEST_ROOT + organisation + "/" + module + "/"
                    + revision + "/" + file).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
