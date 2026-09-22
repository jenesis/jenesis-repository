package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@link PullThroughHooks} for the dispatcher-direct proxy leg (demo seeding, the free {@link
 * build.jenesis.repository.server.FormatDispatcher} loop) whose {@code screenFetch} decorates the miss-leg fetcher with
 * the DEFAULT-strength {@link ProxyScreen} over a supplied gate and store - the SAME decorator the router's DEFAULT
 * fallbacks screen through (EPIC 28). It closes the #79 gap that a demo/dispatcher-direct proxy leg pulls through
 * <em>unscreened</em> since EPIC 26 demoted the embedded per-format publish screen to layout-only: no screening code is
 * added to {@code DemoSeeder}/{@code FormatDispatcher}, and no embedded per-format publish screen is
 * reintroduced - the compliance gate screens the proxy leg exactly like production.
 *
 * <p>The gate is resolved lazily per request from {@code gate}, so a gate armed just before a seed (the demo gate
 * config the {@code demo} flag layers in) is the one that screens; a {@code null} gate (an ungated tenant) leaves the
 * fetcher unwrapped - the honest name for what an ungated proxy already did by omission, matching the router's own
 * {@code screening()} for an ungated tenant. {@code verifyHit} is the serve-through default: a demo seed runs over a
 * freshly empty store, so there is nothing durably local to verify.
 */
public final class ProxyScreenHooks implements PullThroughHooks {

    private final Supplier<ComplianceGate> gate;
    private final int holdDays;

    /** D-246: whether an incomplete screen withholds rather than serving with the fact recorded. */
    private final boolean withholdIncomplete;

    /** @param gate the live gate resolved per request (so a gate armed just before a seed is the one that screens)
     *  and {@code holdDays} the deployment-wide immaturity window. The store the screen records quarantine holds and
     *  log rows into arrives per call, because the serving dispatcher these hooks now also serve is one singleton
     *  over every tenant's store - see {@link PullThroughHooks#screenFetch}. */
    /** The reviewed-fail-open form - see {@link ProxyScreen#ProxyScreen(ComplianceGate, ArtifactStore, int)}. */
    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays) {
        this(gate, holdDays, false);
    }

    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays, boolean withholdIncomplete) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.holdDays = holdDays;
        this.withholdIncomplete = withholdIncomplete;
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
        return screenFetch(path, upstream, store, Map.of());
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store,
                                           Map<String, byte[]> companions) {
        ComplianceGate active = gate.get();
        return active == null
                ? upstream
                : new ProxyScreen(active, store, holdDays, withholdIncomplete).wrap(upstream, path, companions);
    }
}
