package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the policy-as-code dimension's one tunable - the rule set - so it surfaces on the settings screens,
 * {@code /api/settings} and the CLI exactly when this module is installed, and the modules console pairs it with the
 * module. A gate-policy knob, so it is marked {@link Setting.Scope#TENANT}: a tenant may carry its own rules
 * independently of the deployment default (the per-tenant effective chain), a plugin declaring its own scope rather
 * than the neutral core classifying it. Empty (the default) means the dimension enforces nothing.
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
                        Setting.Kind.STRING, "", true, Setting.Scope.TENANT));
    }
}
