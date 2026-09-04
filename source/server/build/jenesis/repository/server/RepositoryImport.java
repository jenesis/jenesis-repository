package build.jenesis.repository.server;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Publication;

/**
 * Drives a migration off an incumbent repository manager: it enumerates an {@link ImportSource} and routes each
 * asset to the first {@link RepositoryFormat} that also carries the {@link RepositoryImporter} capability and
 * {@link RepositoryImporter#imports imports} its format, writing it into the content-addressed store so the imported
 * repository serves and indexes it as its own. Formats are discovered with {@link java.util.ServiceLoader} and
 * filtered by {@code instanceof RepositoryImporter} (WSPI.2 (c): the importer is a format capability, not a second
 * discovered service), so the format coverage of an import is simply the set of importing formats on the module path:
 * the core ships Maven, Docker (OCI) and raw with the capability, and another format adds it by implementing the
 * interface. An asset whose format has no importing format is counted as skipped rather than failing the import, so a
 * mixed-format source migrates the formats this deployment understands and reports the rest - the same listing then
 * drives a second pass once those formats are on the path.
 *
 * <p>The import walk is an ingress <em>edge</em> (EPIC 26): it screens each asset before the demoted, layout-only
 * importer lays it out, so a migration off an incumbent lands the same {@link build.jenesis.repository.store.PublishInterceptor}
 * gate a deploy or batch upload passes - the deploy edge ({@link ScreenedDispatch}) and the import edge run the one
 * shared hosted-publish operation {@link Publication#commit}. For each asset the importer
 * {@link RepositoryImporter#importTarget describes} the target coordinate it will occupy; the edge commits the asset
 * against that descriptor, which screens it once and, on {@code ACCEPT}, restreams the stored
 * {@code blobs/<hash>} into {@link RepositoryImporter#importArtifact} then fires {@link Publication#published}. A
 * {@code QUARANTINE} is held (the screen diverted its blob to {@code /quarantine<target-path>}, never laid out) and a
 * {@code REJECT} is skipped; either way the walk continues to the next asset, so one screened-out artifact never
 * aborts a migration. An importer that {@link RepositoryImporter#importTarget describes} nothing (OCI, which owns its own
 * manifest choke point) has its bytes laid out unscreened here. With the core's empty discovered chain the
 * screen degrades to a store-then-restream and an accepted import is byte-for-byte what the pre-edge importer wrote.
 */
