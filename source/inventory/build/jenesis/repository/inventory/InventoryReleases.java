package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.WalkPass;

/**
 * The release-enumeration subsystem extracted from {@link StoreRepositoryInventory}: the read side that streams the
 * published releases retention, garbage collection and the search/license sweeps all run over - the whole-store
 * {@link #releases} enumerations (the plain buffered form, the {@link RepositoryInventory.ReleaseVisitor} stream, and
 * the shared-{@link ArtifactWalk} form a scheduled surface rides), the cheaper coordinate-only {@link #coordinates}
 * projection, a single coordinate's {@link #versions}, and the blobs-namespace queries ({@link #blobHashes},
 * {@link #servesFromBlobs}). Each row is read from the {@link StoreRepositoryInventory#publishedRoot} tree plus a few
 * tiny sidecars, never an artifact blob, and a corrupt or raced row is skipped rather than aborting the enumeration so
 * one bad row never stops the repository converging. The facade owns the seam - those methods delegate here - and this
 * class shares the facade's subtree {@code walk} and its store-key/codec helpers rather than duplicating them.
 */
final class InventoryReleases {

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final ArtifactWalk walk;

    InventoryReleases(StoreRepositoryInventory inventory, ArtifactStore store, ArtifactWalk walk) {
        this.inventory = inventory;
        this.store = store;
        this.walk = walk;
    }

    /** Every published release, buffered - see {@link StoreRepositoryInventory#releases()}. */
    Collection<Release> releases() throws IOException {
        List<Release> releases = new ArrayList<>();
        inventory.walk(StoreRepositoryInventory.publishedRoot(), key -> {
            Release release = release(key);
            if (release != null) {
                releases.add(release);
            }
        });
        return releases;
    }

    /** Stream every published release grouped by coordinate - see
     *  {@link StoreRepositoryInventory#releases(RepositoryInventory.ReleaseVisitor)}. */
    void releases(RepositoryInventory.ReleaseVisitor visitor) throws IOException {
        if (walk == null) {
            inventory.walk(StoreRepositoryInventory.publishedRoot(), key -> deliver(key, visitor));
            return;
        }
        releases(walk, "retention", visitor);
    }

    /** Stream every published release over the caller's share of the resumable {@code walks/<consumer>} pass - see
     *  {@link StoreRepositoryInventory#releases(ArtifactWalk, String, RepositoryInventory.ReleaseVisitor)}. */
    WalkPass releases(ArtifactWalk walk, String consumer, RepositoryInventory.ReleaseVisitor visitor)
            throws IOException {
        return walk.walk(store, consumer, List.of(StoreRepositoryInventory.publishedRoot()),
                key -> deliver(key, visitor));
    }

    /** Both key spaces over ONE pass - see
     *  {@link StoreRepositoryInventory#releases(ArtifactWalk, String, RepositoryInventory.ReleaseVisitor,
     *  StoreRepositoryInventory.ServedPathVisitor)}. */
    WalkPass releases(ArtifactWalk walk, String consumer, RepositoryInventory.ReleaseVisitor releases,
                      StoreRepositoryInventory.ServedPathVisitor paths) throws IOException {
        String published = StoreRepositoryInventory.publishedRoot();
        return walk.walk(store, consumer, List.of(published, ServableNames.PUBLISHED), key -> {
            if (key.startsWith(published)) {
                deliver(key, releases);
            } else if (key.startsWith(ServableNames.PUBLISHED + "/")) {
                paths.accept(key.substring(ServableNames.PUBLISHED.length()));
            }
        });
    }

    /** Stream every served request path, outside any shared pass - the walk-less counterpart of the above. */
    void servedPaths(StoreRepositoryInventory.ServedPathVisitor visitor) throws IOException {
        inventory.walk(ServableNames.PUBLISHED, key -> {
            if (key.startsWith(ServableNames.PUBLISHED + "/")) {
                visitor.accept(key.substring(ServableNames.PUBLISHED.length()));
            }
        });
    }

    private void deliver(String key, RepositoryInventory.ReleaseVisitor visitor) throws IOException {
        Release release = release(key);
        if (release != null) {
            visitor.visit(release);
        }
    }

    /** Parse one {@link StoreRepositoryInventory#publishedRoot} row into its {@link Release} - the publish-time facts
     *  plus the last-download and pin markers, tiny reads, never an artifact blob - or {@code null} for a key that is
     *  no release row. A row whose timestamp cannot be read is skipped (conservative), not defaulted to EPOCH. */
    Release release(String key) throws IOException {
        Optional<StoreRepositoryInventory.PublishedAt> published = readPublished(key);
        if (published.isEmpty()) {
            return null;
        }
        StoreRepositoryInventory.PublishedAt at = published.get();
        return release(at.ecosystem(), at.coordinate(), at.version(), at.facts());
    }

