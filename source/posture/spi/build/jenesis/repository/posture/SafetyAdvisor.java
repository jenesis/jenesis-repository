package build.jenesis.repository.posture;

import module java.base;

/**
 * The seam a module reports its security-posture advisories through: given the effective {@link Configuration}, it
 * returns zero or more {@link SecurityAdvisory security advisories} about potentially-unsafe settings it owns. A module
 * (or its provider) implements this and is discovered with {@link ServiceLoader}; a <em>disabled or absent</em> module
 * returns nothing, so the console never advises about a feature that is not running - the same graceful-degradation rule
 * the observation seam follows.
 *
 * <p>Thin core: a module owns the advisories about <em>its own</em> settings; only the deployment-cross-cutting ones
 * (auth off, the dev profile, an exposed management port, no rate limit) are seeded centrally by
 * {@link SecurityPosture}. An advisor is a pure function of configuration - it holds no mutable state and never mutates
 * anything (observing posture never changes it), so it is safe to discover, cache and call on any thread.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #advise} may be called concurrently and repeatedly - the console header badge
 *       collects a report on <em>every</em> view render, {@code GET /api/posture} on every read and the boot log once -
 *       so an implementation must be a pure function of its argument, hold no mutable state and take no lock.</li>
 *   <li><b>Idempotency / replay.</b> {@link #advise} has no side effect and is fully repeatable: two calls over an
 *       equal {@link Configuration} return equal advisory lists. It never writes, never records that it advised and
 *       never mutates the configuration it reads.</li>
 *   <li><b>Absence sentinel.</b> "Nothing is unsafe" is an empty list - never {@code null}, never an exception, never a
 *       synthetic all-clear row. A disabled or absent module contributes nothing (or no advisor at all), so an empty
 *       {@link PostureReport} is the healthy state. That makes silence load-bearing: an advisor must be silent only
 *       when it has <em>checked</em> and found nothing, never when it could not tell.</li>
 *   <li><b>Default-deny condition semantics.</b> Every condition is evaluated against the value the deployment would
 *       actually run with - the same default the reading code applies - and never against key <em>presence</em>. So an
 *       unset key raises an advisory exactly when the product's own default is the unsafe one (an unset
 *       {@code jenesis.repository.rate-limit} means unlimited and raises; an unset {@code jenesis.repository.auth}
 *       means enforced and stays silent). Where a value is parsed, the parse mirrors the reading code's parse exactly
 *       and an ambiguity resolves <em>toward</em> raising: matching only the whole value of
 *       {@code jenesis.ui.admins} would miss the wildcard in {@code alice,*} that the reader honours, and missing it
 *       fails open on the surface whose entire job is to report open configuration.</li>
 *   <li><b>Selection failure.</b> There is nothing to select: the policy is additive, every discovered advisor is
 *       evaluated, and no configuration key names one. Discovery is a plain {@code ServiceLoader.load} inside
 *       {@link PostureReport#discover} rather than the shared {@code Providers.all} primitive - an advisor declares no
 *       {@code name()} - so this SPI has no <em>provider-level</em> duplicate refusal and no
 *       {@code jenesis.repository.<name>=false} toggle: an advisor module registered twice is evaluated twice. What
 *       that actually costs a reader - two rows under one id - is caught one level down, where it is observable:
 *       {@link PostureReport#from} reports a duplicated advisory id (clause 11) rather than merging or dropping. A
 *       module switches its own advisories off by having its feature off and returning nothing, which is the
 *       "disabled contributes nothing" rule, not a toggle this SPI owns.</li>
 *   <li><b>Tenant scoping (&sect;6).</b> An advisory declares its {@link Scope}: {@code DEPLOYMENT} for a property of
 *       the whole deployment, {@code TENANT} naming the tenant it concerns, which only that tenant's admins see
 *       ({@link PostureReport#forTenant}). An advisor must not fold a tenant's data into a deployment-scoped row, and
 *       the constructor enforces the id/scope/tenant consistency so a tenant row cannot arrive unattributed.</li>
 *   <li><b>Error visibility (&sect;9).</b> A throw is <b>contained to this advisor</b>: {@link PostureReport#from}
 *       collects through {@code Contributions}, so an advisor that throws (or answers {@code null}) is replaced by a
 *       {@link Severity#WARN} {@code jenesis.posture.unavailable.<advisor>} advisory naming this class and the
 *       exception <em>type</em>, every other advisor is still evaluated, the console header badge and
 *       {@code GET /api/posture} still render, and the failure is logged once with this class's name. That substitute
 *       row is an admission, not an excuse: it can only say "whatever this advisor checks went unchecked", so the
 *       report loses the actual condition either way. An advisor that cannot evaluate a condition must therefore still
 *       answer <em>itself</em> - with an advisory naming what it could not determine, or with nothing when it has
 *       genuinely checked - rather than throw. Nothing else here may be swallowed: an advisory an advisor decides not
 *       to raise is a decision, and silence must mean the condition does not hold. An {@link Error} is <em>not</em>
 *       contained (a {@link LinkageError} from a half-installed module is a broken graph, not an advisor declining to
 *       answer).</li>
 *   <li><b>Read purity (&sect;10).</b> {@link #advise} reads the effective {@link Configuration} and nothing else: no
 *       store read, no network, no filesystem, no scan and no write. Observing posture never changes posture, and the
 *       report must stand when every external source the deployment uses is down. This is why the SPI is handed a
 *       configuration lookup rather than a store.</li>
 *   <li><b>Secret hygiene.</b> An advisory's text names the risk, the key and the safer value to set - it never repeats
 *       a <em>read</em> value. This surface enumerates the deployment's weaknesses and is served over an authenticated
 *       API, so echoing a configured value would turn a posture read into a credential read. A condition may read a
 *       secret to decide whether it holds; the rendered row may not carry it.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link PostureReport#discover} loads the advisors through
 *       {@link java.util.ServiceLoader} on <em>every</em> call, so instances are created from a public no-arg
 *       constructor, consulted and discarded - never cached, never closed. An advisor must therefore be a cheap,
 *       stateless declaration owning no thread, client or connection; an advisor needing a collaborator is evaluated
 *       through {@link PostureReport#from} with an explicitly built list instead.</li>
 *   <li><b>Ordering / determinism.</b> Results are independent of discovery order: {@link PostureReport} concatenates
 *       the advisors and sorts critical-first, ties broken by id, so two deployments with the same modules and the same
 *       configuration render the same report on any module path. Ids follow the {@code jenesis.<feature>.<signal>}
 *       grammar, are validated at construction, are stable across releases (they are the docs anchor and the row key)
 *       and are unique across advisors - a duplicate id is a collision between modules, not a merge, and
 *       {@link PostureReport#from} reports one when it sees one. The key it refuses on is the id <em>plus the
 *       tenant</em> for a tenant-scoped row, so the same advisory legitimately raised for two tenants is two rows and
 *       not a clash; both duplicates are kept, because dropping one would cost an operator a real advisory to fix a
 *       naming accident.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #advise} sits on the console render path with no cancellation
 *       signal, so it must not block and must be a bounded set of configuration reads - never an enumeration, a probe
 *       or a scan whose cost grows with the deployment.</li>
 * </ol>
 */
@FunctionalInterface
public interface SafetyAdvisor {

    /** The advisories this module raises against {@code config}; empty (never {@code null}) when nothing is unsafe. */
    List<SecurityAdvisory> advise(Configuration config);
}
