package build.jenesis.repository.format.gems;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The RubyGems compact index as stored listings: a {@code /info/<gem>} document per gem, whose entries are its version
 * lines, and {@code /versions}, a line per gem naming its versions and its info document's MD5. A gem's info document
 * re-derives its {@code /versions} line on every write, so a push rewrites two documents and scans no other gem. A
 * version is listed exactly when servable: its {@code .gem} not withheld and the version unmarked.
 */
final class RubyGemsListings {

    static final String VERSIONS = "rubygems/versions";

    private static final String VERSIONS_HEADER = "created_at: 1990-01-01T00:00:00Z\n---\n";

    /** An info document: {@code ---} then one line per version, keyed by the version (the line's first token). */
    static final StoredListing.Codec INFO = lines("---\n", line -> line.substring(0, line.indexOf(' ')));

    /** The compact index: its header, then one line per gem, keyed by the gem name (the line's first token). */
    static final StoredListing.Codec COMPACT = lines(VERSIONS_HEADER, line -> line.substring(0, line.indexOf(' ')));

    /** A compact-index document: a fixed header, then one line per entry, through the shared streaming
     *  {@link StoredListing#framed} frame with no footer. */
    private static StoredListing.Codec lines(String header, Function<String, String> idOf) {
        return StoredListing.framed(header, "", StoredListing.Codec.delimited("\n", idOf));
    }

    private final Blobs blobs;
    private final ArtifactStore store;

    RubyGemsListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String info(String name) {
        return "rubygems/" + name + "/info";
    }

    /** Whether a compact index lists any gem; an empty one is served as a {@code 404}, not a header alone. */
    static boolean empty(StoredListing.Header header) {
        return header.size() <= VERSIONS_HEADER.length();
    }

    StoredListing.Spec infoSpec(String name) {
        return StoredListing.Spec.materialising(info(name), INFO, () -> generateInfo(name)).withMd5().deriving(document -> {
            // Stated at the info document's sequence, so the rebuild pass's regeneration of the compact index, which
            // can lag this write, never puts an older line over this one.
            SortedMap<String, byte[]> versions = INFO.split(document.body());
            if (versions.isEmpty()) {
                StoredListing.remove(store, versionsSpec(), name, document.header().seq());
            } else {
                String line = name + " " + String.join(",", versions.keySet()) + " " + md5(document);
                StoredListing.put(store, versionsSpec(), name, line.getBytes(StandardCharsets.UTF_8),
                        document.header().seq());
            }
        });
    }

    StoredListing.Spec versionsSpec() {
        return StoredListing.Spec.of(VERSIONS, COMPACT, this::generateVersions);
    }

    private SortedMap<String, byte[]> generateInfo(String name) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, name);
        for (String version : blobs.list("rubygems/" + name + "/versions")) {
            if (marks.containsKey(version) || blobs.withheld(RubyGemsFormat.gemKey(name, version))) {
                continue;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (blobs.read("rubygems/" + name + "/versions/" + version, buffer)) {
                entries.put(version, buffer.toByteArray());
            }
        }
        return entries;
    }

    /** Emit a compact-index line per gem in the scan's order, the store's lexicographic child order, which the document
     *  needs; it is every gem in the repository, so it is never collected. */
    private void generateVersions(StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, "rubygems", name -> {
            // Each gem's info document, materialised without the derivation that would update this very document.
            Optional<StoredListing.Document> document = StoredListing.read(store,
                    StoredListing.Spec.materialising(info(name), INFO, () -> generateInfo(name)).withMd5());
            if (document.isEmpty()) {
                return;      // nothing to list for this gem
            }
            SortedMap<String, byte[]> versions = INFO.split(document.get().body());
            if (versions.isEmpty()) {
                sink.absent(name, document.get().header().seq());
            } else {
                String line = name + " " + String.join(",", versions.keySet()) + " " + md5(document.get());
                sink.accept(name, line.getBytes(StandardCharsets.UTF_8), document.get().header().seq());
            }
        });
    }

    /** A version was pushed: its compact-index line is stored; list it if it is servable. */
    void published(String name, String version, byte[] line) throws IOException {
        if (servable(name, version)) {
            StoredListing.put(store, infoSpec(name), version, line);
        } else {
            StoredListing.remove(store, infoSpec(name), version);
        }
    }

    /** Re-decide one version's membership from the store's current state. */
    void refresh(String name, String version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read("rubygems/" + name + "/versions/" + version, buffer)) {
            StoredListing.remove(store, infoSpec(name), version);
            return;
        }
        published(name, version, buffer.toByteArray());
    }

    private boolean servable(String name, String version) throws IOException {
        return !blobs.withheld(RubyGemsFormat.gemKey(name, version)) && Lifecycle.read(store, name, version).isEmpty();
    }

    /** Regenerate the listing at this key if it is a RubyGems one: a gem's info document or the compact index. */
    boolean rebuild(String listing) throws IOException {
        if (listing.equals(VERSIONS)) {
            StoredListing.rebuild(store, versionsSpec());
            return true;
        }
        String[] segments = listing.split("/");
        if (segments.length == 3 && segments[0].equals("rubygems") && segments[2].equals("info")) {
            StoredListing.rebuild(store, infoSpec(segments[1]));
            return true;
        }
        return false;
    }


    /** The info document's MD5 the compact index names: from its header, or of its body when the header carries
     *  none. */
    private static String md5(StoredListing.Derived document) throws IOException {
        return document.header().md5().isEmpty()
                ? StoredListing.Header.of(document.header().seq(), document.body(), true).md5()
                : document.header().md5();
    }

    /** The stride the repository-wide index is enumerated in. It drains: the index names every gem, so neither names
     *  nor round-trips are capped - a cap would omit gems or throw and never materialise the document - and only the
     *  names in hand are bounded. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
