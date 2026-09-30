package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.ProvenanceSignerProvider;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gateway.HardenedScreen;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.SignatureSummaries;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.search.LicenseFacet;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.search.service.RepositorySearch;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's generic read over a repository's published pointer tree, scoped to the signed-in tenant: the
 * breadcrumbed browse tree and per-artifact detail, coordinate search, the license inventory and the read-only
 * published-index card - every read served from small pointer, sidecar and roll-up objects, never an artifact blob.
 */
public class RepositoryBrowse extends TenantScope {

    /** The reserved top-level subtree under {@code publish/} that holds the artifacts the compliance gate is
     *  withholding for review: a GET does not serve them and the {@code /assets} export never walks them, so the
     *  console's browse hides it too. Mirrors the {@code ServableNames.QUARANTINE} confinement, kept in step so
     *  the paid console discloses exactly the paths a GET would - never the held-artifact review subtree. */
    private static final String QUARANTINE = "quarantine";

    /** The store key the {@code index} module commits its published-index descriptor to (compare-and-set, so replicas
     *  converge). The console reads only this one small line-oriented object for the read-only index card - never a
     *  chunk and never an artifact blob - so it summarises the index without requiring the heavy {@code index} module
     *  (which carries the zstd chunk codec); a deployment without that module never wrote this object, so the card
     *  degrades to "no index published yet". */
    /** The published index's descriptor key, from the module both the writer and this reader can see. A
     *  literal here would render "no index published yet" if the writer ever moved it - wrong, and quiet. */
    private static final String INDEX_DESCRIPTOR = PublishedIndexKeys.DESCRIPTOR;

    /** The most immediate children a single browse level pages through the store, so a coordinate with an enormous
     *  fan-out (hundreds of thousands of timestamped versions) is navigated into, not materialised whole in heap per
     *  browse request - the same bound the console tree and {@code /api/browse} apply. */
    private static final int MAX_CHILDREN = 1000;

    /** The repository's one search, resolved once so the index's per-repository searchers survive across requests. */
    private final RepositorySearch search;

    public RepositoryBrowse(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        this(repositoryStore, current, observations, SearchQueryProvider.installed());
    }

    /** Embedding/test seam: bind an explicit full-text index provider (empty for none) rather than discovering it
     *  through {@link SearchQueryProvider#installed()}, so a caller can drive both modes without a ServiceLoader
     *  registration. */
    public RepositoryBrowse(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                            Optional<SearchQueryProvider> index) {
        super(repositoryStore, current, observations);
        this.search = new RepositorySearch(index);
    }

    /** The immediate entries under a path in a repository's layout, for navigating the tree. */
    public List<String> browse(String repository, String prefix) throws IOException {
        return inventory(repository).children(prefix);
    }

    /** One entry in the generic browse tree: an immediate child under a prefix, classified folder-vs-artifact and
     *  sized. A folder carries its cached rolled-up subtree size (the sum of the blob sizes published beneath it,
     *  read from the one small roll-up object, never by walking the tree); an artifact leaf carries its stored
     *  blob's recorded size - read from the small pointer, never the artifact body. {@code bytes} is the raw count the
     *  browse sorts the Size column on ({@code -1} when unknown - a folder with no roll-up computed yet, or a pointer
     *  naming no present blob); {@code size} is that count rendered human-readable ({@code "—"} when unknown). */
    public record BrowseEntry(String name, String path, boolean folder, long bytes, String size) {
    }

    /** The console's artifact detail, generic across formats: the request path and whether a blob is currently
     *  published there, the content-addressed SHA-256 the blob is stored under (its checksum - read from the small
     *  pointer, never the artifact body) and its stored size (human-readable, plus the raw byte count for a machine
     *  reader), the format-neutral ecosystem/coordinate/version the owning format's {@code ArtifactLayout} describes
     *  for the path (blank when the path carries no coordinate - a checksum, generated metadata, a raw file), when
     *  that coordinate version was published, the other versions of the coordinate this repository holds, any
     *  compliance gate verdict recorded against the path ({@code null} when the gate never held it), and whether a
     *  provenance attestation can be served for it. */
    /** {@code versions} holds the coordinate's first {@link #VERSIONS_PAGE} versions by name, shown newest first
     *  (the current one always among them); {@code moreVersions} says the coordinate has more, which its own page
     *  lists by cursor. */
    public record ArtifactDetail(String path, boolean present, String hash, long sizeBytes, String size,
                                 String ecosystem, String coordinate, String version, String published,
                                 List<VersionRow> versions, boolean moreVersions, VerdictRow quarantine,
                                 boolean provenance, SignatureRow signature, InspectionRow inspection, Long downloads,
                                 String lastDownloaded) {
    }

