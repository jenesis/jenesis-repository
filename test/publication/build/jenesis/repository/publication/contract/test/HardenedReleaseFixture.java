package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gateway.HardenedScreen;
import build.jenesis.repository.gateway.VerdictSection;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;

/**
 * {@code HardenedReleaseObserver}: a copy a hardened proxy held. Its record is the digest-pinned verdict the hardened
 * leg wrote over the held bytes, and a release promotes it into an {@code ALLOW} over the same bytes under the same
 * policy - the verdict the leg serves a local copy on, so the next hit does not hold what a reviewer released.
 */
final class HardenedReleaseFixture extends HoldReleaseFixture {

    private static final String POLICY = "kit-policy";

    @Override
    public String hook() {
        return "hardened-release";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.gateway.HardenedReleaseObserver";
    }

    @Override
    public List<String> namespaces() {
        // The verdict is a section of the version's metadata document.
        return ReleaseSpaces.of(MetadataKey.PREFIX);
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(Property.A_DISCARD_DROPS_THE_RECORD_WITHOUT_PROMOTING_AN_OVERRIDE,
                "a discard leaves the held verdict standing on purpose: it is the drift baseline the hardened leg "
                        + "holds a re-fetch of an immutable coordinate to, so different bytes fetched there later are "
                        + "refused and alarmed rather than screened as new. A discard promotes no ALLOW, which "
                        + "A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD and the release legs' override reads assert.");
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        // The verdict the hardened leg wrote as it held the bytes the review pointer names.
        String held = new Publication(store, List.of(), List.of()).blob(Publication.QUARANTINE_PATH + path)
                .orElseThrow();
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        metadata(store).mutate(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(),
                VerdictSection.TAG, VerdictSection.record(held, Verdict.QUARANTINE, null, "harden",
                        "https://upstream.test" + path, List.of(), Instant.parse("2026-07-01T00:00:00Z"), 1L, POLICY));
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return verdict(store, path).filter(recorded -> recorded.verdict() != Verdict.ALLOW).isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        // Stable across retries: what was allowed, over which bytes and under which policy - never when.
        return verdict(store, path).filter(recorded -> recorded.verdict() == Verdict.ALLOW)
                .map(recorded -> recorded.verdict() + " " + recorded.digest() + " " + recorded.policy());
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, MetadataKey.PREFIX);
    }

    private static Optional<VerdictSection.Recorded> verdict(ArtifactStore store, String path) throws IOException {
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        return VerdictSection.recorded(metadata(store).section(coordinate.ecosystem(), coordinate.coordinate(),
                coordinate.version(), VerdictSection.TAG));
    }

    private static MetadataStore metadata(ArtifactStore store) {
        return MetadataProvider.installed().over(store);
    }
}