    /** The last-download instant, or {@code fallback} when the marker is absent or unreadable - the marker is
     *  best-effort by design, so a corrupt one reads as "not downloaded since publish", never as an error. */
    /** One coordinate version as a point read of its publish facts and download marker - empty when it is not a
     *  published member. */
    Optional<Release> release(String ecosystem, String coordinate, String version) throws IOException {
        Optional<PublishedSection.Facts> facts = inventory.publishedFacts(ecosystem, coordinate, version);
        if (facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(release(ecosystem, coordinate, version, facts.get()));
    }

    /** The row: the publish facts and the download facts, each read once. The count and the newest instant come from
     *  the document's downloads section; a version never downloaded reads as downloaded at its publish time, which is
     *  what the retention rules read. */
    private Release release(String ecosystem, String coordinate, String version, PublishedSection.Facts facts)
            throws IOException {
        Optional<DownloadsSection.Facts> downloads = inventory.downloads(ecosystem, coordinate, version);
        Instant downloadedAt = downloads.map(DownloadsSection.Facts::last).orElse(null);
        return new Release(ecosystem, coordinate, version, facts.at(), downloadedAt == null ? facts.at() : downloadedAt,
                facts.prerelease(), facts.pinned(), downloads.map(DownloadsSection.Facts::count).orElse(null),
                downloadedAt);
    }

    /**
     * The publish facts a {@link StoreRepositoryInventory#publishedRoot} key carries when it is a published member,
     * else empty: the document's {@code published} section, whose {@code at} is membership. A {@code @coordinate}
     * document, a non-member document, a corrupt or unreadable row, and a key that raced away all read as empty rather
     * than aborting the enumeration.
     */
    private Optional<StoreRepositoryInventory.PublishedAt> readPublished(String key) throws IOException {
        if (!key.startsWith(MetadataKey.PREFIX + "/")) {
            return Optional.empty();
        }
        String[] segments = key.substring(MetadataKey.PREFIX.length() + 1).split("/");
        if (segments.length != 3) {
            return Optional.empty();
        }
        String ecosystem = segments[0];
        String coordinate = StoreRepositoryInventory.decode(segments[1]);
        String version = segments[2];
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (version.startsWith("@")) {
            return Optional.empty();                         // the per-coordinate document, not a version release
        }
        Optional<Section> section = MetadataDocument.read(stored.get().content()).section(PublishedSection.TAG);
        if (!PublishedSection.published(section)) {
            return Optional.empty();
        }
        return PublishedSection.facts(section)
                .map(facts -> new StoreRepositoryInventory.PublishedAt(ecosystem, coordinate, version, facts));
    }

    /** Whether any version of a coordinate is still a published member - see the last-version check in
     *  {@link StoreRepositoryInventory#evict}. */
    boolean anyPublishedVersion(String ecosystem, String coordinate) throws IOException {
        String encoded = ArtifactStore.segment(ecosystem) + "/" + StoreRepositoryInventory.encode(coordinate);
        for (String version : store.list(StoreRepositoryInventory.publishedRoot() + "/" + encoded)) {
            if (readPublished(StoreRepositoryInventory.publishedRoot() + "/" + encoded + "/" + version).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Every published coordinate version as its neutral triple - see {@link StoreRepositoryInventory#coordinates}. */
    List<StoreRepositoryInventory.Coordinate> coordinates() {
        List<StoreRepositoryInventory.Coordinate> coordinates = new ArrayList<>();
        try {
            coordinates(coordinates::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return coordinates;
    }

    /** Stream every published coordinate to {@code visitor} without buffering the whole set into a {@code List} - the
     *  enumeration the identity rollup folds over, so a repository with millions of versions never materialises them
     *  all in heap. Same membership as the buffered {@link #coordinates()}. */
    void coordinates(StoreRepositoryInventory.CoordinateVisitor visitor) throws IOException {
        // Membership is the document's published section, so each meta document is read once.
        inventory.walk(MetadataKey.PREFIX, key -> {
            Optional<StoreRepositoryInventory.PublishedAt> published = readPublished(key);
            if (published.isPresent()) {
                visitor.accept(new StoreRepositoryInventory.Coordinate(
                        published.get().ecosystem(), published.get().coordinate(), published.get().version()));
            }
        });
    }

    /** Stream every published member with its publish instant and declared-license set - the identity rebuild's
     *  enumeration: one document read per member, where the publish facts and the licenses are sections of the same
     *  document. Same membership as {@link #coordinates(StoreRepositoryInventory.CoordinateVisitor)}. */
    void members(StoreRepositoryInventory.MemberVisitor visitor) throws IOException {
        inventory.walk(MetadataKey.PREFIX, key -> {
            String[] segments = key.substring(MetadataKey.PREFIX.length() + 1).split("/");
            if (segments.length != 3 || segments[2].startsWith("@")) {
                return;                                  // the per-coordinate document, not a version release
            }
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
            if (stored.isEmpty()) {
                return;
            }
            MetadataDocument document = MetadataDocument.read(stored.get().content());
            Optional<PublishedSection.Facts> facts = PublishedSection.facts(document.section(PublishedSection.TAG));
            if (facts.isEmpty() || facts.get().at() == null) {
                return;
            }
            visitor.accept(new StoreRepositoryInventory.Member(segments[0],
                    StoreRepositoryInventory.decode(segments[1]), segments[2], facts.get().at(),
                    InventoryRecording.declaredIn(document)));
        });
    }

    /** One bounded page of a coordinate's versions - see {@link StoreRepositoryInventory#versions(String, String,
     *  String, int)}. */
    StoreRepositoryInventory.ReleasePage versions(String ecosystem, String coordinate, String after, int limit)
            throws IOException {
        String prefix = StoreRepositoryInventory.publishedRoot() + "/"
                + ArtifactStore.segment(ecosystem) + "/" + StoreRepositoryInventory.encode(coordinate);
        List<String> names = new ArrayList<>();
        store.page(prefix, after == null ? "" : after, ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> window = more ? names.subList(0, limit) : names;
        List<Release> releases = new ArrayList<>(window.size());
        for (String version : window) {
            Release release = release(prefix + "/" + version);
            if (release != null) {
                releases.add(release);
            }
        }
        return new StoreRepositoryInventory.ReleasePage(releases, more ? window.getLast() : null);
    }

    /** The most recently published releases - see {@link StoreRepositoryInventory#recent}. */
    StoreRepositoryInventory.ReleasePage recent(String after, int limit) throws IOException {
        RecentReleases.Page page = RecentReleases.page(store, after, limit);
        List<Release> releases = new ArrayList<>(page.entries().size());
        for (RecentReleases.Entry entry : page.entries()) {
            Release release = release(StoreRepositoryInventory.publishedRoot() + "/"
                    + ArtifactStore.segment(entry.ecosystem()) + "/" + StoreRepositoryInventory.encode(entry.coordinate())
                    + "/" + entry.version());
            if (release != null) {
                releases.add(release);
            }
        }
        return new StoreRepositoryInventory.ReleasePage(releases, page.next());
    }

    /** The published releases of a single coordinate - see {@link StoreRepositoryInventory#versions}. */
    List<Release> versions(String ecosystem, String coordinate) throws IOException {
        List<Release> releases = new ArrayList<>();
        String prefix = StoreRepositoryInventory.publishedRoot() + "/"
                + ArtifactStore.segment(ecosystem) + "/" + StoreRepositoryInventory.encode(coordinate);
        for (String version : store.list(prefix)) {
            Release release = release(prefix + "/" + version);
            if (release != null) {                           // a non-member document (licenses-only, @coordinate) is skipped
                releases.add(release);
            }
        }
        return releases;
    }

    /** The content hashes a version's blobs-namespace pointers resolve to - see
     *  {@link StoreRepositoryInventory#blobHashes}. */
    List<String> blobHashes(String ecosystem, String coordinate, String version) throws IOException {
        List<BlobLayout> blobLayouts = StoreRepositoryInventory.blobLayoutsFor(ecosystem);
        if (blobLayouts.isEmpty()) {
            return List.of();
        }
        // The bare-hex resolution that lived here (resolve blobKeys, keep bare-hex pointer bodies) is now the
        // BlobLayout.blobHashes default, so a blobs-namespace format whose digests are NOT bare-hex pointer bodies (OCI:
        // sha256:-prefixed tag pointer, config/layer digests inside the manifest JSON) can override it to derive its own
        // set. Behaviour is byte-identical for every bare-hex format (npm/PyPI/NuGet/RubyGems/Debian/Go).
        List<String> hashes = new ArrayList<>();
        for (BlobLayout blobLayout : blobLayouts) {
            hashes.addAll(blobLayout.blobHashes(coordinate, version, store));
        }
        return List.copyOf(hashes);
    }

    /** Whether this ecosystem serves its artifacts from the shared {@code blobs/} namespace - see
     *  {@link StoreRepositoryInventory#servesFromBlobs}. */
    boolean servesFromBlobs(String ecosystem) {
        return !StoreRepositoryInventory.blobLayoutsFor(ecosystem).isEmpty();
    }
}
