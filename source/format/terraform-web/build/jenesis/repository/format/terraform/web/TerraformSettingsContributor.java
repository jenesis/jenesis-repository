package build.jenesis.repository.format.terraform.web;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the discovery document's one dial, so it appears on the settings screens and in the generated
 * reference. It is read through Spring rather than through the settings registry, which is why it needs saying
 * here: a dial no contributor declares is a dial an operator cannot find.
 */
public final class TerraformSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting("terraform.prefix", "Formats", "Terraform registry path",
                "The path this deployment serves its Terraform registry under, as the discovery document at "
                        + "/.well-known/terraform.json reports it. It has to be configured rather than derived: "
                        + "a client fetches that document from the host root, so the request carries no "
                        + "repository segment to infer one from. The default is a repository named terraform "
                        + "in the default tenant.",
                Setting.Kind.STRING, TerraformDiscoveryConfig.DEFAULT_PREFIX, false));
    }
}
