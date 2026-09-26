package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.hooks.testkit.Coordinates;
import build.jenesis.repository.hooks.testkit.HoldReleaseFixture;
import build.jenesis.repository.hooks.testkit.HookTestFormat;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.testkit.PublicationHookContract.Property;

/**
 * {@code DiscardedHoldFindingsObserver}: the only hold-release hook whose whole behaviour is on the
 * <em>discard</em> leg. A discarded version was never published, so no eviction or reconcile sweep would ever reach
 * its findings document - this hook is its only reclamation. A <em>released</em> path keeps its findings on purpose: the artifact
 * now serves and its gate history is part of the ledger.
 *
 * <p>So {@code onReleased} is a documented no-op, and the two properties that assert what a hook leaves behind on a
 * successful release are excluded with that reason. Both remain proven by the hooks that do promote one.
 */
final class DiscardedFindingsFixture extends HoldReleaseFixture {

    private static final String NO_RELEASE_LEG =
            "onReleased is a documented no-op: a released artifact keeps its findings, because it now serves and its "
                    + "gate history is reclaimed with it on eviction. This hook therefore promotes no override marker "
                    + "and touches no store on the release leg, so neither the promoted-override assertion nor the "
                    + "store-outage-mid-fan-out one has anything to bind to here. Both are exercised by the kev "
                    + "and license fixtures, whose release legs do promote one; this hook's own "
                    + "behaviour is asserted by A_DISCARD_DROPS_THE_RECORD_WITHOUT_PROMOTING_AN_OVERRIDE.";

    @Override
    public String hook() {
        return "discarded-hold-findings";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.findings.store.DiscardedHoldFindingsObserver";
    }

    @Override
    public List<String> namespaces() {
        // The findings are a section of the version's metadata document, which a discard drops.
        return ReleaseSpaces.of(MetadataKey.PREFIX);
    }

    @Override
    public Map<Property, String> unsupported() {
        return Map.of(
                Property.HOOKS_THAT_RAN_BEFORE_THE_FAILURE_ARE_IDEMPOTENT_ON_RETRY, NO_RELEASE_LEG,
                Property.A_STORE_FAULT_MID_FAN_OUT_LEAVES_THE_HOLD_SAFE, NO_RELEASE_LEG);
    }

    @Override
    protected void record(ArtifactStore store, String path) throws IOException {
        // The per-version findings the gate recorded beside the hold, which this hook reclaims.
        ledger(store).record(HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION, Finding.of(
                "gate-kit", "gate", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "held",
                Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return !ledger(store).of(HookTestFormat.ECOSYSTEM, Coordinates.of(path), HookTestFormat.VERSION).isEmpty();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) {
        return Optional.empty();   // this hook promotes nothing, on any leg
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Hooks.rows(store, MetadataKey.PREFIX);
    }

    private static Findings ledger(ArtifactStore store) {
        return FindingsProvider.installed().orElseThrow().over(store);
    }
}
