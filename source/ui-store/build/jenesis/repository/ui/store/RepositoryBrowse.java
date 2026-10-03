package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.ProvenanceSignerProvider;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gateway.HardenedScreen;
import build.jenesis.repository.inventory.AboutSection;
import build.jenesis.repository.inventory.CachedSection;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.DownloadsSection;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.LicenseSection;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.ProvenanceSection;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.SignatureSection;
import build.jenesis.repository.inventory.SignatureSummaries;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.compliance.inventory.LicenseReport;
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

    /** The reserved subtree under {@code publish/} holding what the gate withholds for review, hidden from the browse
     *  as a GET hides it ({@code ServableNames.QUARANTINE}). */
    private static final String QUARANTINE = "quarantine";

    /** The published index's descriptor key, from the module writer and reader share. The index card reads only this
     *  small object, so the console needs no index module; without one the card says no index is published. */
    private static final String INDEX_DESCRIPTOR = PublishedIndexKeys.DESCRIPTOR;

    /** The most children one browse level shows, as {@code /api/browse} bounds it, so a huge fan-out is navigated into
     *  rather than materialised. */
    private static final int MAX_CHILDREN = 1000;

    /** The repository's one search, resolved once so the index's per-repository searchers survive across requests. */
    private final RepositorySearch search;

    public RepositoryBrowse(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        this(repositoryStore, current, observations, SearchQueryProvider.installed());
    }

    /** With an explicit full-text index provider (empty for none) rather than {@link SearchQueryProvider#installed()}. */
    public RepositoryBrowse(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                            Optional<SearchQueryProvider> index) {
        super(repositoryStore, current, observations);
        this.search = new RepositorySearch(index);
    }

    /** The immediate entries under a path in a repository's layout, for navigating the tree. */
    public List<String> browse(String repository, String prefix) throws IOException {
        return inventory(repository).children(prefix);
    }

    /** One entry of the browse tree: a folder with its cached roll-up size, or an artifact with its blob's recorded size.
     *  {@code bytes} is the raw count the Size column sorts on ({@code -1} when unknown), {@code size} its rendering. */
    public record BrowseEntry(String name, String path, boolean folder, long bytes, String size) {
    }

    /** The artifact detail, generic across formats: whether a blob is published at the path, its SHA-256 and size, the
     *  coordinate the owning format describes (blank for none), its publish time, any gate verdict ({@code null} when
     *  never held) and whether provenance can be served. {@code versions} holds the coordinate's first
     *  {@link #VERSIONS_PAGE} versions, the current one among them; {@code moreVersions} says its own page lists more. */
    public record ArtifactDetail(String path, boolean present, String hash, long sizeBytes, String size,
                                 String ecosystem, String coordinate, String version, String published,
                                 List<VersionRow> versions, boolean moreVersions, VerdictRow quarantine,
                                 boolean provenance, SignatureRow signature, InspectionRow inspection, Long downloads,
                                 String lastDownloaded) {
    }

    /**
     * What a publisher's signature on this version turned out to be: the outcome, the signer, the grade and where the
     * material sat, from the stored summary rather than re-verified, so the page agrees with the gate. A version with
     * no recorded signature has none, which the page states.
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

    /** An {@link Finding.Kind#INSPECTION} finding against the coordinate, rendered as a scoped "not fully screened"
     *  panel; {@code null} without one. */
    public record InspectionRow(String reason, String when) {
    }

    /** One published version of the detail artifact's coordinate: its version, publish time, whether it is pinned, and
     *  whether it is the version being viewed. */
    public record VersionRow(String version, String published, boolean pinned, boolean current, Long downloads,
                             String lastDownloaded) {
    }

    /**
     * One page of a coordinate's versions, newest first within the page, for the coordinate's own screen, which a
     * blobs-namespace format (npm, PyPI, NuGet) reaches from a search hit since it has no browse folder.
     * {@code location} is the browse folder of a tree format's newest version, empty otherwise; {@code next} resumes
     * in name order, {@code null} on the last page.
     */
    public record CoordinateDetail(String ecosystem, String coordinate, String location,
                                   List<CoordinateVersion> versions, String next) {
    }

    /**
     * One version of a coordinate: when it was published, or first cached and from which upstream; whether it is pinned
     * (never a cached copy) and served (a held or evicted one is listed greyed); and the request paths it is served at.
     */
    public record CoordinateVersion(String version, String published, boolean pinned, boolean served,
                                    List<String> paths, boolean browsable, Long downloads, String lastDownloaded,
                                    boolean cached, String upstream, FindingsState findings) {

        /** The folder every path lies in - see {@link RepositoryBrowse#folder(List)}. */
        public String folder() {
            return RepositoryBrowse.folder(paths);
        }

        /** The paths as the row lists them - see {@link RepositoryBrowse#files(List)}. */
        public List<ServedFile> files() {
            return RepositoryBrowse.files(paths);
        }
    }

    /**
     * How many findings stand against one version and the worst severity among them; {@link #NONE} where no ledger is
     * installed or readable, which a row shows as nothing rather than clean.
     */
    public record FindingsState(boolean known, int count, String worst) {

        /** No ledger to ask. */
        public static final FindingsState NONE = new FindingsState(false, 0, "");

        static FindingsState of(Optional<Findings> ledger, String ecosystem, String coordinate, String version) {
            if (ledger.isEmpty()) {
                return NONE;
            }
            try {
                int count = 0;
                Severity worst = null;
                for (Finding finding : ledger.get().of(ecosystem, coordinate, version)) {
                    if (finding.active()) {
                        count++;
                        if (worst == null || finding.severity().compareTo(worst) > 0) {
                            worst = finding.severity();
                        }
                    }
                }
                return new FindingsState(true, count, worst == null ? "" : worst.name());
            } catch (IOException | RuntimeException _) {
                return NONE;
            }
        }
    }

    /** The folder every path lies in, ending in {@code /}, or empty when they share none below the root. */
    static String folder(List<String> paths) {
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

    /** The paths as a version lists them: each with {@link #folder(List)} taken off, in the order they are served. */
    static List<ServedFile> files(List<String> paths) {
        String folder = folder(paths);
        return paths.stream().map(path -> new ServedFile(path.substring(folder.length()), path)).toList();
    }

    /** One path a version is served at: its {@code name} below the version's shared folder, and the whole path. */
    public record ServedFile(String name, String path) {
    }

    /** A recorded instant as a row shows it, blank when nothing was recorded. */
    private static String stamp(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    /**
     * One {@link OriginSection} acquisition row: where this deployment's bytes came from, a {@code local-upload} or a
     * {@code fallback} fetch, which carries its repository and fallback, upstream {@code target}, whether it was
     * {@code stored}, its {@code screening} and its {@code serves}/{@code lastServed} counters.
     */
    public record OriginRow(String source, String repository, int fallbackIndex, String target, String at,
                            String sha256, boolean stored, String screening, String lastServed, long serves) {

        /** Whether this row is a hand upload (the system-of-record channel). */
        public boolean upload() {
            return OriginSection.LOCAL_UPLOAD.equals(source);
        }

        /** Whether this row is a fallback fetch. */
        public boolean fallback() {
            return OriginSection.FALLBACK.equals(source);
        }
    }

    /** A compliance gate decision the quarantine log recorded against an artifact path: the verdict, the reasons the
     *  gate gave, and when it was taken. */
    public record VerdictRow(String verdict, List<String> reasons, String when) {
    }

    /** The browse level under a prefix by child name, ascending. */
    public List<BrowseEntry> browseTree(String repository, String prefix) throws IOException {
        return browseTree(repository, prefix, "name", false);
    }

    /** One lazy level of the browse tree under a {@link ServableNames#safePrefix traversal-guarded} prefix, ordered
     *  by {@code sort} ({@code name}, {@code type} or {@code size}). A folder's size is its roll-up, written by the
     *  retention sweep; a leaf's its blob's recorded size. */
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
        BrowsePage page = page(scope(repository), ServableNames.safePrefix(prefix), null, MAX_CHILDREN);
        List<BrowseEntry> entries = new ArrayList<>(page.entries());
        entries.sort(browseOrder(sort, descending));
        return new BrowseLevel(entries, page.next() != null);
    }

    /** One window of a folder level in child-name order: at most the requested number of entries, and {@code next},
     *  the child name the following window resumes after - {@code null} once the level is drained. */
    public record BrowsePage(List<BrowseEntry> entries, String next) {
    }

    /**
     * One window of the children under the {@link ServableNames#safePrefix traversal-guarded} prefix {@code safe},
     * strictly after {@code after}, behind the folder screen and {@code /api/browse/children} alike. The servable-name
     * listing hides the quarantine subtree and any leaf a GET would 404, so a browse discloses exactly what a GET
     * would.
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
                // A pointer torn since the screen reads as unknown size, not a 500.
                Optional<String> located = publication.located(path);
                bytes = located.isPresent() ? store.size(located.get()) : -1L;
            }
            entries.add(new BrowseEntry(name, path, folder, bytes, bytes < 0 ? "—" : humanSize(bytes)));
        }
        return new BrowsePage(entries, page.next());
    }

    /**
     * The first page of a coordinate's versions, published or cached, with the paths each is served at.
     */
    public CoordinateDetail coordinate(String repository, String ecosystem, String coordinate) throws IOException {
        return coordinate(repository, ecosystem, coordinate, null, VERSIONS_PAGE);
    }

    /** One page of the coordinate's versions, cut in name order from {@code after}. */
    public CoordinateDetail coordinate(String repository, String ecosystem, String coordinate, String after,
                                       int limit) throws IOException {
        ArtifactStore store = scope(repository);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        StoreRepositoryInventory.HoldingPage page = inventory.holdings(ecosystem, coordinate, after,
                Math.max(1, Math.min(limit, VERSIONS_PAGE)));
        List<CoordinateVersion> versions = new ArrayList<>();
        CoordinateVersion newest = null;
        Optional<Findings> ledger = FindingsProvider.installed().map(provider -> provider.over(store));
        for (StoreRepositoryInventory.Holding holding : page.holdings()) {
            String when = holding.at() == null ? "" : holding.at().toString();
            boolean served = inventory.disclosable(ecosystem, coordinate, holding.version(),
                    ServableNames.Policy.HIDE_WITHHELD_AND_GONE);
            boolean browsable = !inventory.locate(ecosystem, coordinate, holding.version()).isEmpty();
            CoordinateVersion row = new CoordinateVersion(holding.version(), when, holding.pinned(), served,
                    inventory.paths(ecosystem, coordinate, holding.version()), browsable, holding.downloads(),
                    stamp(holding.downloadedAt()), holding.cached(), holding.upstream(),
                    FindingsState.of(ledger, ecosystem, coordinate, holding.version()));
            versions.add(row);
            if (browsable && (newest == null || row.published().compareTo(newest.published()) > 0)) {
                newest = row;
            }
        }
        versions.sort(Comparator.comparing(CoordinateVersion::published).reversed()
                .thenComparing(CoordinateVersion::version));
        String version = newest == null ? ""
                : ServableNames.safePrefix(inventory.locateHeld(ecosystem, coordinate, newest.version()));
        String location = version.lastIndexOf('/') <= 0 ? "" : version.substring(0, version.lastIndexOf('/'));
        return new CoordinateDetail(ecosystem, coordinate, location, versions, page.next());
    }

    /** The most dependencies a version's page lists; past it the page says how many more there are. */
    public static final int DEPENDENCIES_SHOWN = 200;

    /**
     * Everything this repository records about one version, for its own page, from one read of its document and served
     * pointers; empty for a version with no document.
     */
    public Optional<VersionDetail> version(String repository, String ecosystem, String coordinate, String version)
            throws IOException {
        ArtifactStore store = scope(repository);
        Optional<MetadataDocument> read = MetadataProvider.installed().over(store).read(ecosystem, coordinate, version);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        MetadataDocument document = read.get();
        Optional<PublishedSection.Facts> published = PublishedSection.facts(document.section(PublishedSection.TAG));
        Optional<CachedSection.Facts> cached = CachedSection.facts(document.section(CachedSection.TAG));
        if (published.isEmpty() && cached.isEmpty()) {
            return Optional.empty();
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<DownloadsSection.Facts> downloads = DownloadsSection.facts(document.section(DownloadsSection.TAG));
        Optional<AboutSection.About> about = AboutSection.about(document.section(AboutSection.TAG));
        List<DependencySection.Declared> dependencies = DependencySection.declared(
                document.section(DependencySection.TAG)).orElse(List.of());
        return Optional.of(new VersionDetail(ecosystem, coordinate, version,
                stamp(published.map(PublishedSection.Facts::at).orElse(cached.map(CachedSection.Facts::at).orElse(null))),
                cached.isPresent(), cached.map(CachedSection.Facts::upstream).orElse(null),
                published.map(PublishedSection.Facts::prerelease).orElse(false),
                published.map(PublishedSection.Facts::pinned).orElse(false),
                inventory.disclosable(ecosystem, coordinate, version, ServableNames.Policy.HIDE_WITHHELD_AND_GONE),
                downloads.map(DownloadsSection.Facts::count).orElse(0L),
                stamp(downloads.map(DownloadsSection.Facts::last).orElse(null)),
                about.orElse(null), LicenseSection.declared(document.section(LicenseSection.TAG)),
                SignatureSection.summary(document.section(SignatureSection.TAG)).orElse(null),
                ProvenanceSection.summary(document.section(ProvenanceSection.TAG)).orElse(null),
                dependencies.stream().limit(DEPENDENCIES_SHOWN).toList(), dependencies.size(),
                inventory.paths(ecosystem, coordinate, version),
                !inventory.locate(ecosystem, coordinate, version).isEmpty(),
                FindingsState.of(FindingsProvider.installed().map(provider -> provider.over(store)), ecosystem,
                        coordinate, version)));
    }

    /** One version as its own page shows it - see {@link #version}. {@code about}, {@code signature} and
     *  {@code provenance} are {@code null} where the document records none; {@code dependencies} holds at most
     *  {@link #DEPENDENCIES_SHOWN} of the {@code dependencyCount} declared. */
    public record VersionDetail(String ecosystem, String coordinate, String version, String published, boolean cached,
                                String upstream, boolean prerelease, boolean pinned, boolean served, long downloads,
                                String lastDownloaded, AboutSection.About about,
                                List<LicenseInventory.Declared> licenses, SignatureSection.Summary signature,
                                ProvenanceSection.Summary provenance, List<DependencySection.Declared> dependencies,
                                int dependencyCount, List<String> paths, boolean browsable,
                                FindingsState findings) {

        /** The folder every file lies in - see {@link RepositoryBrowse#folder(List)}. */
        public String folder() {
            return RepositoryBrowse.folder(paths);
        }

        /** The files with {@link #folder()} taken off. */
        public List<ServedFile> files() {
            return RepositoryBrowse.files(paths);
        }

        /** The browse folder the files lie in, or empty where the format keeps no folder tree. */
        public String browseFolder() {
            return browsable ? ServableNames.safePrefix(folder()) : "";
        }
    }

    /**
     * The detail of one artifact path at a {@link ServableNames#safePrefix traversal-guarded} {@code path}, from small
     * objects only: the pointer, the blob's recorded size, the coordinate, the version document and the latest gate
     * verdict. A raw file resolves to its checksum and size alone.
     */
    public ArtifactDetail artifact(String repository, String path) throws IOException {
        String safe = ServableNames.safePrefix(path);
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
            // One page of versions; the current one is read by itself when the page misses it.
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
     * The origin rows of an artifact path at a {@link ServableNames#safePrefix traversal-guarded} {@code path}
     * ({@link #originOf}).
     */
    public List<OriginRow> origin(String repository, String path) throws IOException {
        return originOf(scope(repository), ServableNames.safePrefix(path));
    }

    /**
     * The merged {@link OriginSection} rows of a guarded path in a repository's store, behind the console panel and
     * {@code /api/origin} alike. Best-effort: anything missing or unreadable yields an empty list.
     */
    public static List<OriginRow> originOf(ArtifactStore store, String safe) {
        MetadataStore metadata = MetadataProvider.installed().over(store);
        List<OriginRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            // (1) The document of the coordinate the owning format describes, where a hand upload records its origin.
            Optional<ArtifactDescriptor> descriptor = new StoreRepositoryInventory(store).describe(safe);
            String ecosystem = descriptor.map(ArtifactDescriptor::ecosystem).filter(Objects::nonNull).orElse("");
            String coordinate = descriptor.map(ArtifactDescriptor::coordinate).filter(Objects::nonNull).orElse("");
            String version = descriptor.map(ArtifactDescriptor::version).filter(Objects::nonNull).orElse("");
            if (!coordinate.isEmpty() && !version.isEmpty()) {
                collectOrigin(metadata.section(ecosystem, coordinate, version, OriginSection.TAG), rows, seen);
            }
            // (2) The path-derived document, the only key for a path no installed format describes
            // (HardenedScreen.originCoordinate falls back to it) and for a no-store fallback's row; deduplicated when
            // the two derivations coincide.
            HardenedScreen.Coordinate viaPath = HardenedScreen.coordinate(safe);
            if (!(viaPath.ecosystem().equals(ecosystem) && viaPath.coordinate().equals(coordinate)
                    && viaPath.version().equals(version))) {
                collectOrigin(metadata.section(viaPath.ecosystem(), viaPath.coordinate(), viaPath.version(),
                        OriginSection.TAG), rows, seen);
            }
            return List.copyOf(rows);
        } catch (IOException | RuntimeException _) {
            // A read failure empties the origin panel rather than failing the detail view.
            return List.of();
        }
    }

    /** Appends one section's rows, skipping a {@code (source, sha256, repository, target)} already collected. */
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

    /** The signature summary recorded for this coordinate version, one point read, or {@code null} when none was or the
     *  read failed. */
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

    /** The newest active {@link Finding.Kind#INSPECTION} finding of a coordinate version, one point lookup of its
     *  findings; {@code null} without a version, a findings module, or on a read failure. */
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
            // A read failure drops this subsection rather than failing the detail view.
            return null;
        }
    }

    /** The browse order: by {@code name} (default), {@code type} (artifact then folder) or {@code size} (the raw count,
     *  unknown lowest), with the name as tiebreak. */
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

    /** One search hit: its {@code coordinate:version} or path, its parts, and its browse folder, blank when no installed
     *  format places it. */
    public record SearchResult(String display, String coordinate, String version, String ecosystem,
                               String location) {
    }

    /** One page of a search: the repository's mode, whether the full-text index answered, the hits, and the next
     *  cursor, {@code null} when nothing remains. */
    public record SearchPage(SearchMode mode, boolean indexed, List<SearchResult> results, String nextCursor) {

        public SearchPage {
            results = List.copyOf(results);
        }

        /** Whether matches remain past this page. */
        public boolean truncated() {
            return nextCursor != null;
        }
    }

    /** One page of the repository's {@link RepositorySearch}, each hit placed in the browse tree; {@code config} decides
     *  the mode, {@code cursor} is a previous page's or {@code null}. */
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
                        ServableNames.safePrefix(slash <= 0 ? "" : hit.path().substring(0, slash))));
            } else {
                results.add(new SearchResult(hit.display(), hit.coordinate(), hit.version(), hit.ecosystem(),
                        ServableNames.safePrefix(inventory.locate(hit.ecosystem(), hit.coordinate(), hit.version()))));
            }
        }
        return new SearchPage(answer.mode(), answer.indexed(), results, answer.nextCursor());
    }

    /** The repository's licence inventory as its last count left it, the {@link LicenseReport}
     *  {@code GET /api/licenses} reads; one point read. */
    public LicenseReport.Inventory licenses(String repository) throws IOException {
        return LicenseReport.read(scope(repository));
    }

    /** Start a count of the repository's licences in the background, as {@code GET /api/licenses?refresh=true}
     *  does; answers whether this call started it rather than finding one already running. */
    public boolean countLicenses(String repository) throws IOException {
        return LicenseReport.start(scope(repository));
    }

    /** The published index's summary for the console card - generation, watermark, chunk, record and compressed totals,
     *  as {@code /api/index} serves them - read from the line-oriented descriptor ({@code IndexDescriptor});
     *  {@code published=false} before one exists. */
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
                            // Both counters parse before any total moves, so a garbled line counts nothing.
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
                // A torn line contributes nothing, as IndexDescriptor.parse treats it; the next pass rewrites it.
            }
        }
        return new PublishedIndexView(true, generation, watermark, chunks, records, humanSize(compressed));
    }

    /** The published-index summary the console card renders. */
    public record PublishedIndexView(boolean published, int generation, String watermark, int chunks, long records,
                                     String compressed) {
    }
}
