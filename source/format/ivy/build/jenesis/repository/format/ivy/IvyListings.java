package build.jenesis.repository.format.ivy;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;

/**
 * A module's revisions, as the document an Ivy resolver discovers them from. Ivy publishes no
 * {@code maven-metadata.xml}: a resolver asked for {@code 1.+} or {@code latest.release} lists the module directory and
 * picks, so without this every dynamic revision would fail.
 *
 * <p>The listing is a stored document maintained on the write path: a publish adds its revision and a read streams the
 * document. The generator is the first materialisation and the repair, never the read path.
 *
 * <p>A withheld revision leaves it: listed, {@code 1.+} would select it and fail at the download, naming a download
 * rather than a hold. The generator skips what is withheld and {@link IvyListingObserver} re-decides an entry on a
 * hold, release or removal.
 */
final class IvyListings {

    /** The directory listing a resolver parses: the plainest HTML every Ivy resolver reads - an anchor per revision,
     *  its name as the text - since its consumer is a parser nobody here controls. */
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

    /** One revision's line, which is also its stored entry, so splitting and joining are inverse by construction. */
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

    /** Re-decide one revision by asking the store: listed when something under it still serves. Correct only against
     *  the serving store - the repair and a lifecycle mark; a hold uses {@link #withdraw} (see the observer). */
    void refresh(String organisation, String module, String revision) throws IOException {
        if (servable(organisation, module, revision)) {
            published(organisation, module, revision);
        } else {
            StoredListing.remove(store, spec(organisation, module), revision);
        }
    }

    /** Take a revision out of its module's listing because the transition said so. {@code module} is
     *  {@code [organisation, module]}, as the observer resolved it. */
    void withdraw(String[] module, String revision) throws IOException {
        StoredListing.remove(store, spec(module[0], module[1]), revision);
    }

    /** Put it back, for the release that lifts a hold. */
    void restore(String[] module, String revision) throws IOException {
        published(module[0], module[1], revision);
    }

    /** The module's revisions as the store has them - the generator and the repair. A revision counts when at least one
     *  file under it serves. */
    private SortedMap<String, byte[]> generate(String organisation, String module) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String revision : store.list(IvyFormat.publishPrefix(organisation, module))) {
            if (servable(organisation, module, revision)) {
                entries.put(revision, entry(revision));
            }
        }
        return entries;
    }

    /** Regenerate this listing if it is one of ours: a key is exactly {@code ivy/<organisation>/<module>}, and any
     *  other is declined. */
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
