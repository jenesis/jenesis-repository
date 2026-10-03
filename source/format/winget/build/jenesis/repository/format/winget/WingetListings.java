package build.jenesis.repository.format.winget;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The two documents winget reads are answered from, as stored listings: a per-package version list
 * ({@code winget/<repo>/packages/<id>}) and the repository's search index ({@code winget/<repo>/index}, one line per
 * package) derived from it on every write. Each publish rewrites one package's list and its one index line, so a search
 * reads one document; the line carries the identifier, name, publisher and servable versions a search response is built
 * from, so no manifest is re-read on the request path.
 *
 * <p>A version is listed exactly when its manifest is stored, not withheld and not marked removed - the read's own
 * screen, stated once so the index cannot disagree with {@code packageManifests}.
 */
final class WingetListings {

    /** A package's version list: one version per line, the line being the entry id. */
    static final StoredListing.Codec VERSIONS = StoredListing.Codec.delimited("\n", Function.identity());

    /** The search index: {@code <PackageIdentifier>\t<compact JSON>} per line, keyed by the identifier before the tab,
     *  so splitting costs a tab scan rather than a parse per line. */
    static final StoredListing.Codec INDEX = StoredListing.Codec.delimited("\n", line -> {
        int tab = line.indexOf('\t');
        return tab < 0 ? line : line.substring(0, tab);
    });

    private final Blobs blobs;
    private final ArtifactStore store;

    WingetListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String packageListing(String repo, String identifier) {
        return "winget/" + repo + "/packages/" + identifier;
    }

    static String indexListing(String repo) {
        return "winget/" + repo + "/index";
    }

    /** One package's version list, deriving that package's single line in the repository index on every write. */
    StoredListing.Spec packageSpec(String repo, String identifier) {
        return StoredListing.Spec.materialising(packageListing(repo, identifier), VERSIONS,
                        () -> generatePackage(repo, identifier))
                .deriving(document -> {
                    // Stated at the package list's sequence, so the rebuild pass's slightly older regeneration never
                    // overwrites it.
                    SortedMap<String, byte[]> versions = VERSIONS.split(document.body());
                    Optional<byte[]> line = versions.isEmpty() ? Optional.empty()
                            : indexLine(repo, identifier, versions.keySet());
                    if (line.isPresent()) {
                        StoredListing.put(store, indexSpec(repo), identifier, line.get(), document.header().seq());
                    } else {
                        StoredListing.remove(store, indexSpec(repo), identifier, document.header().seq());
                    }
                });
    }

    StoredListing.Spec indexSpec(String repo) {
        return StoredListing.Spec.of(indexListing(repo), INDEX, sink -> generateIndex(repo, sink));
    }

    private SortedMap<String, byte[]> generatePackage(String repo, String identifier) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, identifier);
        for (String version : blobs.list(WingetFormat.manifestPrefix(repo) + "/" + identifier)) {
            Lifecycle.Flag flag = marks.get(version);
            if ((flag == null || flag.state() != LifecycleMark.YANKED)
                    && !blobs.withheld(WingetFormat.manifestKey(repo, identifier, version))) {
                entries.put(version, version.getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Emit an index line per package in the scan's order - the store's lexicographic child order, keyed by the
     *  identifier that is the child name - so the index is never collected in a map holding the repository. */
    private void generateIndex(String repo, StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, WingetFormat.manifestPrefix(repo), identifier -> {
            // Each package's list, materialised if need be - without the derivation, which would write into this
            // document.
            Optional<StoredListing.Document> document = StoredListing.read(store,
                    StoredListing.Spec.materialising(packageListing(repo, identifier), VERSIONS,
                            () -> generatePackage(repo, identifier)));
            if (document.isEmpty()) {
                return;
            }
            SortedMap<String, byte[]> versions = VERSIONS.split(document.get().body());
            Optional<byte[]> line = versions.isEmpty() ? Optional.empty()
                    : indexLine(repo, identifier, versions.keySet());
            if (line.isPresent()) {
                sink.accept(identifier, line.get(), document.get().header().seq());
            } else {
                sink.absent(identifier, document.get().header().seq());
            }
        });
    }

    /** The stride the repository-wide index is enumerated in. It drains: the index names every package, so neither the
     *  names nor the round trips may cap it - a cap would omit packages or, at {@code steps x page}, throw and leave
     *  the document unmaterialised. What is bounded is how many names are in hand at once. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();

    /** The index line for one package: its identifier, the display fields of its newest servable manifest, and the
     *  servable versions. Empty when no manifest can be read, the same answer as never published. */
    private Optional<byte[]> indexLine(String repo, String identifier, Set<String> versions) throws IOException {
        List<String> ordered = new ArrayList<>(versions);
        ordered.sort(Comparator.reverseOrder());
        for (String version : ordered) {
            Optional<byte[]> manifest = WingetFormat.readManifest(blobs, repo, identifier, version);
            if (manifest.isPresent()) {
                return Optional.of(WingetFormat.indexEntry(identifier, manifest.get(), ordered));
            }
        }
        return Optional.empty();
    }

    /** Regenerate the listing at this key if it is a winget one: a package's version list or the repository index. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("winget") || segments.length < 3) {
            return false;
        }
        String repo = segments[1];
        if (segments.length == 4 && segments[2].equals("packages")) {
            StoredListing.rebuild(store, packageSpec(repo, segments[3]));
            return true;
        }
        if (segments.length == 3 && segments[2].equals("index")) {
            StoredListing.rebuild(store, indexSpec(repo));
            return true;
        }
        return false;
    }

    /** Re-decide one version's membership from the store's current state. */
    void refresh(String repo, String identifier, String version) throws IOException {
        boolean servable = blobs.exists(WingetFormat.manifestKey(repo, identifier, version))
                && !blobs.withheld(WingetFormat.manifestKey(repo, identifier, version))
                && Lifecycle.read(store, identifier, version)
                        .filter(flag -> flag.state() == LifecycleMark.YANKED).isEmpty();
        if (servable) {
            StoredListing.put(store, packageSpec(repo, identifier), version, version.getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, packageSpec(repo, identifier), version);
        }
    }
}
