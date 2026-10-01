package build.jenesis.repository.posture;

import module java.base;

/**
 * The seam a module reports its security-posture advisories through: given the effective {@link Configuration}, it
 * returns zero or more {@link SecurityAdvisory advisories} about potentially unsafe settings it owns. It is discovered
 * with {@link ServiceLoader}; a disabled or absent module returns nothing, so the console never advises about a feature
 * that is not running.
 *
 * <p>A module owns the advisories about its own settings; only the deployment-cross-cutting ones (auth off, the dev
 * profile, no rate limit) are seeded centrally by {@link SecurityPosture}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #advise} may be called concurrently and repeatedly - the console badge collects a
 *       report on every render - so an implementation is a pure function of its argument, holds no mutable state and
 *       takes no lock.</li>
 *   <li><b>Idempotency / replay.</b> {@link #advise} has no side effect: two calls over an equal {@link Configuration}
 *       return equal lists. It never writes and never mutates the configuration it reads.</li>
 *   <li><b>Absence sentinel.</b> "Nothing is unsafe" is an empty list - never {@code null}, an exception or an
 *       all-clear row - so an empty {@link PostureReport} is the healthy state. Silence is therefore load-bearing: an
 *       advisor is silent only when it has checked and found nothing, never when it could not tell.</li>
 *   <li><b>Default-deny condition semantics.</b> A condition is evaluated against the value the deployment would run
 *       with - the reading code's own default - never against key presence: an unset {@code jenrepo.rate-limit} means
 *       the default ceiling and stays silent, an unset {@code jenrepo.auth} means enforced and stays silent. A parse
 *       mirrors the reading code's parse exactly and an ambiguity resolves toward raising (the wildcard in
 *       {@code alice,*} counts, as its reader counts it).</li>
 *   <li><b>Selection failure.</b> Nothing is selected: every discovered advisor is evaluated and no key names one.
 *       Discovery is a plain {@code ServiceLoader.load} in {@link PostureReport#discover} - an advisor declares no
 *       {@code name()} - so there is no provider-level duplicate refusal and no {@code jenrepo.<name>=false} toggle; an
 *       advisor registered twice is evaluated twice, and {@link PostureReport#from} reports the duplicated id (clause
 *       11). A module switches its advisories off by having its feature off.</li>
 *   <li><b>Tenant scoping.</b> An advisory declares its {@link Scope}: {@code DEPLOYMENT} for a property of the whole
 *       deployment, {@code TENANT} naming the tenant it concerns, which only that tenant's admins see
 *       ({@link PostureReport#forTenant}). An advisor never folds a tenant's data into a deployment-scoped row; the
 *       constructor enforces id/scope/tenant consistency.</li>
 *   <li><b>Error visibility.</b> A throw is <b>contained to this advisor</b>: {@link PostureReport#from} replaces it
 *       (or a {@code null} answer) with a {@link Severity#WARN} {@code jenrepo.posture.unavailable.<advisor>} advisory
 *       naming this class and the exception type, evaluates every other advisor, and logs the failure once. That row
 *       can only say the advisor's checks went unchecked, so an advisor that cannot evaluate a condition answers itself
 *       - with an advisory naming what it could not determine - rather than throw. An {@link Error} is not contained: a
 *       {@link LinkageError} is a broken graph.</li>
 *   <li><b>Read purity.</b> {@link #advise} reads the {@link Configuration} and nothing else: no store, network,
 *       filesystem, scan or write. Observing posture never changes it, and the report stands when every external source
 *       is down.</li>
 *   <li><b>Secret hygiene.</b> An advisory's text names the risk, the key and the safer value - never a read value. A
 *       condition may read a secret to decide; the rendered row may not carry it.</li>
 *   <li><b>Lifecycle / ownership.</b> Advisors are created from a public no-arg constructor once per class loader by
 *       {@link PostureReport#discover} and held for its life, never closed. An advisor is therefore a cheap stateless
 *       declaration owning no thread, client or connection; one needing a collaborator is evaluated through
 *       {@link PostureReport#from} with an explicitly built list.</li>
 *   <li><b>Ordering / determinism.</b> {@link PostureReport} sorts critical-first, ties by id, so the report is
 *       independent of discovery order. Ids follow the {@code jenrepo.<feature>.<signal>} grammar, are validated at
 *       construction, are stable across releases (the docs anchor and the row key) and unique across advisors;
 *       {@link PostureReport#from} reports a duplicate, keyed by id plus tenant for a tenant-scoped row, and keeps both
 *       rows.</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #advise} sits on the console render path with no cancellation
 *       signal, so it is a bounded set of configuration reads - never an enumeration, probe or scan.</li>
 * </ol>
 */
@FunctionalInterface
public interface SafetyAdvisor {

    /** The advisories this module raises against {@code config}; empty (never {@code null}) when nothing is unsafe. */
    List<SecurityAdvisory> advise(Configuration config);
}
