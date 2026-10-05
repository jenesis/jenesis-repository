package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;

/**
 * The back-fill of the name an advisory database knows a cached copy by, for a copy cached before its screen recorded
 * one: a Debian binary is published in the databases under its source package and an Alpine package under its origin
 * in its release, so asked under its own name such a copy reads clean to every later screen. The walk hands this each
 * pointer; a cached copy whose version document records no name, of a path an inspector naming advisories
 * ({@link QualityInspector.AdvisoryNames}) claims, is read once and its name recorded - its own where the databases
 * know it by that - so no copy is read twice.
 *
 * <p><b>Delivery.</b> Per-item durable: the record is one compare-and-set of the version's document inside
 * {@link #onRetained}, and a re-delivered pointer finds the name recorded and reads nothing. A pointer of another copy
 * of those formats costs the read of its version's document and of its served paths, and a pointer of any other format
 * nothing at all.
 */
public final class AdvisedNameBackfill implements WalkConsumer {

    /** The consumer's name. */
    public static final String NAME = "advised-name";

    /** How much of a package is read for its control data: what an inspector reads it from sits at the front. */
    private static final int READ_LIMIT = 8 << 20;

    /** The installed inspectors that can name an advisory name, the only ones a copy is read for. */
    private static final List<QualityInspector> NAMING = QualityInspector.all().stream()
            .filter(inspector -> inspector instanceof QualityInspector.AdvisoryNames).toList();

    /** The installed layouts that keep artifacts in the shared blobs namespace, which name a pointer's version. */
    private static final List<BlobLayout> LAYOUTS = RepositoryFormat.installed().stream()
            .filter(format -> format instanceof BlobLayout).map(format -> (BlobLayout) format).toList();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled() {
        return !NAMING.isEmpty();
    }

    @Override
    public String description() {
        return "Records the name the advisory databases know a cached Debian or Alpine copy by - its source package, "
                + "its origin in its release - for a copy cached before its screen recorded one; reads each such "
                + "package once.";
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (NAMING.isEmpty() || artifact.path() == null || artifact.hash() == null || artifact.size() < 0) {
            return;
        }
        // An inspector claims by its format's prefix, which a blobs-namespace key shares, so a pointer of any other
        // format is passed over without a store read.
        String probe = artifact.path().startsWith("/") ? artifact.path() : "/" + artifact.path();
        if (NAMING.stream().noneMatch(inspector -> inspector.handles(probe))) {
            return;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> named = version(inventory, artifact.path());
        if (named.isEmpty()) {
            return;
        }
        ArtifactDescriptor version = named.get();
        Optional<String> served = inventory.paths(version.ecosystem(), version.coordinate(), version.version())
                .stream().filter(path -> NAMING.stream().anyMatch(inspector -> inspector.handles(path))).findFirst();
        if (served.isEmpty()) {
            return;
        }
        Optional<StoreRepositoryInventory.Holding> holding = inventory.holding(version.ecosystem(),
                version.coordinate(), version.version());
        if (holding.isEmpty() || !holding.get().cached() || holding.get().advised() != null) {
            return;
        }
        byte[] content;
        try (InputStream in = store.open("blobs/" + artifact.hash())) {
            content = in.readNBytes(READ_LIMIT);
        }
        AdvisorySource.Query asked = new AdvisorySource.Query(version.ecosystem(), version.coordinate(),
                version.version());
        for (QualityInspector inspector : NAMING) {
            if (!inspector.handles(served.get())) {
                continue;
            }
            for (ComplianceGate.Subject subject : inspector.inspectArtifact(served.get(), content,
                    fetchedFrom(holding.get().upstream()))) {
                if (!subject.contentScan() && subject.advised() != null) {
                    asked = subject.advised();
                }
            }
        }
        inventory.advised(version.ecosystem(), version.coordinate(), version.version(), asked, Instant.now());
    }

    /** A lookup with no siblings whose origin is the upstream the copy was cached from, which can name what its path
     *  does not - an Alpine copy's release. */
    private static QualityInspector.Lookup fetchedFrom(String upstream) {
        Optional<URI> origin;
        try {
            origin = upstream == null || upstream.isBlank() ? Optional.empty() : Optional.of(URI.create(upstream));
        } catch (IllegalArgumentException unreadable) {
            origin = Optional.empty();
        }
        Optional<URI> from = origin;
        return new QualityInspector.Lookup.Detached() {

            @Override
            public Optional<byte[]> fetch(String path) {
                return Optional.empty();
            }

            @Override
            public Optional<Bounded> fetchBounded(String path, int limit) {
                return Optional.empty();
            }

            @Override
            public Optional<URI> origin() {
                return from;
            }
        };
    }

    /** The version a delivered path is a file of: a blobs-namespace pointer key as its layout names it, a served path
     *  as the inventory describes it. */
    private static Optional<ArtifactDescriptor> version(StoreRepositoryInventory inventory, String path) {
        if (path.startsWith("/")) {
            return inventory.versionOf(path);
        }
        for (BlobLayout layout : LAYOUTS) {
            Optional<ArtifactDescriptor> described = layout.describePointer(path)
                    .filter(found -> found.coordinate() != null && found.version() != null);
            if (described.isPresent()) {
                return described;
            }
        }
        return Optional.empty();
    }
}
