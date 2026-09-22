package build.jenesis.repository.format.gems;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The RubyGems compact index as stored listings: one {@code /info/<gem>} document per gem, whose entries are the
 * version lines the push stored, and the repository-wide {@code /versions} document, whose entries are one line per
 * gem naming its versions and the MD5 of its info document. A gem's info document re-derives its line in
 * {@code /versions} on every write - the MD5 is the stored document's own digest - so a push costs one rewrite of the
 * gem's info document and one of the compact index, never a scan of every gem.
 *
 * <p>A version is listed exactly when it is servable: its {@code .gem} pointer not withheld and the version carrying
 * no lifecycle mark - the screen the on-read generation applied per version, applied here to the one version a write
 * touches.
 */
final class RubyGemsListings {

    static final String VERSIONS = "rubygems/versions";

    private static final String VERSIONS_HEADER = "created_at: 1990-01-01T00:00:00Z\n---\n";

    /** An info document: {@code ---} then one line per version, keyed by the version (the line's first token). */
    static final StoredListing.Codec INFO = lines("---\n", line -> line.substring(0, line.indexOf(' ')));

    /** The compact index: its header, then one line per gem, keyed by the gem name (the line's first token). */
    static final StoredListing.Codec COMPACT = lines(VERSIONS_HEADER, line -> line.substring(0, line.indexOf(' ')));

    /**
     * A compact-index document: a fixed header, then one line per version.
     *
     * <p>{@link StoredListing#framed} with no footer, rather than the hand-written equivalent this was. The copy
     * implemented {@code split} and {@code join} and nothing else, so it inherited the materialising {@code append}
     * and {@code read} - which meant the streaming generator below wrote into a buffer and every version published
     * rewrote the whole {@code versions} document in heap. Reaching for the shared frame is what fixes that,
     * because the shared frame streams.
     */
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

    /** Whether a compact index body lists any gem - an empty one is served as a {@code 404}, not a header alone. */
    static boolean empty(StoredListing.Header header) {
        return header.size() <= VERSIONS_HEADER.length();
    }

    StoredListing.Spec infoSpec(String name) {
        return StoredListing.Spec.materialising(info(name), INFO, () -> generateInfo(name)).withMd5().deriving(document -> {
            // Stated at the info document's sequence, so the rebuild pass's regeneration of the compact index -
            // a walk over every gem's document, which can be a beat behind this write - never puts an older line
            // over the one this derivation wrote.
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

    /**
     * Emit a compact-index line per gem, in the order the scan yields them.
     *
     * <p>The compact index names every gem in the repository, so collecting the lines into a map held the
     * repository. The scan's order is the sink's order - the store's lexicographic child order, which is what the
     * sorted map used to supply.
     */
    private void generateVersions(StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, "rubygems", name -> {
            // Each gem's info document, materialised if need be - without the derivation that would update the very
            // document this generation is producing.
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

    /** Re-decide one version's membership from the store's current state - after a hold, a release or a mark. */
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
     *  none (a document stored through a spec that did not ask for it). */
    private static String md5(StoredListing.Derived document) throws IOException {
        return document.header().md5().isEmpty()
                ? StoredListing.Header.of(document.header().seq(), document.body(), true).md5()
                : document.header().md5();
    }

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the index names every package by
     *  definition, so neither the names nor the round-trips that fetch them may cap it, and what is bounded is how
     *  many names are in hand at once. Capping either one silently omits packages - or, once the entry cap alone was
     *  lifted, stopped omitting them and started throwing instead, at exactly {@code steps x page} names. That is
     *  the ceiling the OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not
     *  answer short, it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
