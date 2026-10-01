package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

/**
 * The {@link SafetyAdvisor} for the core's tenant-overridable dials: the compliance gate's admission knobs, which
 * {@link SettingsScopes} classifies {@link Setting.Scope#TENANT} because the gate reads them for the tenant being
 * served. The module that declares the settings also declares the advisories about them.
 *
 * <p>A row this advisor raises:
 * <ol>
 *   <li>names exactly one tenant, the one whose configuration {@link #scoped} composed; with {@link #TENANT_KEY} unset
 *       the advisor is silent rather than folding a tenant's data into a deployment-wide row;</li>
 *   <li>names only keys {@link SettingsScopes#tenantOverridable} says that tenant can change;</li>
 *   <li>is shown only in that tenant's view, since the console filters through {@code PostureReport#forTenant};</li>
 *   <li>reads the value the tenant's gate runs with, along the effective chain and parsed as the gate parses it.</li>
 * </ol>
 *
 * <p>It says nothing about the deployment baseline: these dials have no single deployment-wide answer, so an unsafe
 * baseline shows in every tenant's view. Like every advisor it reads configuration only, holds no state and never
 * repeats a read value; it reads a fixed set of keys for one tenant, so its cost does not grow with the tenants.
 */
public final class TenantPosture implements SafetyAdvisor {

    /**
     * The reserved key naming the tenant a posture read is collected over, the only channel {@link #advise} has for it.
     * Not a setting: {@link #scoped} answers it itself, so an ambient {@code JENREPO_POSTURE_TENANT} cannot make a
     * deployment-wide read attribute rows to a tenant.
     */
    public static final String TENANT_KEY = "jenrepo.posture.tenant";

    /** The prefix the runtime settings live under; a core dial is {@code jenrepo.<bare key>}. */
    private static final String PREFIX = "jenrepo.";

    /** Where the reference documents each advisory's condition and fix; each advisory anchors on its id. */
    static final String DOCS = "https://jenesis.build/repository/operations/";

    /** The CVSS-band dial, and the one band that disables the check rather than relaxing it. */
    private static final String THRESHOLD_KEY = "vulnerability-threshold";
    private static final String THRESHOLD_OFF = "NONE";

    /** The malicious-package dial, and the one verdict that admits what the feed marks malicious. */
    private static final String MALWARE_KEY = "malware-action";
    private static final String MALWARE_ADMIT = "ALLOW";

    /** The other two core verdict dials, both defaulting to REJECT. */
    private static final String VULNERABILITY_ACTION_KEY = "vulnerability-action";
    private static final String DENY_LIST_ACTION_KEY = "deny-list-action";

    /**
     * The configuration a posture read is collected over: {@code base} answers every key except {@link #TENANT_KEY},
     * which answers {@code tenant}, or nothing when it is {@code null} or blank (a deployment-wide read).
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
            // A deployment-wide read: these dials resolve per tenant, so there is nothing to attribute a row to.
            return List.of();
        }
        String tenant = selected.get();
        List<SecurityAdvisory> advisories = new ArrayList<>();

        // 1. The gate admits a package the feed marks malicious. CRITICAL: the finding is known and served anyway.
        if (dial(config, MALWARE_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenrepo.gate.malware", Severity.CRITICAL, tenant,
                    "This tenant admits packages known to be malicious",
                    "The compliance gate resolves malware-action=ALLOW for this tenant, so a package the advisory "
                            + "feed marks malicious is admitted into its artifact space instead of being held or "
                            + "refused - on publish and on every pull-through fetch. ALLOW is an evaluated verdict "
                            + "that permits, not a dimension that stands down, so the finding is known and served "
                            + "anyway. Other tenants are unaffected: this dial is resolved per tenant.",
                    "Hold a malicious package for review (QUARANTINE) or refuse it outright (REJECT, the "
                            + "packaged default). ALLOW belongs to a deliberate, time-boxed investigation, not to a serving "
                            + "tenant.",
                    PREFIX + MALWARE_KEY, "QUARANTINE", DOCS + "#jenrepo.gate.malware"));
        }

        // The rows below are WARN: operator-chosen softenings of advisory-scored or locally listed findings.
        //
        // 1b. The vulnerability check runs, reaches the threshold and permits - unlike NONE, where it does not run.
        if (dial(config, VULNERABILITY_ACTION_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenrepo.gate.vulnerability.action", Severity.WARN, tenant,
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
                    PREFIX + VULNERABILITY_ACTION_KEY, "REJECT", DOCS + "#jenrepo.gate.vulnerability.action"));
        }

        // 1c. The gate serves a coordinate the operator's own deny list names.
        if (dial(config, DENY_LIST_ACTION_KEY).equals(MALWARE_ADMIT)) {
            advisories.add(SecurityAdvisory.tenant("jenrepo.gate.denylist.action", Severity.WARN, tenant,
                    "This tenant admits coordinates its own deny list forbids",
                    "The compliance gate resolves deny-list-action=ALLOW for this tenant, so a coordinate named in "
                            + "deny-list is served rather than held or refused. Unlike every other dimension the "
                            + "finding here is not an external feed's judgement but an operator's own, so the dial "
                            + "is admitting exactly what this deployment said to forbid. Other tenants are "
                            + "unaffected: this dial is resolved per tenant.",
                    "Hold a denied coordinate for review (QUARANTINE) or refuse it (REJECT, the packaged default). "
                            + "If a coordinate should be served, remove it from deny-list rather than softening the "
                            + "verdict for every entry at once.",
                    PREFIX + DENY_LIST_ACTION_KEY, "REJECT", DOCS + "#jenrepo.gate.denylist.action"));
        }

        // 2. The vulnerability check is switched off: NONE refuses no severity at all.
        if (dial(config, THRESHOLD_KEY).equals(THRESHOLD_OFF)) {
            advisories.add(SecurityAdvisory.tenant("jenrepo.gate.vulnerability", Severity.WARN, tenant,
                    "This tenant's vulnerability check is disabled",
                    "The compliance gate resolves vulnerability-threshold=NONE for this tenant, which disables the "
                            + "CVSS check rather than relaxing it: no severity is refused, so a known-vulnerable "
                            + "artifact is admitted on publish and on pull-through however severe its advisory. "
                            + "Other tenants are unaffected: this dial is resolved per tenant.",
                    "Set the band this tenant should refuse at. CRITICAL is the packaged secure floor; a lower band "
                            + "(HIGH, MEDIUM) is stricter still. NONE opts out of the check entirely.",
                    PREFIX + THRESHOLD_KEY, "CRITICAL", DOCS + "#jenrepo.gate.vulnerability"));
        }

        return List.copyOf(advisories);
    }

    /**
     * A gate dial read as the gate reads it, trimmed and upper-cased; unset or blank answers the empty string, which
     * raises nothing since the dial runs at its secure default. An unparseable value raises nothing either, because the
     * live configuration refuses it and keeps the last good snapshot.
     */
    private static String dial(Configuration config, String key) {
        return config.optional(PREFIX + key).map(value -> value.toUpperCase(Locale.ROOT)).orElse("");
    }
}
