package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The {@link PullThroughHooks} for the dispatcher-direct proxy leg (the {@link
 * build.jenesis.repository.server.FormatDispatcher} loop) whose {@code screenFetch} decorates the miss-leg fetcher with
 * the DEFAULT-strength {@link ProxyScreen} over a supplied gate and store - the SAME decorator the router's DEFAULT
 * fallbacks screen through. Without it a dispatcher-direct proxy leg would pull through <em>unscreened</em>,
 * since a format's own publish path only lays out: no screening code is added to
 * {@code FormatDispatcher}, and no per-format publish screen is embedded - the compliance gate
 * screens the proxy leg exactly like production.
 *
 * <p>The gate is resolved lazily per request, so the one in force when a fetch runs is the one that screens, and on
 * the serving dispatcher it is the requesting tenant's own ({@link #perTenant}, bound per request through
 * {@link #forRequest}, which also reads whether the request's repository marks its upstreams internal and screens its
 * fetch through the publishing flavour if so); a {@code null} gate (an ungated tenant) leaves the fetcher unwrapped -
 * the honest name for what
 * an ungated proxy already did by omission, matching the router's own {@code screening()} for an ungated tenant.
 * {@code verifyHit} is the serve-through default.
 */
public final class ProxyScreenHooks implements PullThroughHooks {

    /** A tenant's gate, by tenant and flavour; {@code tenant} and {@code flavour} name the one these hooks screen with. */
    private final BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gates;
    private final String tenant;
    private final GatePolicyProvider.Path flavour;
    /** The immaturity window, read per fetch. */
    private final IntSupplier holdDays;

    /** Whether an incomplete screen withholds rather than serving with the fact recorded, read per fetch. */
    private final BooleanSupplier withholdIncomplete;

    /** One gate, whatever the tenant: {@code gate} is resolved per request (so a gate armed just before a seed is the
     *  one that screens) and {@code holdDays} is the deployment-wide immaturity window. The store the screen records
     *  quarantine holds and log rows into arrives per call - see {@link PullThroughHooks#screenFetch}. The
     *  reviewed-fail-open form - see {@link ProxyScreen#ProxyScreen(ComplianceGate, ArtifactStore, int)}. */
    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays) {
        this(gate, holdDays, false);
    }

    public ProxyScreenHooks(Supplier<ComplianceGate> gate, int holdDays, boolean withholdIncomplete) {
        Objects.requireNonNull(gate, "gate");
        this((_, _) -> gate.get(), null, GatePolicyProvider.Path.PROXY, () -> holdDays, () -> withholdIncomplete);
    }

    private ProxyScreenHooks(BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gates, String tenant,
                             GatePolicyProvider.Path flavour, IntSupplier holdDays,
                             BooleanSupplier withholdIncomplete) {
        this.gates = Objects.requireNonNull(gates, "gates");
        this.tenant = tenant;
        this.flavour = Objects.requireNonNull(flavour, "flavour");
        this.holdDays = Objects.requireNonNull(holdDays, "holdDays");
        this.withholdIncomplete = Objects.requireNonNull(withholdIncomplete, "withholdIncomplete");
    }

    /**
     * Hooks that screen each request with its own tenant's gate: {@code gates} answers a tenant's gate of a flavour -
     * its own policy where it has one, the deployment's where it has overridden nothing - and {@link #forRequest} binds
     * the tenant a request is served in and the flavour its repository's fetches are screened through. What the serving dispatcher installs, since it serves every tenant; bound to no
     * tenant, they screen with what {@code gates} answers for {@code null}. The immaturity window and the
     * incomplete-screen dial are read per fetch, so a change to either applies without a restart.
     */
    public static ProxyScreenHooks perTenant(BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gates,
                                             IntSupplier holdDays, BooleanSupplier withholdIncomplete) {
        return new ProxyScreenHooks(gates, null, GatePolicyProvider.Path.PROXY, holdDays, withholdIncomplete);
    }

    @Override
    public PullThroughHooks forTenant(String tenant) {
        return new ProxyScreenHooks(gates, tenant, GatePolicyProvider.Path.PROXY, holdDays, withholdIncomplete);
    }

    @Override
    public PullThroughHooks forRequest(String tenant, FormatExchange exchange) {
        return new ProxyScreenHooks(gates, tenant, GatePolicyProvider.Path.fetched(exchange::setting), holdDays,
                withholdIncomplete);
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
        return screenFetch(path, upstream, store, Map.of());
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store,
                                           Map<String, byte[]> companions) {
        ComplianceGate active = gates.apply(tenant, flavour);
        return active == null
                ? upstream
                : new ProxyScreen(active, store, holdDays.getAsInt(), withholdIncomplete.getAsBoolean())
                        .wrap(upstream, path, companions);
    }
}