    /**
     * What a publisher's signature on this version turned out to be: the outcome, who signed it, the quality grade,
     * and where the material sat. {@code null} when the version carries no recorded signature at all - a version
     * published before signatures were checked, or a format that has none - which the page states rather than
     * rendering as a failure.
     *
     * <p>Read from the stored summary, never re-derived: the page does not re-verify anything, because a screen that
     * ran cryptography on render would cost more the more it is looked at and would disagree with the gate the moment
     * a key changed.
     */
    public record SignatureRow(String outcome, String signer, String grade, String location, String source,
                               String admittedBy, String issuer, String subject, String link, String logIndex,
                               String integratedTime) {

        /** The row the recorded summary renders as: the signer's issuer and subject apart where the record kept
         *  them, the subject linked where it is a location, the log entry where the bundle carried one. */
        static SignatureRow of(SignatureSection.Summary summary) {
            String subject = summary.details().get("subject");
            return new SignatureRow(summary.outcome(), summary.signer(), summary.grade(), summary.location(),
                    summary.source(), summary.admittedBy(), summary.details().get("issuer"), subject,
                    subject != null && linkable(subject) ? subject : null, summary.details().get("log-index"),
                    summary.details().get("integrated-time"));
        }

        /** Whether this reads as good news - the only outcome an operator need not look at. */
        public boolean trusted() {
            return "VALID".equals(outcome);
        }

        /** Whether this is the outcome that means the bytes do not match what was signed, which is the one worth
         *  ranking above the rest on a screen. */
        public boolean invalid() {
            return "INVALID".equals(outcome);
        }
    }

    /** The versions one page of a coordinate lists. */
    public static final int VERSIONS_PAGE = 100;

    /** The quality-inspection subsection's scoped error state: a {@link Finding.Kind#INSPECTION} finding recorded
     *  against this coordinate when its quality inspector could not parse the artifact, so the detail view renders a
     *  scoped "could not fully screen this - not fully screened" panel for just this subsection while every other
     *  panel renders normally. {@code null} when the coordinate carries no such finding (the ordinary case). */
    public record InspectionRow(String reason, String when) {
    }

    /** One published version of the detail artifact's coordinate: its version, publish time, whether it is pinned, and
     *  whether it is the version being viewed. */
    public record VersionRow(String version, String published, boolean pinned, boolean current, Long downloads,
                             String lastDownloaded) {
    }

    /**
     * One coordinate of one ecosystem and every version of it this repository holds: the screen a coordinate has of
     * its own, whichever way its format stores. A format under the published tree reaches it from the browse tree
     * as well; a format in a blobs namespace of its own - npm, PyPI, NuGet and their kind - has no folder in that
     * tree, so this is where a search hit or a release row for it lands. {@code location} is the browse folder where
     * a tree format lays the coordinate's newest version out, empty for a blobs-namespace format.
     */
    /** One page of a coordinate's versions, newest first within the page; {@code next} resumes after it in name
     *  order, null on the last page. */
    public record CoordinateDetail(String ecosystem, String coordinate, String location,
                                   List<CoordinateVersion> versions, String next) {
    }

    /**
     * One version of a coordinate: when it was published - or, for a copy cached from an upstream, first cached, with
     * the upstream it came from - whether it is pinned, whether it is currently served (a held or evicted version is
     * listed, greyed, so the history reads whole), and the request paths it is served at - for a tree format each
     * links to its artifact page, for a blobs-namespace format they are the paths a client fetches. A cached copy is
     * never pinned: a pin is a retention decision, and retention keeps to releases.
     */
    public record CoordinateVersion(String version, String published, boolean pinned, boolean served,
                                    List<String> paths, boolean browsable, Long downloads, String lastDownloaded,
                                    boolean cached, String upstream) {

        /** The folder every path lies in, ending in {@code /} - the longest prefix they share, cut back to a folder -
         *  or the empty string when they share none below the root, so a row names it once instead of per file. */
        public String folder() {
            if (paths.isEmpty()) {
                return "";
            }
            String shared = paths.getFirst();
            for (String path : paths) {
                int length = 0;
                while (length < shared.length() && length < path.length()
                        && shared.charAt(length) == path.charAt(length)) {
                    length++;
                }
                shared = shared.substring(0, length);
            }
            int slash = shared.lastIndexOf('/');
            return slash <= 0 ? "" : shared.substring(0, slash + 1);
        }

        /** The paths as the row lists them: each with {@link #folder()} taken off, in the order they are served. */
        public List<ServedFile> files() {
            String folder = folder();
            return paths.stream().map(path -> new ServedFile(path.substring(folder.length()), path)).toList();
        }
    }

