package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the policy dimension's one setting, the rule set, so it appears exactly when this module is installed. A
 * gate-policy dial, {@link Setting.Scope#TENANT}: a tenant may carry its own rules over the deployment default. Empty,
 * the default, enforces nothing.
 */
public final class PolicySettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("policy-rules", "Compliance", "Policy rules",
                        "Expression-based gate rules, one per line (or separated by ';'), each "
                                + "'<verdict> <expression>' where verdict is allow, quarantine or reject - e.g. "
                                + "'quarantine #ecosystem == \"npm\" and #advisoryCount > 0' or "
                                + "'quarantine !#licenses.?[#this matches \"(?i).*agpl.*\"].empty'. Variables: "
                                + "#ecosystem, #coordinate, #version, #licenses, #severity, #severityRank (0..5, UNKNOWN highest), "
                                + "#reachable, #reachability, #depth, #advisories, #advisoryCount, #malicious, "
                                + "#secretCount, #contentScan. Expressions are sandboxed (no method calls or type "
                                + "references). Empty disables the dimension.",
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT).standard());
    }
}
