package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

/**
 * The posture counterpart of {@link CoreSettingsContributor}: the {@link SafetyAdvisor} that owns the advisories about
 * the core's <em>tenant-overridable</em> dials - the compliance gate's admission knobs, which {@link SettingsScopes}
 * classifies {@link Setting.Scope#TENANT} because the gate reads them per publish and per proxy fetch <em>for the
 * tenant being served</em>. The same module declares the settings and the advisories about them, so a dial and the
 * warning about it are never described in two places.
 *
 * <h2>What a TENANT-scoped advisory promises</h2>
 * The posture model declares {@link build.jenesis.repository.posture.Scope#TENANT} and
 * {@link build.jenesis.repository.posture.PostureReport#forTenant} as the multi-tenant extension point; this is the
 * first advisor to raise one, and it is what such a row means here:
 * <ol>
 *   <li><b>It names exactly one tenant, and that tenant is the one whose configuration was read.</b> An advisory is
 *       derived only from the configuration handed to {@link #advise}, and a tenant-scoped read is collected over
 *       <em>one</em> tenant's effective chain ({@link #scoped}), so a row can never carry a condition observed in
 *       another tenant's settings. The absence of a tenant is not a licence to guess: with {@link #TENANT_KEY} unset
 *       this advisor is silent rather than folding a tenant's data into a deployment-wide row (the
 *       {@code SafetyAdvisor} contract, clause 6).</li>
 *   <li><b>Every setting key it names is one that tenant can actually change.</b> A row whose fix is a
 *       deployment-wide dial would be addressed to an audience that cannot act on it, so each key an advisory here
 *       carries is {@link SettingsScopes#tenantOverridable} - the same guard the tenant settings screen and the
 *       tenant write path consult.</li>
 *   <li><b>It is scoped, not merely labelled.</b> The row is shown to that tenant's view and to nobody else's; the
 *       console filters through {@code PostureReport#forTenant} rather than rendering every {@code TENANT}-scoped row
 *       it happens to receive, so an advisor that named a foreign tenant still could not put a row in front of the
 *       wrong audience.</li>
 *   <li><b>It reads the value the tenant's gate would actually run with</b>, along the effective chain (an operator
 *       pin over that tenant's document over the deployment document over the packaged default), parsed exactly as
 *       the gate parses it - so an advisory describes admission as it is enforced, not as it is stored.</li>
 * </ol>
 *
 * <p>Deliberately silent about the deployment baseline. These dials have no single deployment-wide answer: the value
 * that matters is the one the gate resolves for a tenant, and a deployment-wide row would claim an admission policy
 * that no tenant necessarily runs. A deployment whose baseline is unsafe therefore shows the row in <em>every</em>
 * tenant's view, which is the truth - every tenant's gate is open - rather than once, somewhere the audience cannot
 * fix it.
 *
 * <p>Like every advisor it reads configuration only (no store, no network, no scan), holds no state, and names the
 * risk and the key to change without ever repeating a read value. Bounded by construction, which is why the tenant
 * arrives as a value rather than as something to enumerate: {@link #advise} reads two keys whatever the deployment
 * looks like, and a caller renders one tenant's report, so neither the advisor nor the screen behind it grows a cost
 * with the number of tenants (the {@code SafetyAdvisor} contract's clause 12, and PRINCIPLES &sect;7).
 */
public final class TenantPosture implements SafetyAdvisor {

    /**
     * The reserved key naming the tenant whose effective configuration a posture read is being collected over - the
     * only channel a {@link SafetyAdvisor} has for it, since {@link #advise} is handed a configuration and nothing
     * else. It is <em>not</em> a setting and no operator sets it: it is written by {@link #scoped} at the point the
     * configuration is composed and read back here, which is also why {@link #scoped} answers it itself rather than
     * letting it fall through - an ambient {@code JENREG_POSTURE_TENANT} in the deployment environment must not be
     * able to make a deployment-wide posture read start attributing rows to a tenant.
     */
    public static final String TENANT_KEY = "jenreg.posture.tenant";

    /** The prefix the runtime settings live under; a core dial is {@code jenreg.<bare key>}. */
    private static final String PREFIX = "jenreg.";

