package build.jenesis.repository.format.terraform.web;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the Terraform registry's dials - the discovery document's path, and where a proxied module's git source
 * may be fetched from - so they appear on the settings screens and in the generated reference. They are read through
 * Spring rather than through the settings registry, which is why they need saying here: a dial no contributor
 * declares is a dial an operator cannot find.
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
                Setting.Kind.STRING, TerraformDiscoveryConfig.DEFAULT_PREFIX, false),
                new Setting("terraform.git-hosts", "Formats", "Terraform git hosts",
                        "The git hosts a proxied Terraform module's git source may be fetched from, so the module "
                                + "downloads through this repository rather than being cloned by the client. Each "
                                + "listed host serves a ref as an archive, which is cached and held to the digest of "
                                + "its first fetch; a moved tag is then refused. Comma-separated hosts as a source "
                                + "writes them, port included; a host other than github.com, gitlab.com or "
                                + "bitbucket.org names its kind after '=' (github, gitlab or bitbucket), as in "
                                + "git.example.com=gitlab. Empty by default, since it reaches a third party: to "
                                + "fetch from the three public hosts, set github.com,gitlab.com,bitbucket.org.",
                        Setting.Kind.STRING, "", false),
                new Setting("terraform.git-refuse-unlisted", "Formats", "Refuse Terraform git sources elsewhere",
                        "Whether a proxied Terraform module whose git source cannot be fetched through this "
                                + "repository - its host is not in the git hosts, or it names no single ref - is "
                                + "refused rather than handed to the client to clone.",
                        Setting.Kind.BOOLEAN, "false", false));
    }
}
