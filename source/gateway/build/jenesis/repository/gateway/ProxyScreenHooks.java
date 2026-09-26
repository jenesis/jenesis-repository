package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@link PullThroughHooks} for the dispatcher-direct proxy leg (demo seeding, the {@link
 * build.jenesis.repository.server.FormatDispatcher} loop) whose {@code screenFetch} decorates the miss-leg fetcher with
 * the DEFAULT-strength {@link ProxyScreen} over a supplied gate and store - the SAME decorator the router's DEFAULT
 * fallbacks screen through. It closes the #79 gap that a demo/dispatcher-direct proxy leg pulls through
 * <em>unscreened</em> since the embedded per-format publish screen was demoted to layout-only: no screening code is
 * added to {@code DemoSeeder}/{@code FormatDispatcher}, and no embedded per-format publish screen is
 * reintroduced - the compliance gate screens the proxy leg exactly like production.
 *
 * <p>The gate is resolved lazily per request, so a gate armed just before a seed (the demo gate config the {@code demo}
 * flag layers in) is the one that screens, and on the serving dispatcher it is the requesting tenant's own ({@link
 * #perTenant}, bound per request through {@link #forTenant}); a {@code null} gate (an ungated tenant) leaves the
 * fetcher unwrapped - the honest name for what an ungated proxy already did by omission, matching the router's own
 * {@code screening()} for an ungated tenant. {@code verifyHit} is the serve-through default: a demo seed runs over a
 * freshly empty store, so there is nothing durably local to verify.
 */
public final class ProxyScreenHooks implements PullThroughHooks {

    /** A tenant's gate, by tenant; {@code tenant} names the one these hooks screen with. */
    private final Function<String, ComplianceGate> gates;
    private final String tenant;
    private final int holdDays;

    /** Whether an incomplete screen withholds rather than serving with the fact recorded. */
    private final boolean withholdIncomplete;

    /** One gate, whatever the tenant: {@code gate} is resolved per request (so a gate armed just before a seed is the
     *  one that screens) and {@code holdDays} is the deployment-wide immaturity window. The store the screen records
     *  quarantine holds and log rows into arrives per call - see {@link PullThroughHooks#screenFetch}. The
     *  reviewed-fail-open form - see {@link ProxyScreen#ProxyScreen(ComplianceGate, ArtifactStore, int)}. */
    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays) {
        this(gate, holdDays, false);
    }

    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays, boolean withholdIncomplete) {
        Objects.requireNonNull(gate, "gate");
        this(_ -> gate.get(), null, holdDays, withholdIncomplete);
    }

    private ProxyScreenHooks(Function<String, ComplianceGate> gates, String tenant, int holdDays,
                             boolean withholdIncomplete) {
        this.gates = Objects.requireNonNull(gates, "gates");
        this.tenant = tenant;
        this.holdDays = holdDays;
        this.withholdIncomplete = withholdIncomplete;
    }

    /**
     * Hooks that screen each request with its own tenant's gate: {@code gates} answers a tenant's proxy-path gate - its
     * own policy where it has one, the deployment's where it has overridden nothing - and {@link #forTenant} binds the
     * tenant a request is served in. What the serving dispatcher installs, since it serves every tenant; bound to no
     * tenant, they screen with what {@code gates} answers for {@code null}.
     */
    public static ProxyScreenHooks perTenant(Function<String, ComplianceGate> gates, int holdDays,
                                             boolean withholdIncomplete) {
        return new ProxyScreenHooks(gates, null, holdDays, withholdIncomplete);
    }

    @Override
    public PullThroughHooks forTenant(String tenant) {
        return new ProxyScreenHooks(gates, tenant, holdDays, withholdIncomplete);
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
        return screenFetch(path, upstream, store, Map.of());
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store,
                                           Map<String, byte[]> companions) {
        ComplianceGate active = gates.apply(tenant);
        return active == null
                ? upstream
                : new ProxyScreen(active, store, holdDays, withholdIncomplete).wrap(upstream, path, companions);
    }
}