    /** One path a version is served at: its {@code name} below the version's shared folder, and the whole path. */
    public record ServedFile(String name, String path) {
    }

    /** A recorded instant as a row shows it, blank when nothing was recorded. */
    private static String stamp(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    /**
     * One {@code origin} acquisition row rendered on the artifact detail and returned by the origin API (over the
     * {@link OriginSection}): where <em>this</em> deployment's bytes for the coordinate version came
     * from - a {@code local-upload} (a hand upload through the publish path) or a {@code fallback} fetch (bytes fetched
     * from an ordered upstream fallback, for both a store and a no-store fallback). A fallback row carries which
     * repository and fallback it arrived through, the upstream {@code target} URL, whether it was {@code stored} (cached
     * vs pass-through), its {@code screening} strength, and the no-copy {@code serves}/{@code lastServed} counters. All
     * fields are rendered strings (an absent instant blank), so the neutral display renders straight from the section.
     */
    public record OriginRow(String source, String repository, int fallbackIndex, String target, String at,
                            String sha256, boolean stored, String screening, String lastServed, long serves) {

        /** Whether this row is a hand upload (the system-of-record channel). */
        public boolean upload() {
            return OriginSection.LOCAL_UPLOAD.equals(source);
        }

        /** Whether this row is a fallback fetch (uploaded vs via which fallback - the browse-row provenance). */
        public boolean fallback() {
            return OriginSection.FALLBACK.equals(source);
        }
    }

    /** A compliance gate decision the quarantine log recorded against an artifact path: the verdict, the reasons the
     *  gate gave, and when it was taken. */
    public record VerdictRow(String verdict, List<String> reasons, String when) {
    }

    /** The browse level under a prefix in the default order (child name, ascending) - the overload the plain page and
     *  the lazy-children fragment call when no explicit sort is chosen. */
    public List<BrowseEntry> browseTree(String repository, String prefix) throws IOException {
        return browseTree(repository, prefix, "name", false);
    }

    /** The immediate children under a browse prefix, each classified folder-vs-artifact with a size and ordered by the
     *  chosen column - the one lazy level of the console's breadcrumbed tree (the caller re-invokes this per navigation
     *  or expand, so a browse never scans or buffers a whole tree). A folder's size is its cached rolled-up subtree
     *  total (a single small roll-up read, populated by the retention sweep, {@code "—"} until then); an
     *  artifact leaf's is its stored blob's recorded size. Reads only the {@code publish/} pointer tree, a folder's
     *  roll-up object and a referenced blob's recorded size, never an artifact blob. {@code sort} is one of
     *  {@code name}/{@code type}/{@code size} and {@code descending} the direction - so the Size column sorts on the
     *  raw byte count, not the rendered string. The prefix is {@link #safePrefix traversal-guarded} so a
     *  request can never escape the {@code publish/} subtree to enumerate the content-addressed {@code blobs/}
     *  bucket. */
    public List<BrowseEntry> browseTree(String repository, String prefix, String sort, boolean descending)
            throws IOException {
        return browseLevel(repository, prefix, sort, descending).entries();
    }

    /** One folder level as the browse screen shows it: at most {@link #MAX_CHILDREN} entries, and whether the folder
     *  holds more than that - the bound is shown, never silently applied. */
    public record BrowseLevel(List<BrowseEntry> entries, boolean truncated) {
    }

    public BrowseLevel browseLevel(String repository, String prefix, String sort, boolean descending)
            throws IOException {
        BrowsePage page = page(scope(repository), safePrefix(prefix), null, MAX_CHILDREN);
        List<BrowseEntry> entries = new ArrayList<>(page.entries());
        entries.sort(browseOrder(sort, descending));
        return new BrowseLevel(entries, page.next() != null);
    }

    /** One window of a folder level in child-name order: at most the requested number of entries, and {@code next},
     *  the child name the following window resumes after - {@code null} once the level is drained. */
    public record BrowsePage(List<BrowseEntry> entries, String next) {
    }

    /**
     * One window of the immediate children under {@code safe} - a {@link #safePrefix traversal-guarded} layout prefix
     * of the repository {@code store} - resumed strictly after the child named {@code after}, each classified
     * folder-vs-artifact with its size. The one implementation behind the console's folder screen and its
     * {@code /api/browse/children} twin, so the two answer the same children for one folder.
     *
     * <p>It goes through the servable-name seam's paged, screened child listing: that pages one bounded level,
     * forwards folder children unconditionally, suppresses the reserved quarantine review subtree at the root, and
     * drops any non-folder leaf a GET would 404 (withheld, retracted, or a blob a garbage collection reclaimed) - the
     * serve-parity screen, so a browse discloses exactly the paths a GET would. Per child it reads a one-entry folder
     * probe and either a folder's cached roll-up or a leaf's recorded size, never an artifact body.
     */
    public static BrowsePage page(ArtifactStore store, String safe, String after, int limit) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        StoreRepositoryInventory.ChildPage page =
                inventory.children(safe, after, limit, ServableNames.Policy.HIDE_WITHHELD_AND_GONE);
        List<BrowseEntry> entries = new ArrayList<>();
        for (String name : page.names()) {
            String path = safe + "/" + name;
            boolean folder = !inventory.children(path, 1).isEmpty();   // a one-entry probe, never the child listing
            long bytes;
            if (folder) {
                bytes = inventory.subtreeSize(path).orElse(-1L);      // a folder is a listing, kept unconditionally
            } else {
                // The leaf survived the screen, so a GET would serve it: read its recorded size from the small
                // pointer (never the artifact body); a torn pointer that raced the screen degrades to unknown, not a 500.
                Optional<String> located = publication.located(path);
                bytes = located.isPresent() ? store.size(located.get()) : -1L;
            }
            entries.add(new BrowseEntry(name, path, folder, bytes, bytes < 0 ? "—" : humanSize(bytes)));
        }
        return new BrowsePage(entries, page.next());
    }

