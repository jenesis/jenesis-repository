package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes {@value LicenseTable#KEY}, the licences an operator adds to the built-in identification table, and refuses
 * a value {@link LicenseTable} would not parse - naming the row - on every surface that writes settings.
 *
 * <p>Deployment-wide, as the licence policy's own settings are: one declaration resolves to one licence for every
 * tenant, so the inventory, the gate and the attribution NOTICE agree about what a name means.
 */
public final class LicenseTableSettings implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(LicenseTable.KEY, "Compliance", "Additional licenses",
                "Licenses to identify beyond the built-in table of SPDX licenses, each an identifier, its category and "
                        + "the names or URLs that declare it, separated by vertical bars. A declaration naming the "
                        + "identifier, or carrying one of the names or URLs as a word of its own, resolves to it; "
                        + "these rows are tried before the built-in ones, so one may also recategorise a known "
                        + "license. The category is permissive, weak-copyleft, strong-copyleft, network-copyleft or a "
                        + "word of your own, which the allowed and denied license lists match like any other.",
                Setting.Kind.STRING, LicenseTable.DEFAULT, true).form(Setting.Form.LINES).advanced());
    }

    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        return LicenseTable.KEY.equals(setting.key()) ? LicenseTable.refusal(value) : Optional.empty();
    }
}