    /** Where the reference documents each advisory's condition and fix; each advisory anchors on its id. These four
     *  are the gate's own dials, so they anchor in the chapter that explains the gate rather than beside the free
     *  core's deployment-wide rows, which {@code SecurityPosture} points at the observability chapter for. It used to
     *  name a path no page has ever been served at, so every one of these rows linked an operator to a 404. */
    static final String DOCS = "https://jenesis.build/depot/compliance-gate/";

    /** The CVSS-band dial, and the one band that <em>disables</em> the check rather than relaxing it. Every other
     *  band (LOW..CRITICAL) refuses at or above itself, so only this one leaves nothing refused. */
    private static final String THRESHOLD_KEY = "vulnerability-threshold";
    private static final String THRESHOLD_OFF = "NONE";

    /** The malicious-package dial, and the one verdict that admits what the feed marks malicious (the others hold
     *  it for review or refuse it outright). */
    private static final String MALWARE_KEY = "malware-action";
    private static final String MALWARE_ADMIT = "ALLOW";

    /** The other two core verdict dials, and the same admitting verdict. Both default to REJECT, so reaching ALLOW
     *  on either is a deliberate softening of a dimension that found something. */
    private static final String VULNERABILITY_ACTION_KEY = "vulnerability-action";
    private static final String DENY_LIST_ACTION_KEY = "deny-list-action";

    /**
     * The configuration a tenant-scoped posture read is collected over: {@code base} answers every key except
     * {@link #TENANT_KEY}, which answers {@code tenant} - or nothing at all when {@code tenant} is {@code null} or
     * blank, which is how a deployment-wide read is composed. The reserved key is never delegated in either
     * direction, so the tenant a report attributes its rows to is exactly the one the caller selected and never a
     * value that leaked in from the environment.
     */
    public static Configuration scoped(String tenant, Configuration base) {
        Objects.requireNonNull(base, "base");
        String named = tenant == null ? "" : tenant.strip();
        return key -> TENANT_KEY.equals(key) ? (named.isEmpty() ? null : named) : base.value(key);
    }

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        Optional<String> selected = config.optional(TENANT_KEY);
        if (selected.isEmpty()) {
            // A deployment-wide read. These dials are resolved per tenant, so there is no tenant to attribute a row
            // to and nothing honest to say - not a deployment-scoped row about a value no tenant necessarily runs.
            return List.of();
        }
        String tenant = selected.get();
        List<SecurityAdvisory> advisories = new ArrayList<>();