    /**
     * A coordinate's own screen: every version of it this repository holds - published here or cached from an
     * upstream - newest first, with the paths each is served at. Reads the version documents and the tiny pointers
     * only, never an artifact blob.
     */
    public CoordinateDetail coordinate(String repository, String ecosystem, String coordinate) throws IOException {
        return coordinate(repository, ecosystem, coordinate, null, VERSIONS_PAGE);
    }

    /** One page of the coordinate's versions - the page is cut in name order from {@code after} and shown newest
     *  first; {@code location} is the browse folder of the page's most recently published browsable version. */
    public CoordinateDetail coordinate(String repository, String ecosystem, String coordinate, String after,
                                       int limit) throws IOException {
        ArtifactStore store = scope(repository);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        StoreRepositoryInventory.HoldingPage page = inventory.holdings(ecosystem, coordinate, after,
                Math.max(1, Math.min(limit, VERSIONS_PAGE)));
        List<CoordinateVersion> versions = new ArrayList<>();
        CoordinateVersion newest = null;
        for (StoreRepositoryInventory.Holding holding : page.holdings()) {
            String when = holding.at() == null ? "" : holding.at().toString();
            boolean served = inventory.disclosable(ecosystem, coordinate, holding.version(),
                    ServableNames.Policy.HIDE_WITHHELD_AND_GONE);
            boolean browsable = !inventory.locate(ecosystem, coordinate, holding.version()).isEmpty();
            CoordinateVersion row = new CoordinateVersion(holding.version(), when, holding.pinned(), served,
                    inventory.paths(ecosystem, coordinate, holding.version()), browsable, holding.downloads(),
                    stamp(holding.downloadedAt()), holding.cached(), holding.upstream());
            versions.add(row);
            if (browsable && (newest == null || row.published().compareTo(newest.published()) > 0)) {
                newest = row;
            }
        }
        versions.sort(Comparator.comparing(CoordinateVersion::published).reversed()
                .thenComparing(CoordinateVersion::version));
        String location = newest == null ? ""
                : safePrefix(inventory.locateHeld(ecosystem, coordinate, newest.version()));
        return new CoordinateDetail(ecosystem, coordinate, location, versions, page.next());
    }

