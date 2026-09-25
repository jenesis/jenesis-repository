package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code ProvenanceAttestationReaper}: the content-keyed reclamation of a cached provenance attestation when the
 * served pointer it describes is unpublished.
 *
 * <p><b>It carries the kit's second irreducible exclusion, and for a different reason from the webhook's.</b> The
 * webhook's gap is in the nature of an event; this one is a <em>sweep gap</em>. The cache is keyed by
 * {@code (blob SHA-256, served path)}, so it cannot be reached from a coordinate version, and the one lifecycle its
 * key aligns with is the pointer's own - which is why the reaper exists at all. But nothing walks the space:
 * {@code compliance/web} provides no {@code MaintenanceTaskProvider}, no sweep names the prefix, and while the module
 * is installed {@code GET /api/admin/orphans} cannot even see the leak. So a lost {@code onDeleted} orphans one cache
 * object <em>forever</em>, and there is no pass a {@code repair} leg could drive. §9 D-4 raises the fix - a
 * retention leg keyed on "the pointer no longer resolves", which would also reclaim orphans created while the module
 * was uninstalled, something no delivery mechanism could cover.
 *
 * <p><b>And it has no publish leg at all.</b> {@code onPublished} is an explicit no-op - publishing mints no
 * attestation, the provenance endpoint does that lazily on first read - so the publish-driven properties are excluded
 * for the same reason as the index retraction observer's, and its real delete leg is driven in
 * {@link CoordinateKeyedObserverTest}.
 */
final class ProvenanceReaperFixture implements PublicationHookFixture.Observer, RecordsNothingOnPublish {

    /** {@code ProvenanceAttestationCache.PREFIX}; the class is package-private in a module whose package is exported
     *  to one named module only, so the key shape is restated rather than imported. */
    static final String SPACE = "provenance-attestation";

    static final String NO_SWEEP =
            "nothing reclaims this space but the hook itself: compliance/web provides no MaintenanceTaskProvider, no "
                    + "sweep names the prefix, and the orphan diagnostic cannot see the leak while the module is "
                    + "installed - so a lost onDeleted orphans one cache object forever and there is no pass a repair "
                    + "leg could drive. §9 D-4 raises the fix (a retention leg keyed on \"the pointer no longer "
                    + "resolves\", which also covers orphans created while the module was absent).";

    private static final String NO_PUBLISH_LEG =
            "the hook declares no publish leg: onPublished is an explicit no-op, because publishing mints no "
                    + "attestation - the provenance endpoint signs one lazily on first read. Its whole behaviour is "
                    + "on the delete leg, which the kit's publish-driven convergence cannot reach. Proven instead in "
                    + "CoordinateKeyedObserverTest, over the unpublish that really reclaims a cached attestation.";

    @Override
    public String hook() {
        return "provenance-attestation-reaper";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.compliance.web.ProvenanceAttestationReaper";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(SPACE);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.A_DROPPED_CALL_IS_HEALED_BY_AN_EXECUTABLE_REPAIR, NO_SWEEP,
                Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION, NO_PUBLISH_LEG,
                Property.A_LOST_CALL_NEVER_HIDES_A_SERVED_ARTIFACT_OR_A_HOLD, NO_PUBLISH_LEG,
                Property.THE_COMMIT_TO_CALLBACK_WINDOW_LOSES_THE_CALL, NO_PUBLISH_LEG);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // Presence rather than content: an attestation envelope is a signed document whose bytes two runs legitimately
        // differ on, and what this hook decides about is whether the object is there at all.
        Map<String, String> rows = new TreeMap<>();
        Hooks.names(store, SPACE).forEach(name -> rows.put(name, "cached"));
        return rows;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        return Map.of();   // a publish mints no attestation; the cache is filled by the first read, not by the write
    }

    @Override
    public void repair(ArtifactStore store) {
        throw new UnsupportedOperationException(NO_SWEEP);
    }

    /** The cache key the reaper rebuilds from an {@code onDeleted} descriptor: the blob hash, then the hashed served
     *  path, so two paths sharing content keep their own statements. Restated here so the delete leg can be driven. */
    static String key(String sha256, String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return SPACE + "/" + sha256 + "/" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is mandatory", impossible);
        }
    }

    @Override
    public boolean recordsWhatTheKitPublishes() {
        return false;
    }

    @Override
    public String whyNothingOnPublish() {
        return NO_PUBLISH_LEG;
    }
}
