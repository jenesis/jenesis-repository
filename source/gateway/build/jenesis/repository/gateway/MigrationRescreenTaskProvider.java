package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.DenyListPolicy;
import build.jenesis.repository.compliance.GatePolicy;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the late-enablement migration re-screen sweep ({@link MigrationRescreenTask}) through {@code ServiceLoader},
 * exactly as the gate/reclamation sweeps ({@code QuarantineRetentionTaskProvider}, {@code ReanalysisTaskProvider}) are.
 * It is enabled by its own {@code harden-rescreen} switch and paced by {@code harden-rescreen-interval} (both declared
 * on {@link HardeningSettingsContributor}, so they render on the settings screen and apply on the next pass without a
 * restart); a deployment that never enables it schedules nothing, and a deployment with no {@code harden} repository
 * simply back-fills nothing.
 *
 * <p>The pass re-screens with the same compliance gates the live legs use - the deployment's CVSS threshold, malware
 * action, deny list and every discovered {@link GatePolicy} dimension over the resolved advisory source - reconstructed
 * here from the same effective config the live gates read, since a {@link MaintenanceTaskProvider} is handed only the
 * config lookup. The untrusted-upstream {@link HardenedScreen.Bounds} default the same way. Tenant-scoped gate
 * overrides are not layered here (the pass reads the deployment-wide config), which only ever screens with the
 * deployment default rather than a tenant's own stricter dial.
 *
 * <p><b>Both flavours, not just the proxy one.</b> A stored artifact is re-screened through the gate flavour
 * it was reached by - see {@link RescreenFlavor} for why the flavour belongs to the artifact - so this builds the
 * {@link GatePolicyProvider.Path#PUBLISH} and {@link GatePolicyProvider.Path#PROXY} gates from one shared shape and
 * hands the sweep the lookup rather than a single gate. The two differ only in the discovered dimensions each flavour
 * declares itself to carry; the core dials are the same on both, exactly as {@code LiveConfig} builds them.
 */
public final class MigrationRescreenTaskProvider implements MaintenanceTaskProvider {

    /** How often the migration back-fill re-screens: daily. It was hourly, and every pass listed every cached
     *  artifact of every hardened repository to find that a converged one re-screens nothing; a repository flipped
     *  to harden late is verified fail-closed on every read until the sweep reaches it, so a day is what the
     *  bulk complement costs, not what the guarantee waits for. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("harden-rescreen-interval", "P1D");

    @Override
    public String name() {
        return MigrationRescreenTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!Boolean.parseBoolean(config.apply("harden-rescreen"))) {
            return Optional.empty();
        }
        Duration interval = INTERVAL.resolve(config);
        int holdDays = holdDays(config.apply("immaturity-hold-days"));
        // Both flavours are resolved once, here, rather than per artifact: create() is where a misconfigured dimension
        // must throw (the GatePolicyProvider selection-failure clause), and a per-artifact resolve would move that
        // failure into the middle of a sweep.
        Map<GatePolicyProvider.Path, ComplianceGate> gates = new EnumMap<>(GatePolicyProvider.Path.class);
        for (GatePolicyProvider.Path path : GatePolicyProvider.Path.values()) {
            gates.put(path, gate(config, path));
        }
        // The bounds are read against the deployment's own spool budget, exactly as the live leg reads them, so a
        // per-artifact ceiling configured above the in-flight budget is refused here too rather than being accepted by
        // the sweep and refused by the boot. The pass's own scratch spool is unused on the local re-screen
        // path, so this validates the operator's configuration rather than a budget this task spends.
        HardenedScreen.Bounds bounds = HardenedScreen.Bounds.fromConfig(config, SpoolStore.Budget.fromConfig(config));
        return Optional.of(new MigrationRescreenTask(interval, Map.copyOf(gates)::get, holdDays, bounds));
    }

    /** One flavour's compliance gate resolved from the effective config: the same shape {@code LiveConfig} builds for
     *  the live legs - the CVSS threshold and its verdict, the malware action, the deny list and its verdict,
     *  plus every discovered
     *  {@link GatePolicyProvider} dimension that declares it carries {@code path}, over the resolved
     *  {@link AdvisorySource}. Unset core dials fall back to the packaged secure defaults
     *  ({@code CoreSettingsContributor}) so the migration gate is no laxer than a fresh deployment's. */
    private static ComplianceGate gate(UnaryOperator<String> config, GatePolicyProvider.Path path) {
        Severity threshold = severity(config.apply("vulnerability-threshold"), Severity.CRITICAL);
        Verdict malware = verdict(config.apply("malware-action"), Verdict.REJECT);
        Verdict vulnerable = verdict(config.apply("vulnerability-action"), Verdict.REJECT);
        List<String> denied = tokens(config.apply("deny-list"));
        Verdict denyAction = verdict(config.apply("deny-list-action"), Verdict.REJECT);
        List<GatePolicy> policies = GatePolicyProvider.resolve(config, path);
        return new ComplianceGate(new VulnerabilityPolicy(threshold).action(vulnerable),
                AdvisorySource.resolve(config))
                .malicious(new MaliciousPackagePolicy().action(malware))
                .denyList(new DenyListPolicy(denied).action(denyAction))
                .policies(policies);
    }

    private static Severity severity(String value, Severity fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return fallback;
        }
    }

    private static Verdict verdict(String value, Verdict fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Verdict.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return fallback;
        }
    }

    private static int holdDays(String value) {
        if (value == null || value.isBlank()) {
            return 2;   // the CoreSettingsContributor immaturity-hold-days default (the secure floor)
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException malformed) {
            return 2;
        }
    }

    private static List<String> tokens(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return List.copyOf(tokens);
    }
}
