package build.jenesis.repository.compliance.inventory;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * Resolves a release's declared licences for the passes that need them - the licence inventory's count and the search
 * index's licence fields. Section first: the gate records what it extracted as the version's {@code licenses} section
 * on the ACCEPT leg, so the common case is one small read. A release without a section - published before the gate
 * recorded one, or by a path no inspector claims - is re-derived through the gate's discovered
 * {@link QualityInspector}s over its stored metadata (a POM, a {@code .nuspec}, a {@code control} stanza, a packument;
 * a candidate past {@link #MAX_METADATA_BYTES} is skipped). The re-derivation is not written back: the gate's section
 * stays the only authored record, and both callers produce derived data each pass. Declared licences resolve to their
 * SPDX id and category through the deployment's {@link LicenseTable}; a release declaring none, or nothing readable,
 * resolves to the single {@link License#UNKNOWN}, so it is counted rather than vanishing.
 *
 * <p>It lives beside the licence inventory because both callers reach this module and neither the policy's nor the
 * index's.
 */
public final class LicenseDerivation {

    private static final Logger LOGGER = LoggerFactory.getLogger(LicenseDerivation.class);

    /** The largest stored object the re-derivation materialises to read metadata from, so a pass never pulls a large
     *  body into heap; real metadata sits far below it, and a release whose only carrier exceeds it resolves to
     *  unknown. It governs the candidate read only; siblings an inspector reads through {@link QualityInspector.Lookup}
     *  carry that seam's bounds. */
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

    /** The resolved licences of a release - one {@link License} per declared licence, or a single
     *  {@link License#UNKNOWN} - from the gate's section when present, re-derived otherwise. */
    public List<License> resolve(Release release) throws IOException {
        return resolve(release, licenses.read(release.ecosystem(), release.coordinate(), release.version()));
    }

    /** {@link #resolve(Release)} over licences already read from the release's document - {@code recorded} empty where
     *  none are recorded - so a caller that read the document pays no second read. */
    public List<License> resolve(Release release, Optional<List<LicenseInventory.Declared>> recorded)
            throws IOException {
        // The gate's record is preferred and never re-parsed; only a release without one is re-derived.
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

    /** The sibling lookup this derivation hands its inspectors, exposed so the bounded-read guard test drives both legs
     *  directly - as {@code ProxyScreen.siblingLookup()} and {@code PublishInspection.siblings(Content)} expose
     *  theirs. */
    public QualityInspector.Lookup siblings() {
        return siblings;
    }

    /** Re-derive a release's declared licences from its stored metadata through the inspectors, or an empty list when
     *  nothing claims it or it declares none. */
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
                    // Contained per candidate, not per pass: one release whose companion is past the sibling seam's
                    // ceiling, or whose body will not parse, must not abort a derived pass. Not silent either: it
                    // resolves to UNKNOWN, a visible count, and the reason is logged with its path.
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

    /** The declared licences across an inspector's subjects (the artifact-only variant returns the artifact's own),
     *  deduplicated. */
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

    /** The stored bytes a path serves, materialised only when a small metadata object - the candidate read deciding
     *  whether a carrier is worth an inspector. A carrier past {@link #MAX_METADATA_BYTES} is declined, not
     *  reported. */
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
     * The sibling lookup handed to inspectors (a jar reading its sibling POM, a coordinate reading its CycloneDX
     * attachment), with both legs stated: {@link #fetch} carries the seam's whole-document ceiling
     * ({@link PublishInterceptor.Content#LARGEST_SIBLING}, the screens' own constant) and fails loudly past it, and
     * {@link #fetchBounded} honours the caller's limit and reports overflow.
     *
     * <p>Not {@code this::read}: as the sibling seam, the candidate read would answer a too-large companion with the
     * absence sentinel, telling the inspector no sibling was published and resolving the release to unknown with
     * nothing said. A bound reached here is a reported outcome.
     */
    private final class Siblings implements QualityInspector.Lookup {

        /** The settings the repository's store carries. */
        @Override
        public UnaryOperator<String> settings() {
            return ComplianceSettings.lookup(store);
        }

        /** The coordinate the claiming format gives the path in this repository. */
        @Override
        public Optional<ArtifactDescriptor> described(String path) {
            return BlobLayout.claimed(path, store);
        }

        /** A derivation re-reads what was published, and follows nothing beyond what an operator named. */
        @Override
        public Optional<List<URI>> resolvesFrom(String format) {
            return Optional.empty();
        }

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
                // One byte past the limit, so a sibling of exactly the limit reads whole.
                byte[] prefix = in.readNBytes(limit + 1);
                boolean truncated = prefix.length > limit;
                return Optional.of(new Bounded(truncated ? Arrays.copyOf(prefix, limit) : prefix, truncated));
            }
        }

        /** The stored read: {@link #fetchBounded} already reads the published pointer, with no withhold probe. */
        @Override
        public Optional<Bounded> fetchStored(String path, int limit) throws IOException {
            return fetchBounded(path, limit);
        }

        /** What a format recorded in this repository, by the key it wrote it under. */
        @Override
        public Optional<Bounded> fetchRecorded(String key, int limit) throws IOException {
            return QualityInspector.Lookup.recorded(store, key, limit);
        }
    }
}