    /**
     * The detail of one published artifact path, generic across every format. Reads only small objects - the
     * {@code publish/} pointer (whose value is the artifact's content-addressed SHA-256 checksum), the referenced
     * blob's recorded size, the format-neutral coordinate the owning format's {@code ArtifactLayout} describes for
     * the path, the publish-time sidecars, and the quarantine log - never the artifact blob itself, so a browse into
     * an arbitrarily large artifact's detail costs the same bounded read. The {@code path} is
     * {@link #safePrefix traversal-guarded} so a request can never escape the {@code publish/} subtree to name a raw
     * content-addressed blob. Coordinate, version, the other versions of the coordinate and the publish date are
     * present only for a path a descriptive format claims (a raw file resolves to its checksum and size alone); the
     * quarantine verdict is the most recent gate decision recorded against the path, and the provenance flag reports
     * whether a signer is configured to attest it.
     */
    public ArtifactDetail artifact(String repository, String path) throws IOException {
        String safe = safePrefix(path);
        ArtifactStore store = scope(repository);
        Publication publication = new Publication(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<String> located = publication.located(safe);
        String hash = publication.blob(safe).orElse("");
        long sizeBytes = located.isPresent() ? store.size(located.get()) : -1L;
        Optional<ArtifactDescriptor> descriptor = inventory.describe(safe);
        String ecosystem = descriptor.map(ArtifactDescriptor::ecosystem).filter(Objects::nonNull).orElse("");
        String coordinate = descriptor.map(ArtifactDescriptor::coordinate).filter(Objects::nonNull).orElse("");
        String version = descriptor.map(ArtifactDescriptor::version).filter(Objects::nonNull).orElse("");
        String published = "";
        Long downloads = null;
        String lastDownloaded = "";
        List<VersionRow> versions = new ArrayList<>();
        boolean moreVersions = false;
        if (!coordinate.isEmpty()) {
            // The sibling versions are one page of this coordinate's own version folder, never a walk of every
            // release; the current version is read by itself when the page does not reach it.
            StoreRepositoryInventory.ReleasePage page = inventory.versions(ecosystem, coordinate, null, VERSIONS_PAGE);
            moreVersions = page.next() != null;
            boolean seen = false;
            for (Release release : page.releases()) {
                boolean current = version.equals(release.version());
                seen |= current;
                String when = release.published() == null ? "" : release.published().toString();
                if (current) {
                    published = when;
                    downloads = release.downloads();
                    lastDownloaded = stamp(release.downloadedAt());
                }
                versions.add(new VersionRow(release.version(), when, release.pinned(), current, release.downloads(),
                        stamp(release.downloadedAt())));
            }
            if (!seen && !version.isEmpty()) {
                Optional<Release> own = inventory.release(ecosystem, coordinate, version);
                if (own.isPresent()) {
                    published = own.get().published() == null ? "" : own.get().published().toString();
                    downloads = own.get().downloads();
                    lastDownloaded = stamp(own.get().downloadedAt());
                    versions.add(new VersionRow(version, published, own.get().pinned(), true, downloads,
                            lastDownloaded));
                }
            }
            versions.sort(Comparator.comparing(VersionRow::published).reversed());
        }
        // The quarantine verdict is a point lookup of the latest-verdict-by-path index, not a scan of the whole log.
        VerdictRow quarantine = new QuarantineLog(store).latest(safe)
                .map(event -> new VerdictRow(event.verdict().name(), event.reasons(), event.when().toString()))
                .orElse(null);
        boolean provenance = ProvenanceSignerProvider.resolve(settings()::getProperty).enabled();
        InspectionRow inspection = inspectionFailure(store, ecosystem, coordinate, version);
        SignatureRow signature = signatureOf(store, ecosystem, coordinate, version);   // same read the API takes
        return new ArtifactDetail(safe, located.isPresent(), hash, sizeBytes,
                sizeBytes < 0 ? "—" : humanSize(sizeBytes), ecosystem, coordinate, version, published,
                versions, moreVersions, quarantine, provenance, signature, inspection, downloads, lastDownloaded);
    }

    /**
     * The {@code origin} acquisition rows for a published artifact path, read from the coordinate
     * version's consolidated metadata document's {@code origin} section ({@link OriginSection}) - a small,
     * bounded read of the one section, never the artifact body. Rows render on the artifact detail and are returned
     * by the origin API, both the neutral display the gate does not consume. Best-effort render-what-you-have: a
     * path with no coordinate/version or a read failure yields an empty list (the panel then states there is no
     * recorded origin), never a failed detail view. The path is {@link #safePrefix traversal-guarded} exactly as
     * {@link #artifact} is.
     */
    public List<OriginRow> origin(String repository, String path) throws IOException {
        return originOf(scope(repository), safePrefix(path));
    }

    /**
     * The merged {@code origin} acquisition rows over one repository-scoped store for an already-{@link #safePrefix
     * traversal-guarded} path - the reusable read behind both the console origin panel/API and the
     * {@code /api/origin} audit-export endpoint, so the two surfaces share one merge rather than diverging. Reads only
     * the two small {@code origin} sections, never the artifact body; best-effort render-what-you-have:
     * anything missing or unreadable yields an empty list, never a thrown error.
     */
    public static List<OriginRow> originOf(ArtifactStore store, String safe) {
        MetadataStore metadata = MetadataProvider.installed().over(store);
        List<OriginRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            // (1) The format-coordinate document: a hand upload records its local-upload origin here, keyed by the
            // coordinate the owning format describes for the path - the key the published/licenses sections and
            // browse.artifact resolve the path to.
            Optional<ArtifactDescriptor> descriptor = new StoreRepositoryInventory(store).describe(safe);
            String ecosystem = descriptor.map(ArtifactDescriptor::ecosystem).filter(Objects::nonNull).orElse("");
            String coordinate = descriptor.map(ArtifactDescriptor::coordinate).filter(Objects::nonNull).orElse("");
            String version = descriptor.map(ArtifactDescriptor::version).filter(Objects::nonNull).orElse("");
            if (!coordinate.isEmpty() && !version.isEmpty()) {
                collectOrigin(metadata.section(ecosystem, coordinate, version, OriginSection.TAG), rows, seen);
            }
            // (2) The path-derived document. A fallback fetch does not record its origin here (one artifact, one
            // origin document, and it is the format-coordinate one above). Still read,
            // for the two cases where it is the only key there is: a path no installed format describes - where
            // HardenedScreen.originCoordinate falls back to this same derivation, so this IS where the row is - and a
            // no-store fallback's row, which survives durably beside transient bytes that never landed.
            // Deduped when the two derivations coincide, which they do for exactly those paths.
            HardenedScreen.Coordinate viaPath = HardenedScreen.coordinate(safe);
            if (!(viaPath.ecosystem().equals(ecosystem) && viaPath.coordinate().equals(coordinate)
                    && viaPath.version().equals(version))) {
                collectOrigin(metadata.section(viaPath.ecosystem(), viaPath.coordinate(), viaPath.version(),
                        OriginSection.TAG), rows, seen);
            }
            return List.copyOf(rows);
        } catch (IOException | RuntimeException _) {
            // Render-what-you-have: a metadata read failure degrades the origin panel to empty rather than failing the
            // whole artifact detail view, the same posture the quarantine/inspection subsections take.
            return List.of();
        }
    }