        // 1. The gate admits a package the advisory feed marks malicious. ALLOW is an evaluated, permitting verdict
        //    (not a withdrawn dimension), so this tenant's publishes and proxy fetches carry known-malicious
        //    artifacts into its artifact space. The packaged default is QUARANTINE.
        if (dial(config, MALWARE_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenreg.gate.malware", Severity.CRITICAL, tenant,
                    "This tenant admits packages known to be malicious",
                    "The compliance gate resolves malware-action=ALLOW for this tenant, so a package the advisory "
                            + "feed marks malicious is admitted into its artifact space instead of being held or "
                            + "refused - on publish and on every pull-through fetch. ALLOW is an evaluated verdict "
                            + "that permits, not a dimension that stands down, so the finding is known and served "
                            + "anyway. Other tenants are unaffected: this dial is resolved per tenant.",
                    "Hold a malicious package for review (QUARANTINE, the packaged default) or refuse it outright "
                            + "(REJECT). ALLOW belongs to a deliberate, time-boxed investigation, not to a serving "
                            + "tenant.",
                    PREFIX + MALWARE_KEY, "QUARANTINE", DOCS + "#jenreg.gate.malware"));
        }

        // Both rows below are WARN rather than CRITICAL, matching the threshold-off row rather than the malware
        // one: they are operator-chosen softenings of dimensions whose findings are advisory-scored or locally
        // listed, and CRITICAL is reserved here for admitting a package known to be malicious.
        //
        // 1b. The gate evaluates the vulnerability dimension, reaches the threshold, and serves anyway. This is
        //     distinct from the NONE row below: there the check does not run, here it runs, finds an advisory at or
        //     above the band, and permits. The packaged default is REJECT.
        if (dial(config, VULNERABILITY_ACTION_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenreg.gate.vulnerability.action", Severity.WARN, tenant,
                    "This tenant admits artifacts whose advisories reach its threshold",
                    "The compliance gate resolves vulnerability-action=ALLOW for this tenant, so an artifact the "
                            + "advisory feed scores at or above vulnerability-threshold is served rather than held "
                            + "or refused - on publish and on every pull-through fetch. The check still runs, so the "
                            + "finding is known and recorded; the verdict permits it anyway, which is a different "
                            + "posture from the threshold being switched off and reads differently in an incident "
                            + "review. Other tenants are unaffected: this dial is resolved per tenant.",
                    "Hold such an artifact for review (QUARANTINE) or refuse it (REJECT, the packaged default). To "
                            + "stop evaluating the dimension at all, set vulnerability-threshold=NONE instead - one "
                            + "way to switch a capability off, and it is not a verdict.",
                    PREFIX + VULNERABILITY_ACTION_KEY, "REJECT", DOCS + "#jenreg.gate.vulnerability.action"));
        }

        // 1c. The gate serves a coordinate the operator's own deny list names. The deny list is the one dimension
        //     whose finding is not a feed's judgement but this deployment's, so permitting it contradicts an
        //     explicit local decision. The packaged default is REJECT.
        if (dial(config, DENY_LIST_ACTION_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenreg.gate.denylist.action", Severity.WARN, tenant,
                    "This tenant admits coordinates its own deny list forbids",
                    "The compliance gate resolves deny-list-action=ALLOW for this tenant, so a coordinate named in "
                            + "deny-list is served rather than held or refused. Unlike every other dimension the "
                            + "finding here is not an external feed's judgement but an operator's own, so the dial "
                            + "is admitting exactly what this deployment said to forbid. Other tenants are "
                            + "unaffected: this dial is resolved per tenant.",
                    "Hold a denied coordinate for review (QUARANTINE) or refuse it (REJECT, the packaged default). "
                            + "If a coordinate should be served, remove it from deny-list rather than softening the "
                            + "verdict for every entry at once.",
                    PREFIX + DENY_LIST_ACTION_KEY, "REJECT", DOCS + "#jenreg.gate.denylist.action"));
        }

        // 2. The vulnerability dimension is switched off for this tenant: NONE disables the check outright rather
        //    than raising the band, so no CVSS severity is refused at all. The packaged default is CRITICAL - the
        //    secure floor - and every other band (LOW..CRITICAL) is stricter than NONE, so only NONE raises.
        if (dial(config, THRESHOLD_KEY).equals(THRESHOLD_OFF)) {
            advisories.add(SecurityAdvisory.tenant("jenreg.gate.vulnerability", Severity.WARN, tenant,
                    "This tenant's vulnerability check is disabled",
                    "The compliance gate resolves vulnerability-threshold=NONE for this tenant, which disables the "
                            + "CVSS check rather than relaxing it: no severity is refused, so a known-vulnerable "
                            + "artifact is admitted on publish and on pull-through however severe its advisory. "
                            + "Other tenants are unaffected: this dial is resolved per tenant.",
                    "Set the band this tenant should refuse at. CRITICAL is the packaged secure floor; a lower band "
                            + "(HIGH, MEDIUM) is stricter still. NONE opts out of the check entirely.",
                    PREFIX + THRESHOLD_KEY, "CRITICAL", DOCS + "#jenreg.gate.vulnerability"));
        }

        return List.copyOf(advisories);
    }

    /**
     * A gate dial read exactly as the gate reads it - trimmed and upper-cased - with an unset or blank value
     * answering the empty string, which matches no unsafe constant and therefore raises nothing (an unset dial runs
     * at its packaged secure default). An unparseable value answers itself and likewise matches nothing, which is
     * deliberately <em>not</em> resolved toward raising: such a value never becomes the running policy at all - the
     * live configuration refuses it and keeps the last good snapshot, and the migration re-screen falls back to the
     * packaged default - so no tenant is served through an open gate because of it.
     */
    private static String dial(Configuration config, String key) {
        return config.optional(PREFIX + key).map(value -> value.toUpperCase(Locale.ROOT)).orElse("");
    }
}
