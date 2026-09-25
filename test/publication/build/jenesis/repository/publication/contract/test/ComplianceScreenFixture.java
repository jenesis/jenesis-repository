package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code ComplianceScreen}: the gate's pre-commit screen, and the only one of the three that overrides an
 * inherited observer leg ({@code onPublished}, the hold-mapping round-trip diagnostic).
 *
 * <p><b>Why {@code reads()} is empty and there is no read side to arrange.</b> The screen's verdict side reads
 * through injected providers over the publication's own scoped store - a wired gate, not a key prefix the kit can
 * fault - and it no longer has a read side of its own: it holds through the {@code /quarantine<path>} review pointer,
 * which {@code Publication.link} copies onto the serving pointer as its hold flag, so a serve reads the hold
 * off the one pointer it reads anyway. Until 2026-09-12 the screen answered {@code withheld} by probing the review
 * pointer on every download, and this fixture declared that prefix so the kit could prove the probe failed closed;
 * that probe was the first of a download's four reads and is gone. The fail-closed property now belongs to the
 * serving pointer read itself, which the store SPI's own tests and the format contract's hold round trip hold.
 *
 * <p><b>Why the declared verdicts are {@code ACCEPT} only.</b> {@code assess} returns {@code ACCEPT} unconditionally
 * until a {@code ComplianceGate} is wired, and that wiring is a process-wide {@code AtomicReference} the deployment
 * sets ({@code ComplianceScreen.live(...)}), not durable state a fixture can seed - the kit's {@code arrange} seam is
 * explicitly about state "the screen could really have been holding". Declaring a verdict this fixture cannot reach
 * from the store would make the fail-closed leg assert a gate the fixture wired rather than one a deployment has.
 * The quarantine and reject verdicts are covered where the gate is wired: {@code test/gateway}'s
 * {@code ComplianceScreenTest} and {@code test/gate}'s {@code ReleaseReplaySuppressionTest}.
 */
final class ComplianceScreenFixture implements PublicationHookFixture.Interceptor {

    static final String PROVIDER = "build.jenesis.repository.gate.store.ComplianceScreen";

    @Override
    public String hook() {
        return "gate-compliance-screen";
    }

    @Override
    public String providerClass() {
        return PROVIDER;
    }

    @Override
    public PublishInterceptor create() {
        return (PublishInterceptor) Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        // The gate's own declared spaces, plus the inventory spaces its accepted-publish leg records THROUGH the
        // inventory's API. The kit walks the store and attributes every new key to the hook that ran, so a screen
        // that calls another module's recorder has to declare where that recorder writes - which is itself worth
        // knowing: ComplianceScreen.committed is the one hook in this family whose effect lands outside its module.
        return List.of("audit/quarantine", "audit/quarantine-index", "holds", "overrides",
                "published", "recent", "licenses", "identity", "findings");
    }

    @Override
    public Set<PublishInterceptor.Disposition> verdicts() {
        return Set.of(PublishInterceptor.Disposition.ACCEPT);
    }

    @Override
    public void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict) {
        if (verdict != PublishInterceptor.Disposition.ACCEPT) {
            throw new IllegalArgumentException(hook() + " cannot be arranged to " + verdict
                    + " from durable state: a non-neutral verdict comes from a wired ComplianceGate, which is process "
                    + "wiring rather than store state");
        }
    }

    @Override
    public boolean arrangeWithhold(ArtifactStore store, String path) {
        // No read side of its own: the retroactive hold a sweep writes is a review pointer at /quarantine<path>, and
        // Publication copies it onto the serving pointer, which is what a serve reads. The kit's own
        // withholding probe drives clause 9's retraction check with this screen as a bystander.
        return false;
    }

    @Override
    public List<String> reads() {
        return List.of();
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // The screen's durable commit-time trace, normalised to presence: the publish-time sidecar it records for an
        // accepted artifact carries the publish instant, which two converged runs legitimately differ on, and the
        // clause-2 replay check compares this map across three commits of the same bytes.
        Map<String, String> rows = new TreeMap<>();
        Hooks.names(store, "published").forEach(name -> rows.put(name, "recorded"));
        Hooks.names(store, "audit/quarantine-index").forEach(name -> rows.put("held:" + name, "quarantined"));
        return rows;
    }
}
