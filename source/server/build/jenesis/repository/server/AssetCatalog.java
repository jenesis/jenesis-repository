package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.ComposedLayout;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.PublishedAssets;

/**
 * A stably-ordered, resumable walk of a repository's published assets, layering format/coordinate enrichment over the
 * shared {@link PublishedAssets} pointer-tree walk ({@code publish/<request-path> -> <sha256>}) - the read-side of the
 * free {@code /api/assets} enumeration and the outbound mirror of the import connectors. The walk itself (its
 * depth-first ordering, its quarantine exclusion, its withheld-pointer skipping, its pointer-only metadata read) lives
 * once in {@link PublishedAssets}; this catalogue adds only the owning format's name and neutral coordinate to each
 * emitted {@link PublishedAssets.Entry}, through the format's {@link ArtifactLayout#describe describe} -
 * <strong>no artifact blob is ever opened</strong>, in keeping with the read-first bias.
 *
 * <p>The order and cursor semantics are {@link PublishedAssets}': a depth-first walk of the pointer tree where the
 * {@code '/'} separator sorts below every other character, and a page is resumed by an opaque cursor (the last emitted
 * asset's path). A page is a bounded slice of pointer metadata, the only full materialization the streaming principle
 * allows.
 *
 * <p>A format that keeps its own key space - every {@link BlobLayout}: npm, PyPI, NuGet, Debian, RPM and the rest -
 * stores no {@code publish/} pointer for what it serves, so the pointer walk does not see it. Once that walk is
 * exhausted, a repository holding a release of such an ecosystem is listed a second way: its releases, newest first
 * through the inventory's paged {@link StoreRepositoryInventory#recent recent} face, each version's served paths
 * through {@link StoreRepositoryInventory#paths paths}, or what a {@link ComposedLayout} says a copy of it lays
 * down, and each path's hash and size off the serving key the owning layout names ({@link BlobLayout#servingKey}) -
 * pointers only, no blob opened. A page of that leg holds the files of at most {@code limit} versions, so it may carry
 * more than {@code limit} assets when a version has several files. A path the pointer walk already listed, or one a
 * hold withholds, is left out.
 */
public final class AssetCatalog {

    /** A cursor resuming the pointer walk, past the path that follows it. */
    private static final String POINTERS = "p:";

    /** A cursor resuming the releases of the blobs-namespace formats, from the inventory key that follows it - empty
     *  for the first release. */
    private static final String RELEASES = "r:";

    private final ArtifactStore store;
    private final PublishedAssets assets;
    private final Publication publication;
    private final ServableNames names;
    private StoreRepositoryInventory inventory;
    private final Function<String, Optional<RepositoryFormat>> owner;
    private final List<BlobLayout> blobLayouts;

    /**
     * @param store the doubly-scoped ({@code root.scope(tenant).scope(repository)}) artifact space to enumerate.
     * @param owner resolves a request path to the format that owns it (typically {@link FormatDispatcher#owner}),
     *              used to label each asset with its format name and neutral coordinate.
     * @param installed the formats this deployment serves, whose blobs-namespace layouts name the serving key of a
     *              path the pointer walk cannot see.
     */
    public AssetCatalog(ArtifactStore store, Function<String, Optional<RepositoryFormat>> owner,
                        List<RepositoryFormat> installed) {
        this.store = store;
        this.publication = new Publication(store);
        this.assets = new PublishedAssets(store, publication);
        this.names = new ServableNames(store, publication);
        this.owner = owner;
        this.blobLayouts = installed.stream()
                .filter(BlobLayout.class::isInstance).map(BlobLayout.class::cast).toList();
    }

    /** One enumerated asset: its serving request path (leading slash), stored size and SHA-256 straight from the
     *  publication pointer, its owning format name, and - when the format exposes a coordinate layout - its neutral
     *  ecosystem/coordinate/version and prerelease flag ({@code null}/{@code false} for a coordinate-less format
     *  such as raw). */
    public record Asset(String path,
                        long size,
                        String sha256,
                        String format,
                        String ecosystem,
                        String coordinate,
                        String version,
                        boolean prerelease) {
    }

    /** A bounded page of assets plus the cursor to resume after the last one, or {@code null} when the walk is
     *  exhausted (the terminal signal an importer loops until). */
    public record Page(List<Asset> assets, String cursor) {
    }

