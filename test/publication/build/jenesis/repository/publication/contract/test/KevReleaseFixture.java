package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.gate.KevHold;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * {@code KevHoldReleaseObserver}: the retroactive known-exploited hold. A release promotes its
 * {@code holds/kev/<eco>/<coord>/<ver>} record into {@code overrides/kev/...}, so the {@code kev-enforce} sweep never
 * re-holds a CVE a human has cleared; a discard drops the record and promotes nothing.
 */
final class KevReleaseFixture extends HoldReleaseFixture {

    private static final String CVE = "CVE-2021-44228";

    @Override
    public String hook() {
        return "kev-hold-release";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.gate.store.KevHoldReleaseObserver";
    }

    @Override
    public List<String> namespaces() {
        return ReleaseSpaces.of();
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        KevHold.hold(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION, Set.of(CVE));
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return KevHold.held(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION).isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        Set<String> overridden =
                KevHold.overridden(store, HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION);
        return overridden.isEmpty() ? Optional.empty() : Optional.of(new TreeSet<>(overridden).toString());
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, "overrides/kev");
    }
}