    /** Append the acquisition rows of one origin section as {@link OriginRow}s, skipping a row already collected from a
     *  sibling document keyed under a different coordinate derivation (the {@code (source, sha256, repository, target)}
     *  identity), so the merged audit view never double-lists the same acquisition. */
    private static void collectOrigin(Optional<Section> section, List<OriginRow> rows, Set<String> seen) {
        for (OriginSection.Acquisition row : OriginSection.acquisitions(section)) {
            String identity = row.source() + '|' + row.sha256() + '|' + row.repository() + '|' + row.target();
            if (!seen.add(identity)) {
                continue;
            }
            rows.add(new OriginRow(row.source(), row.repository(), row.fallbackIndex(), row.target(),
                    row.at() == null ? "" : row.at().toString(),
                    row.sha256() == null ? "" : row.sha256(), row.stored(),
                    row.screening() == null ? "" : row.screening(),
                    row.lastServed() == null ? "" : row.lastServed().toString(), row.serves()));
        }
    }

    /** The quality-inspection subsection's scoped error state for a coordinate: the newest active
     *  {@link Finding.Kind#INSPECTION} finding, recorded when the compliance screen could not parse the artifact (so it
     *  was screened only from its path coordinate). A point lookup of this coordinate's findings - the same bounded
     *  read the quarantine verdict above is - not a ledger scan. Best-effort (render what you have): a
     *  coordinate with no version, an uninstalled findings module, or a read failure yields {@code null}, so the rest
     *  of the detail
     *  view still renders rather than the whole page failing on one subsection's derive. */
    /**
     * The signature summary recorded for this coordinate version, or {@code null} when none was.
     *
     * <p>One bounded point read of the version document's own section - no artifact body, no store walk, no
     * cryptography - so the panel costs the same on a repository holding ten million versions as on one holding ten.
     * Best-effort in the render-what-you-have sense: a read that fails yields no row and the page says there is none,
     * never a failed detail view.
     */
    /** The console's view of the signature, over the one read every surface takes. */
    private static SignatureRow signatureOf(ArtifactStore store, String ecosystem, String coordinate,
                                            String version) {
        return SignatureSummaries.of(store, ecosystem, coordinate, version)
                .map(SignatureRow::of)
                .orElse(null);
    }