public final class RepositoryImport {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RepositoryImport.class);

    private final List<RepositoryImporter> importers;

    public RepositoryImport() {
        this(ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** Filter the discovered (or supplied) formats to those carrying the {@link RepositoryImporter} capability - the
     *  {@code instanceof} split that replaces the second ServiceLoader pass. A base format without the capability is
     *  simply absent from the importer set, so its assets are skipped exactly as a missing importer's were. */
    public RepositoryImport(List<RepositoryFormat> formats) {
        this.importers = formats.stream()
                .filter(format -> format instanceof RepositoryImporter)
                .map(format -> (RepositoryImporter) format)
                .toList();
    }

    /** Import every asset of {@code source} into {@code store}, returning the counts of what was imported and skipped. */
    public Result run(ImportSource source, ArtifactStore store) throws IOException {
        return run(source, store, Listener.NONE);
    }

    /** As {@link #run(ImportSource, ArtifactStore)}, reporting each imported and skipped asset and each resume
     *  checkpoint to {@code listener} - the seam an async job uses to track progress and persist a resume cursor.
     *
     *  <p>Runs under a listing batch: every artifact imported puts its entry into the same few repository-wide
     *  listings (a Debian suite's index, an RPM repository's primary, a channel's repodata, the OCI catalog), and
     *  one publish rewrites such a listing whole - a stated property of {@link StoredListing#update}, a second at a
     *  hundred thousand entries. An import of N packages therefore cost N rewrites of a growing document; under the
     *  batch each listing is written once per ten thousand collected entries instead. */
    public Result run(ImportSource source, ArtifactStore store, Listener listener) throws IOException {
        return StoredListing.batching(() -> runUnbatched(source, store, listener));
    }

    /** The import itself, its listing writes deferred to the batch {@link #run(ImportSource, ArtifactStore, Listener)}
     *  opened. */
    private Result runUnbatched(ImportSource source, ArtifactStore store, Listener listener) throws IOException {
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger held = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Set<String> skippedFormats = new LinkedHashSet<>();
        Map<ImportSource.Reason, Integer> dropped = new EnumMap<>(ImportSource.Reason.class);
        Map<ImportSource.Reason, String> examples = new EnumMap<>(ImportSource.Reason.class);
        source.forEach(new ImportSource.Asset() {

            @Override
            public void dropped(String path, ImportSource.Reason reason) {
                dropped.merge(reason, 1, Integer::sum);
                examples.putIfAbsent(reason, path);
                listener.dropped(path, reason);
            }

            @Override
            public void accept(String format, String path, ImportSource.Content content) throws IOException {
                walk(format, path, content);
            }

            private void walk(String format, String path, ImportSource.Content content) throws IOException {
                // A format configured off (jenreg.<format>=false) imports nothing either - its assets
                // count as skipped, exactly as if its importer module were absent.
                if (Features.enabled(format)) {
                    for (RepositoryImporter importer : importers) {
                        if (importer.imports(format)) {
                            screenAndLayout(importer, path, content, store, imported, held, rejected, listener);
                            return;
                        }
                    }
                }
                skipped.incrementAndGet();
                skippedFormats.add(format);
                listener.skipped(format);
            }
        }, listener::checkpoint);
        // ONE summary line, not one per drop: a hostile listing is a source of unbounded rows, so a line each is a
        // remote party writing as much of our log as it likes. The per-reason tallies with one example carry what an
        // operator acts on, and the example is quoted rather than interpolated bare because it is untrusted text.
        if (!dropped.isEmpty()) {
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<ImportSource.Reason, Integer> entry : dropped.entrySet()) {
                summary.append(summary.isEmpty() ? "" : ", ")
                        .append(entry.getValue()).append(' ').append(entry.getKey())
                        .append(" (e.g. \"").append(examples.get(entry.getKey())).append("\")");
            }
            LOGGER.warn("Import walk refused {} row(s) the source offered and imported none of them: {}. An "
                    + "UNSAFE_PATH count is an attack indicator rather than a broken listing - it means the source "
                    + "offered paths that would have written outside the import's scope.",
                    dropped.values().stream().mapToInt(Integer::intValue).sum(), summary);
        }
        materialiseListings(store);
        return new Result(imported.get(), skipped.get(), held.get(), rejected.get(), Set.copyOf(skippedFormats),
                Map.copyOf(dropped));
    }

    /**
     * Build the listings the imported content implies, before the import is reported as done.
     *
     * <p>An importer that describes a target coordinate publishes through {@link Publication#commit}, which fires
     * {@code published()} once the artifact is laid out, so its listings are maintained as the walk runs. An
     * importer that describes none is laid out unscreened and fires nothing - OCI, which owns its own manifest
     * choke point, and it is the only one - so a migrated registry ends the walk with every tag pointer present
     * and no tag list. The first client request would then generate it inline: measured at 200,000 tags,
     * <b>35 seconds</b> on a request thread, against 186 ms once the document exists.
     *
     * <p>The maintenance pass repairs that within a day, which is right for a store that drifts and wrong for one
     * an operator has just finished migrating and is about to point a build at. Doing it here closes the window to
     * nothing, and costs nothing on a repository whose listings the walk already maintained: each rebuilder
     * creates only what is absent and probes a header to find out.
     *
     * <p>Best-effort by design. The artifacts are imported and durable at this point; a listing that could not be
     * built is a slow first read, not a lost migration, and the pass will build it. Failing the import over it
     * would turn a degraded read into a failed migration.
     */
    private void materialiseListings(ArtifactStore store) {
        List<StoredListing.Rebuilder> rebuilders = new ArrayList<>();
        for (PublicationObserver observer : ServiceLoader.load(PublicationObserver.class)) {
            if (observer instanceof StoredListing.Rebuilder rebuilder) {
                rebuilders.add(rebuilder);
            }
        }
        if (rebuilders.isEmpty()) {
            return;
        }
        int rebuilt = 0;
        for (StoredListing.Rebuilder rebuilder : rebuilders) {
            try {
                // Scope.ALL, and only what this format's own content implies. ALL rather than MISSING because a
                // consumer reading DURING the walk finds the listing absent and generates it inline from the
                // content laid out so far - so by the time the walk ends there is a document that exists and is
                // short, which a probe for absence would skip and leave served. And the format's own walk rather
                // than rebuildAll, which regenerates every listing in the store: a small import into a large
                // repository has no business rewriting listings it never touched.
                rebuilt += rebuilder.materialise(store, StoredListing.Rebuilder.Scope.ALL);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("A listing the imported content implies could not be built; the repair pass builds it, "
                        + "and a read of it meanwhile pays for it or sees it short", e);
            }
        }
        if (rebuilt > 0) {
            LOGGER.info("Import built {} listing(s) the migrated content implies, so no read has to and none is "
                    + "left short by a read that raced the walk", rebuilt);
        }
    }

    /** Screen one walked asset at the import edge, then route it by the chain's verdict: on {@code ACCEPT} restream the
     *  screened blob into the layout-only importer and fire {@link Publication#published}; on {@code QUARANTINE} leave
     *  it held (the screen already diverted its blob to {@code /quarantine<target-path>}); on {@code REJECT} skip it.
     *  An importer that describes no target coordinate (OCI) is laid out from the source stream unscreened. The walk
     *  continues past a held or rejected asset - one screened-out artifact never aborts a migration. */
    private void screenAndLayout(RepositoryImporter importer, String path, ImportSource.Content content,
                                 ArtifactStore store, AtomicInteger imported, AtomicInteger held,
                                 AtomicInteger rejected, Listener listener) throws IOException {
        Optional<ArtifactDescriptor> described = importer.importTarget(path);
        if (described.isEmpty()) {
            // No target coordinate to screen against (OCI owns its own manifest choke point): lay the asset out from
            // the source stream unchanged, exactly as before this edge screened.
            try (InputStream in = content.open()) {
                importer.importArtifact(path, in, store);
            }
            imported.incrementAndGet();
            listener.imported(path);
            return;
        }
        ArtifactDescriptor descriptor = described.get();
        Publication.Commit commit;
        try (InputStream in = content.open()) {
            // The one hosted-publish choreography: commit() stores the body content-addressed as it reads (never
            // buffered whole), runs the chain once over the real target coordinate, restreams the accepted blob into
            // the layout-only importer - never the raw source download, so the importer lays out exactly the bytes the
            // gate saw - and fires published() itself once the importer has laid the artifact out. A QUARANTINE is
            // already diverted to /quarantine<target-path> inside the screen.
            commit = new Publication(store).commit(descriptor, in, Publication.Republish.overwrite(), accepted -> {
                try (InputStream restream = accepted.open()) {
                    importer.importArtifact(path, restream, store);
                }
                // An opaque format-SPI layout: RepositoryImporter.importArtifact links its own serving pointer inside
                // this callback. The ingress census asserts the pointer-last ordering behaviourally for this shape.
                return Publication.Visibility.laidOut();
            });
        }
        switch (commit.disposition()) {
            case ACCEPT -> {
                imported.incrementAndGet();
                listener.imported(path);
            }
            case QUARANTINE -> {
                held.incrementAndGet();
                listener.held(path, descriptor, commit.hash());
            }
            case REJECT -> {
                rejected.incrementAndGet();
                listener.rejected(path, descriptor);
            }
        }
    }

    /** Observes an import as it runs: each asset imported, held, rejected or skipped, and each resume checkpoint (the
     *  cursor to resume from, or {@code null} at the end). The default {@link #NONE} ignores everything, and the
     *  {@code held}/{@code rejected} hooks default no-op so an existing caller is unaffected by the import edge's
     *  screening. */
    public interface Listener {

        Listener NONE = new Listener() {
        };

        /** An asset was imported (screened to {@code ACCEPT} and laid out); {@code path} is the source path (the
         *  coordinate) the walk just reached. */
        default void imported(String path) {
        }

        /** An asset was held: the import edge screened it to {@code QUARANTINE}, so its blob is diverted to
         *  {@code /quarantine<target-path>} for review and never laid out. {@code path} is the source path,
         *  {@code descriptor} the target-layout coordinate it was screened against, {@code hash} its stored blob - the
         *  replay context an edition records so a released hold can be re-driven into the importer later. */
        default void held(String path, ArtifactDescriptor descriptor, String hash) {
        }

        /**
         * A row the connector refused to carry, with the reason - see {@link ImportSource.Asset#dropped}. A default
         * no-op so an existing listener keeps compiling; a job that persists progress overrides it, because the
         * count is what separates "the source was empty" from "the source was refused wholesale" on a status read.
         */
        default void dropped(String path, ImportSource.Reason reason) {
        }

        /** An asset was rejected: the import edge screened it to {@code REJECT}, so nothing was laid out (the orphan
         *  blob is left for garbage collection) and the walk continued. {@code descriptor} is the target coordinate. */
        default void rejected(String path, ArtifactDescriptor descriptor) {
        }

        default void skipped(String format) {
        }

        default void checkpoint(String cursor) throws IOException {
        }
    }

    /** The outcome of an import: how many assets were imported, how many were held (screened to quarantine) and
     *  rejected at the import edge, how many were skipped, and the formats skipped for want of an importer (empty on a
     *  complete import). */
    /**
     * @param dropped rows the connector refused to carry at all, by reason - a laced path, an incomplete listing
     *                entry, an unparseable URL. Empty on a clean source.
     *                <p>It is a component rather than a log line because the number is the whole point: without it a
     *                listing whose every row was refused finished {@code completed, imported: 0, skipped: 0}, which
     *                reads exactly like migrating an empty repository. An operator could not tell "nothing was there"
     *                from "everything was refused", and the second is the one that means the source is hostile.
     */
    public record Result(int imported, int skipped, int held, int rejected, Set<String> skippedFormats,
                         Map<ImportSource.Reason, Integer> dropped) {

        /** The five-component form, for a caller that reports no drops. Delegating rather than replaced, so existing
         *  callers keep compiling - the shape {@code ArtifactDescriptor} took when it absorbed its ninth component. */
        public Result(int imported, int skipped, int held, int rejected, Set<String> skippedFormats) {
            this(imported, skipped, held, rejected, skippedFormats, Map.of());
        }

        /** Every refused row, however it was refused - the single number a status line shows. */
        public int droppedTotal() {
            return dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
