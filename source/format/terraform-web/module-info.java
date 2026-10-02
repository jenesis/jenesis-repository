/**
 * The Terraform service-discovery document, {@code GET /.well-known/terraform.json}, the one path a Terraform or
 * OpenTofu client fetches before it knows anything else about a registry. It cannot live under a format's prefix,
 * since Terraform derives the URL from the host of a source address, so it is a server-level contribution through
 * {@code ServerModuleProvider}. Without this module a deployment still serves both registry protocols to a client
 * addressing them directly, and is not discoverable by {@code terraform init}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.terraform.web {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.server.kernel;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.web;
    exports build.jenesis.repository.format.terraform.web;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.terraform.web.TerraformSettingsContributor;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.format.terraform.web.TerraformDiscoveryModule;
}
