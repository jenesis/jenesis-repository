package build.jenesis.repository.compliance.inventory;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * Resolves a release's declared licenses for the passes that need them - the licence inventory's count and the
 * search index's licence fields - section-first: the publishing gate records what it already extracted as the
 * version's {@code licenses} section on the ACCEPT leg, so the common case is one tiny read and no re-parse. Only when a release has <em>no</em> section - an artifact published before the gate recorded one, or by a
 * path no inspector claims - does it backfill by re-deriving through the same discovered {@link QualityInspector}s the
 * gate uses, over the artifact's own stored metadata (a POM, a {@code .nuspec}, a {@code control} stanza, a packument -
 * the small metadata parse the streaming principle allows, never a large artifact body: a candidate whose blob exceeds
 * {@link #MAX_METADATA_BYTES} is skipped). The backfill derivation stays for those artifacts - it is not written back,
 * so the {@code licenses} section the gate wrote remains the sole authored record and a pre-existing artifact is simply
 * re-derived on each pass (both callers produce derived data, recomputed every time). The declared licenses
 * (name/URL) are resolved to their SPDX id and category through the deployment's {@link LicenseTable} - the built-in
 * rows and the ones an operator configured, handed in by the caller from the settings it read; a release
 * that declares none, or nothing an inspector can read, resolves to the single {@link License#UNKNOWN} so it is
 * counted as unknown rather than vanishing.
 *
 * <p>It lives beside the licence inventory rather than in the search index or the licence policy, because both of
 * its callers reach this module and neither of the others: it reads the inventory's sidecar, the gate's inspectors
 * and the store, and nothing of the policy's or the index's.
 */
public final class LicenseDerivation {

    private static final Logger LOGGER = LoggerFactory.getLogger(LicenseDerivation.class);

    /** The largest stored object the backfill will materialise to re-read metadata from - a guard so a pass never
     *  pulls a large artifact body into the heap; real metadata (a POM, a nuspec-bearing nupkg, a control-bearing deb)
     *  sits far below it, and a release whose only carrier exceeds it simply resolves to unknown. It governs the
     *  <em>candidate</em> read only ("is this carrier small enough to parse at all?"); the siblings an inspector then
     *  reads through {@link QualityInspector.Lookup} carry that seam's own bounds, which are not this number. */
    private static final long MAX_METADATA_BYTES = 16L << 20;

    private final ArtifactStore store;
    private final StoreRepositoryInventory inventory;
    private final LicenseInventory licenses;
    private final Publication publication;
    private final List<QualityInspector> inspectors;
    private final LicenseTable table;
    private final QualityInspector.Lookup siblings = new Siblings();

    /** A derivation over {@code store} resolving declarations through {@code table}. */
    public LicenseDerivation(ArtifactStore store, LicenseTable table) {
        this.store = store;
        this.table = table;
        this.inventory = new StoreRepositoryInventory(store);
        this.licenses = new LicenseInventory(store);
        this.publication = new Publication(store);
        this.inspectors = QualityInspector.all();
    }

    /** The resolved licenses for a release - one {@link License} per declared license, or a single
     *  {@link License#UNKNOWN} when none is declared or derivable. Reads the gate's sidecar when present, backfills
     *  otherwise. */
    public List<License> resolve(Release release) throws IOException {
        return resolve(release, licenses.read(release.ecosystem(), release.coordinate(), release.version()));
    }

    /** {@link #resolve(Release)} over the licences the release's document was already read for - {@code recorded}
     *  empty where it records none - so a caller that read the document for something else pays no second read. */
    public List<License> resolve(Release release, Optional<List<LicenseInventory.Declared>> recorded)
            throws IOException {
        // What the gate recorded is preferred and never re-parsed; only a release without it is backfilled from
        // metadata.
        List<LicenseInventory.Declared> declared = recorded.isPresent() ? recorded.get() : backfill(release);
        if (declared.isEmpty()) {
            return List.of(License.UNKNOWN);
        }
        List<License> resolved = new ArrayList<>();
        for (LicenseInventory.Declared license : declared) {
            resolved.add(table.identify(license.name(), license.url()));
        }
        return resolved;
    }

    /** The already-published-sibling lookup this derivation hands its inspectors. Exposed so the bounded-read guard
     *  test can drive both legs directly rather than through a sibling-reading inspector - the same seam
     *  {@code ProxyScreen.siblingLookup()} and {@code PublishInspection.siblings(Content)} offer for the two ingress
     *  legs, so all three suppliers of a {@link QualityInspector.Lookup} are pinned the same way. */
    public QualityInspector.Lookup siblings() {
        return siblings;
    }

    /** Re-derive a release's declared licenses from its stored metadata through the discovered inspectors, or an empty
     *  list when nothing claims it or it declares none. */
    private List<LicenseInventory.Declared> backfill(Release release) throws IOException {
        for (String path : inventory.paths(release.ecosystem(), release.coordinate(), release.version())) {
            for (QualityInspector inspector : inspectors) {
                if (!inspector.handles(path)) {
                    continue;
                }
                Optional<byte[]> metadata = read(path);
                if (metadata.isEmpty()) {
                    continue;
                }
                List<LicenseInventory.Declared> declared;
                try {
                    declared = declaredFrom(inspector.inspectArtifact(path, metadata.get(), siblings));
                } catch (IOException unreadable) {
                    // Contained per candidate, never per pass: what the callers build is DERIVED and recomputed every
                    // pass, and one release whose companion is past the sibling seam's whole-document ceiling (or
                    // whose body will not parse) must not abort the whole of it. Contained is not silent - the release
                    // still resolves to UNKNOWN, which is a visible count, and the reason is logged with the path that
                    // caused it, so a bound that bit is attributable rather than a licence that quietly went missing.
                    LOGGER.warn("Could not re-derive licences for " + path + " through "
                            + inspector.getClass().getName() + "; this release resolves to the unknown license "
                            + "until its carrier or companion is readable", unreadable);
                    continue;
                }
                if (!declared.isEmpty()) {
                    return declared;
                }
            }
        }
        return List.of();
    }

    /** The declared licenses across an inspector's subjects (the artifact-only variant returns just the artifact's own,
     *  no transitive dependencies), deduplicated. */
    private static List<LicenseInventory.Declared> declaredFrom(List<ComplianceGate.Subject> subjects) {
        List<LicenseInventory.Declared> declared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ComplianceGate.Subject subject : subjects) {
            for (ComplianceGate.DeclaredLicense license : subject.licenses()) {
                if (seen.add(license.name() + "\0" + license.url())) {
                    declared.add(new LicenseInventory.Declared(license.name(), license.url()));
                }
            }
        }
        return declared;
    }

    /** The stored bytes a request path serves, materialised only when they are a small metadata object - the
     *  <em>candidate</em> read that decides whether a carrier is worth handing to an inspector at all, never pulling a
     *  large blob. A carrier past {@link #MAX_METADATA_BYTES} is not a companion whose bound was reached, it is a
     *  candidate this pass declines to parse, so it is skipped rather than reported. */
    private Optional<byte[]> read(String path) throws IOException {
        Optional<String> blob = publication.located(path);
        if (blob.isEmpty() || store.size(blob.get()) > MAX_METADATA_BYTES) {
            return Optional.empty();
        }
        try (InputStream in = store.open(blob.get())) {
            return Optional.of(in.readAllBytes());
        }
    }

    /**
     * The already-published-sibling lookup the backfill hands its inspectors (a jar reading its sibling POM, a
     * coordinate reading the CycloneDX attachment beside it). Both legs are stated against the store, because the SPI
     * defaults neither: {@link #fetch} carries the seam's shared whole-document ceiling
     * ({@link PublishInterceptor.Content#LARGEST_SIBLING}, the same constant the publish and proxy screens' lookups
     * use, rather than a third number invented here) and fails loudly past it, and {@link #fetchBounded} honours the
     * caller's own limit and reports the overflow.
     *
     * <p>It is not {@code this::read} - the candidate read, doubling as the sibling seam - under which a companion past
     * the ceiling would answer the <em>absence</em> sentinel: the inspector would be told no sibling was published
     * where one was, and the release would resolve to unknown with nothing said. The two reads answer different
     * questions and have different code; a bound reached on this one is a reported outcome, not a missing companion.
     */
    private final class Siblings implements QualityInspector.Lookup {

        @Override
        public Optional<byte[]> fetch(String path) throws IOException {
            Optional<String> blob = publication.located(path);
            if (blob.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = store.open(blob.get())) {
                byte[] read = in.readNBytes(PublishInterceptor.Content.LARGEST_SIBLING + 1);
                if (read.length > PublishInterceptor.Content.LARGEST_SIBLING) {
                    throw new IOException("Published sibling exceeds the "
                            + PublishInterceptor.Content.LARGEST_SIBLING + "-byte whole-document cap, refusing to "
                            + "buffer it whole: " + path);
                }
                return Optional.of(read);
            }
        }

        @Override
        public Optional<Bounded> fetchBounded(String path, int limit) throws IOException {
            Optional<String> blob = publication.located(path);
            if (blob.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = store.open(blob.get())) {
                // One byte past the caller's limit, so a sibling of EXACTLY limit bytes is reported whole. The
                // boundary is `>`, never `>=`.
                byte[] prefix = in.readNBytes(limit + 1);
                boolean truncated = prefix.length > limit;
                return Optional.of(new Bounded(truncated ? Arrays.copyOf(prefix, limit) : prefix, truncated));
            }
        }
    }
}
