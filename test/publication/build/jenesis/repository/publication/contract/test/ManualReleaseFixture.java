package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.gate.ManualHold;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * {@code ManualHoldReleaseObserver}: a hold an operator placed by hand. A release or a discard consumes its
 * {@code holds/manual/<eco>/<coord>/<ver>} record; a release promotes it into {@code overrides/manual/...}, which no
 * sweep reads.
 */
final class ManualReleaseFixture extends HoldReleaseFixture {

    private static final String OPERATOR = "keylogin/admin";

    @Override
    public String hook() {
        return "manual-hold-release";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.gate.store.ManualHoldReleaseObserver";
    }

    @Override
    public List<String> namespaces() {
        return ReleaseSpaces.of();
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        ManualHold.hold(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION, OPERATOR);
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return ManualHold.held(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION)
                .isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        Set<String> overridden =
                ManualHold.overridden(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION);
        return overridden.isEmpty() ? Optional.empty() : Optional.of(new TreeSet<>(overridden).toString());
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, "overrides/manual");
    }
}
