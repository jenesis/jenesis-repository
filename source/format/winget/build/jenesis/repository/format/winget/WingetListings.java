package build.jenesis.repository.format.winget;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The two documents a winget client's reads are answered from, as stored listings: a per-package version list
 * ({@code winget/<repo>/packages/<id>}, one entry per servable version) and the repository's search index
 * ({@code winget/<repo>/index}, one entry per package) derived from it on every write.
 *
 * <p>The derivation is what keeps {@code POST manifestSearch} bounded. A search that folded over every package's
 * manifests would cost the whole repository per query; instead each publish rewrites the one package's version list
 * and, from it, that package's one line in the index, so a query reads a single document. The index line carries the
 * fields the search response is built from - the identifier, the package name, the publisher and the servable versions
 * - because a response assembled by re-reading each matched package's manifest would put the fold back on the request
 * path one level down.
 *
 * <p>A version is listed exactly when its manifest is stored, is not withheld, and is not marked as removed from the
 * lifecycle's point of view. That is the same screen the read applies, stated once here so the index cannot disagree
 * with what {@code packageManifests} will actually serve.
 */
final class WingetListings {

    /** A package's version list: one version per line, the line being the entry id. */
    static final StoredListing.Codec VERSIONS = StoredListing.Codec.delimited("\n", Function.identity());

    /** The search index: {@code <PackageIdentifier>\t<compact JSON>} per line, keyed by the identifier before the tab.
     *  The identifier is carried out of band rather than parsed back out of the JSON, so splitting the document costs
     *  a scan for a tab instead of a parse per line. */
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
                    // Stated at the package list's sequence, so the rebuild pass's regeneration of the index - a
                    // walk over every package's list, which can be a beat behind this write - never puts an older
                    // line over the one this derivation wrote.
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
            if ((flag == null || flag.state() != Lifecycle.State.YANKED)
                    && !blobs.withheld(WingetFormat.manifestKey(repo, identifier, version))) {
                entries.put(version, version.getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /**
     * Emit an index line per package, in the order the scan yields them.
     *
     * <p>The index names every package in the repository, so collecting the lines into a sorted map held the
     * repository. The scan's order is the sink's order - the store's lexicographic child order. The key is the
     * identifier, which is the child name itself; that is what makes the substitution sound, since a key composed
     * across nested scans would not arrive in scan order.
     */
    private void generateIndex(String repo, StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, WingetFormat.manifestPrefix(repo), identifier -> {
            // Each package's list, materialised if need be - without the derivation, which would write back into the
            // very document this generation is producing.
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

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the index names every package by
     *  definition, so neither the names nor the round-trips that fetch them may cap it, and what is bounded is how
     *  many names are in hand at once. Capping either one silently omits packages - or, once the entry cap alone was
     *  lifted, stopped omitting them and started throwing instead, at exactly {@code steps x page} names. That is
     *  the ceiling the OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not
     *  answer short, it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();

    /**
     * The index line for one package: its identifier, the display fields read from its newest servable manifest, and
     * the servable versions. Empty when no manifest can be read - a package whose every manifest has gone is not in
     * the index, which is the same answer as never having been published.
     */
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

    /** Re-decide one version's membership from the store's current state - after a publish, a hold, a release, a mark
     *  or a removal. */
    void refresh(String repo, String identifier, String version) throws IOException {
        boolean servable = blobs.exists(WingetFormat.manifestKey(repo, identifier, version))
                && !blobs.withheld(WingetFormat.manifestKey(repo, identifier, version))
                && Lifecycle.read(store, identifier, version)
                        .filter(flag -> flag.state() == Lifecycle.State.YANKED).isEmpty();
        if (servable) {
            StoredListing.put(store, packageSpec(repo, identifier), version, version.getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, packageSpec(repo, identifier), version);
        }
    }
}
