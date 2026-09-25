package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.gate.LicenseHold;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * {@code LicenseHoldReleaseObserver}: the retroactive license hold. Same promotion shape as the KEV kind, over
 * {@code holds/license/...} and {@code overrides/license/...} - and with no quarantine-log fallback, so its record is
 * the only thing it will ever act on.
 */
final class LicenseReleaseFixture extends HoldReleaseFixture {

    private static final String REASON = "GPL-3.0-only";

    @Override
    public String hook() {
        return "license-hold-release";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.gate.store.LicenseHoldReleaseObserver";
    }

    @Override
    public List<String> namespaces() {
        return ReleaseSpaces.of();
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        LicenseHold.hold(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION,
                Set.of(REASON));
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return LicenseHold.held(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION)
                .isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        Set<String> overridden =
                LicenseHold.overridden(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION);
        return overridden.isEmpty() ? Optional.empty() : Optional.of(new TreeSet<>(overridden).toString());
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, "overrides/license");
    }
}