    /** Whether a subject is a location a screen may link to: an absolute http(s) URL and nothing else, so an
     *  e-mail address, a service account or anything an attacker could shape stays text. */
    public static boolean linkable(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            URI uri = new URI(subject);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException notALocation) {
            return false;
        }
    }

    private static InspectionRow inspectionFailure(ArtifactStore store, String ecosystem, String coordinate,
                                                   String version) {
        if (coordinate.isEmpty() || version.isEmpty()) {
            return null;
        }
        Optional<FindingsProvider> provider = FindingsProvider.installed();
        if (provider.isEmpty()) {
            return null;
        }
        try {
            Findings ledger = provider.get().over(store);
            Finding latest = null;
            for (Finding finding : ledger.of(ecosystem, coordinate, version)) {
                if (finding.kind() == Finding.Kind.INSPECTION && finding.active()
                        && (latest == null || finding.lastSeen().isAfter(latest.lastSeen()))) {
                    latest = finding;
                }
            }
            return latest == null ? null
                    : new InspectionRow(latest.description(), latest.lastSeen().toString());
        } catch (IOException | RuntimeException _) {
            // Render-what-you-have: a findings read failure degrades this one subsection to no panel rather than
            // failing the whole artifact detail view.
            return null;
        }
    }

    /**
     * Drop every unsafe segment so a browse prefix stays strictly within a repository's {@code publish/} pointer
     * tree: an empty, {@code .} or {@code ..} segment, or one carrying a backslash, is removed rather than allowed to
     * walk up out of the subtree into the content-addressed {@code blobs/} bucket or another key space (the store
     * normalises {@code publish/../blobs} to {@code blobs}, so this guard - not the store - is what keeps the browse
     * confined). The result is a leading-slash path (or {@code ""} for the root), the convention the inventory's
     * {@code children} expects. Domain-owned so both the page and the lazy-children fragment inherit it.
     */
    public static String safePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : prefix.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                continue;
            }
            if (safe.length() == 0 && segment.equals(QUARANTINE)) {
                // A leading "quarantine" segment would navigate into the withheld-artifact review subtree, whose paths
                // and sizes a GET does not serve; drop it (a deeper "quarantine" is a legitimate artifact-path segment
                // and is kept), so a crafted prefix/path cannot enumerate or open held artifacts through the browse.
                continue;
            }
            safe.append('/').append(segment);
        }
        return safe.toString();
    }

    /** The order the browse rows are presented in: by {@code name} (default), {@code type}
     *  (artifact-then-folder) or {@code size} - the raw byte count, so a small artifact sorts below a large one rather
     *  than by a lexical compare of the rendered string, and an unknown size ({@code -1}) sorts lowest - ascending
     *  unless {@code descending}, with the child name the deterministic tiebreak. Kept in the domain so neither the
     *  controller nor the template carries sort logic. */
    private static Comparator<BrowseEntry> browseOrder(String sort, boolean descending) {
        Comparator<BrowseEntry> primary = switch (sort == null ? "" : sort) {
            case "size" -> Comparator.comparingLong(BrowseEntry::bytes);
            case "type" -> Comparator.comparing(BrowseEntry::folder);
            default -> Comparator.comparing(BrowseEntry::name, String.CASE_INSENSITIVE_ORDER);
        };
        if (descending) {
            primary = primary.reversed();
        }
        return primary.thenComparing(BrowseEntry::name, String.CASE_INSENSITIVE_ORDER);
    }

    /** Bytes as a compact human-readable size (the browse size column): binary units, one decimal above a kilobyte. */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** One search hit rendered in the same generic list as the browse: what a person reads for it - a
     *  {@code coordinate:version}, or the path of an artifact with no coordinate - its parts, and the browse folder it
     *  occupies, so the row links into the tree there. The location is blank when no installed format can place the
     *  coordinate, in which case the console links the hit to its coordinate's own page, or shows it inert. */
    public record SearchResult(String display, String coordinate, String version, String ecosystem,
                               String location) {
    }

    /** One page of a search as the search bar shows it: the mode the repository answers in, whether its full-text
     *  index answered this page, the hits, and the cursor to the next page - {@code null} when nothing remains, so
     *  a clamped page never reads as the whole match set. */
    public record SearchPage(SearchMode mode, boolean indexed, List<SearchResult> results, String nextCursor) {

        public SearchPage {
            results = List.copyOf(results);
        }

        /** Whether matches remain past this page. */
        public boolean truncated() {
            return nextCursor != null;
        }
    }

    /** One page of the repository's search - see {@link RepositorySearch} - with each hit placed in the browse tree.
     *  {@code config} is the repository's effective configuration, which decides whether it answers by name or from
     *  its full-text index; {@code cursor} is a previous page's, or {@code null}. */
    public SearchPage search(String repository, UnaryOperator<String> config, String query, String cursor)
            throws IOException {
        RepositorySearch.Answer answer = search.search(scope(repository), tenant() + '/' + repository, config, query,
                cursor, RepositorySearch.PAGE);
        StoreRepositoryInventory inventory = inventory(repository);
        List<SearchResult> results = new ArrayList<>();
        for (SearchQuery.Hit hit : answer.hits()) {
            if (hit.pathAddressed()) {
                int slash = hit.path().lastIndexOf('/');
                results.add(new SearchResult(hit.display(), "", "", "",
                        safePrefix(slash <= 0 ? "" : hit.path().substring(0, slash))));
            } else {
                results.add(new SearchResult(hit.display(), hit.coordinate(), hit.version(), hit.ecosystem(),
                        safePrefix(inventory.locate(hit.ecosystem(), hit.coordinate(), hit.version()))));
            }
        }
        return new SearchPage(answer.mode(), answer.indexed(), results, answer.nextCursor());
    }

    /** The license inventory over a repository for the console screen: the per-category and per-SPDX-id facet counts
     *  its full-text index counted (never a scan of artifact metadata on the request path). Reports
     *  {@code indexed=false} with empty facets when the repository's full-text search is off or its index not built
     *  yet - the same answer {@code /api/licenses} gives - so the screen states it rather than showing a false clean
     *  bill. */
    public LicenseInventory licenses(String repository, UnaryOperator<String> config) throws IOException {
        Optional<List<LicenseFacet>> facets = search.licenses(scope(repository), tenant() + '/' + repository, config);
        if (facets.isEmpty()) {
            return new LicenseInventory(false, List.of(), List.of());
        }
        List<LicenseCount> categories = new ArrayList<>();
        List<LicenseCount> licenses = new ArrayList<>();
        for (LicenseFacet facet : facets.get()) {
            (facet.kind().equals(LicenseFacet.CATEGORY) ? categories : licenses)
                    .add(new LicenseCount(facet.value(), facet.count()));
        }
        return new LicenseInventory(true, categories, licenses);
    }

    /** One license-inventory facet row for the console: a license category or resolved SPDX id and the number of
     *  coordinates in the repository carrying it. Each drills down through the browse search
     *  ({@code ?q=category:<value>} or {@code license:<value>}) to the coordinates behind the count. */
    public record LicenseCount(String value, long count) {
    }

    /** The license inventory the console screen renders: whether the full-text index backed it ({@code false} while
     *  the repository's full-text search is off or its index not built), the per-category counts and the
     *  per-SPDX-id counts. */
    public record LicenseInventory(boolean indexed, List<LicenseCount> categories, List<LicenseCount> licenses) {
    }

    /** The read-only summary of a repository's published index for the console card: the current generation, the
     *  durable high-water mark incremental passes advance, and the chain's chunk count, total record count and total
     *  compressed size - the same facts {@code /api/index} serves, read straight from the descriptor object the
     *  {@code index} pass commits. Reports {@code published=false} when no descriptor has been written yet (the module
     *  absent, or its first pass has not run), so the card states it plainly rather than showing a false empty index.
     *  The descriptor is a small, line-oriented document (see {@code IndexDescriptor}); this reads only its summary
     *  fields, mirroring that stable format so the console need not require the index module and its native codec. */
    public PublishedIndexView publishedIndex(String repository) throws IOException {
        Optional<ArtifactStore.Versioned> descriptor = scope(repository).readVersioned(INDEX_DESCRIPTOR);
        if (descriptor.isEmpty()) {
            return new PublishedIndexView(false, 0, "", 0, 0L, "—");
        }
        int generation = 0;
        String watermark = "";
        int chunks = 0;
        long records = 0L;
        long compressed = 0L;
        for (String line : new String(descriptor.get().content(), StandardCharsets.UTF_8).split("\n")) {
            String[] token = line.trim().split(" ");
            try {
                switch (token[0]) {
                    case "generation" -> {
                        if (token.length >= 2) {
                            generation = Integer.parseInt(token[1]);
                        }
                    }
                    case "watermark" -> {
                        if (token.length >= 2) {
                            watermark = token[1];
                        }
                    }
                    case "chunk" -> {
                        if (token.length >= 5) {
                            // Parse both counters before mutating any total, so a chunk line garbled after its
                            // compressed size does not half-count the chunk (increment the count and compressed but
                            // drop its records).
                            long chunkCompressed = Long.parseLong(token[3]);
                            long chunkRecords = Long.parseLong(token[4]);
                            chunks++;
                            compressed += chunkCompressed;
                            records += chunkRecords;
                        }
                    }
                    default -> { }
                }
            } catch (NumberFormatException malformed) {
                // A torn or garbled descriptor line degrades to no contribution rather than throwing out of the whole
                // read-only card - the same total parse the /api/index reader (IndexDescriptor.parse) carries, so one
                // partially-written descriptor reads as "no usable index" here too instead of a 500, and the next
                // index pass rewrites it.
            }
        }
        return new PublishedIndexView(true, generation, watermark, chunks, records, humanSize(compressed));
    }

    /** The read-only published-index summary the console card renders: whether an index has been published at all, and
     *  when it has, the generation, watermark and the chain's chunk / record / compressed-size totals. */
    public record PublishedIndexView(boolean published, int generation, String watermark, int chunks, long records,
                                     String compressed) {
    }
}