    /**
     * The next page after {@code cursor} (a previous page's, or {@code null} to start), holding at most {@code limit}
     * assets of the pointer walk or the files of at most {@code limit} versions of the blobs-namespace leg. A page
     * carries a non-null {@link Page#cursor()} while there may be more - possibly an empty page, when a run of releases
     * served nothing from blobs - and {@code null} once both are exhausted.
     *
     * @throws IllegalArgumentException for a cursor no page of this catalogue handed out
     */
    public Page page(String cursor, int limit) throws IOException {
        if (cursor != null && cursor.startsWith(RELEASES)) {
            return releases(cursor.substring(RELEASES.length()), limit);
        }
        if (cursor != null && !cursor.startsWith(POINTERS)) {
            throw new IllegalArgumentException("not a cursor of this catalogue: " + cursor);
        }
        List<Asset> page = new ArrayList<>();
        // Ask the shared walk for one extra to learn whether a further page exists without a second walk: > limit
        // means there is a next cursor, otherwise the walk is exhausted. The cursor is the relative path (no leading
        // slash) the walk resumes strictly past.
        assets.walk(cursor == null ? null : cursor.substring(POINTERS.length()), ArtifactStore.oneMoreThan(limit),
                entry -> page.add(enrich(entry)));
        if (page.size() > limit) {
            Asset last = page.get(limit - 1);
            return new Page(List.copyOf(page.subList(0, limit)), POINTERS + last.path().substring(1));
        }
        if (!holdsBlobReleases()) {
            return new Page(List.copyOf(page), null);
        }
        return page.isEmpty() ? releases("", limit) : new Page(List.copyOf(page), RELEASES);
    }

    /** Whether the repository holds a release of an ecosystem served from the blobs namespace - its handful of
     *  ecosystem names, read once at the end of the pointer walk so a repository of none pays no release listing. */
    private boolean holdsBlobReleases() throws IOException {
        if (blobLayouts.isEmpty()) {
            return false;
        }
        for (String ecosystem : inventory().ecosystems()) {
            if (inventory().servesFromBlobs(ecosystem)) {
                return true;
            }
        }
        return false;
    }

    /** One page of releases after {@code after} ({@code ""} for the first), each version of a blobs-namespace
     *  ecosystem listed as the files it serves. */
    private Page releases(String after, int limit) throws IOException {
        StoreRepositoryInventory.ReleasePage releases = inventory().recent(after.isEmpty() ? null : after, limit);
        List<Asset> page = new ArrayList<>();
        for (Release release : releases.releases()) {
            if (!inventory().servesFromBlobs(release.ecosystem())) {
                continue;
            }
            for (String path : contents(release)) {
                served(release, path).ifPresent(page::add);
            }
        }
        return new Page(List.copyOf(page), releases.next() == null ? null : RELEASES + releases.next());
    }

    /** The paths a release is made of: what a composed layout of its ecosystem says a copy lays down, in order,
     *  else the paths it is served at. */
    private List<String> contents(Release release) throws IOException {
        for (BlobLayout layout : blobLayouts) {
            if (layout instanceof ComposedLayout composed && layout.ecosystem().equals(release.ecosystem())) {
                return composed.contents(release.coordinate(), release.version(), store);
            }
        }
        return inventory().paths(release.ecosystem(), release.coordinate(), release.version());
    }

    /** The repository's inventory, made only once a blobs-namespace layout is installed to ask it about. */
    private StoreRepositoryInventory inventory() {
        if (inventory == null) {
            inventory = new StoreRepositoryInventory(store);
        }
        return inventory;
    }

    /** A served path of a blobs-namespace release as an asset, or empty when the pointer walk lists it already,
     *  a hold withholds it, or no layout of the release's ecosystem serves anything there. */
    private Optional<Asset> served(Release release, String path) throws IOException {
        if (publication.blob(path).isPresent() || names.heldByChain(path)) {
            return Optional.empty();
        }
        for (BlobLayout layout : blobLayouts) {
            if (!layout.ecosystem().equals(release.ecosystem())) {
                continue;
            }
            Optional<String> key = layout.servingKey(path, store);
            if (key.isEmpty()) {
                continue;
            }
            Optional<Blobs.Located> located = new Blobs(store).locate(key.get());
            if (located.isEmpty()) {
                return Optional.empty();
            }
            String hash = located.get().hash();
            long size = located.get().size() >= 0 ? located.get().size() : store.size("blobs/" + hash);
            return Optional.of(new Asset(path, size, hash, owner.apply(path).map(RepositoryFormat::name).orElse(null),
                    release.ecosystem(), release.coordinate(), release.version(), release.prerelease()));
        }
        return Optional.empty();
    }

    /** Label a walked pointer with its owning format's name and - when the format lays out a coordinate - its neutral
     *  ecosystem/coordinate/version, the only enrichment this catalogue adds over the store-level walk. */
    private Asset enrich(PublishedAssets.Entry entry) {
        String requestPath = entry.path();
        Optional<RepositoryFormat> format = owner.apply(requestPath);
        Optional<ArtifactDescriptor> descriptor = format
                .filter(ArtifactLayout.class::isInstance)
                .flatMap(layout -> ((ArtifactLayout) layout).describe(requestPath));
        return new Asset(requestPath,
                entry.size(),
                entry.sha256(),
                format.map(RepositoryFormat::name).orElse(null),
                descriptor.map(ArtifactDescriptor::ecosystem).orElse(null),
                descriptor.map(ArtifactDescriptor::coordinate).orElse(null),
                descriptor.map(ArtifactDescriptor::version).orElse(null),
                descriptor.map(ArtifactDescriptor::prerelease).orElse(false));
    }
}
