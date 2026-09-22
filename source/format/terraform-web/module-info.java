/**
 * The Terraform service-discovery document, as a module of its own: {@code GET /.well-known/terraform.json}, the
 * one path a Terraform or OpenTofu client fetches before it knows anything else about a registry.
 *
 * <p>It is separate from the format because it cannot live under a format's prefix. Terraform derives the discovery
 * URL from the <em>host</em> of a source address - {@code example.com/acme/random} is fetched from
 * {@code https://example.com/.well-known/terraform.json} - and a source address has no room for a path. Serving it
 * is therefore a server-level contribution, made through {@code ServerModuleProvider} like every other endpoint
 * family that is not a format's own.
 *
 * <p>Being separate is also what makes it optional: a deployment that leaves this module out still serves both
 * registry protocols to anything addressing them directly, and is simply not discoverable by {@code terraform init}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.format.terraform.web {
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.server.kernel;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.web;
    exports build.jenesis.repository.format.terraform.web to build.jenesis.repository.server.kernel.test;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.terraform.web.TerraformSettingsContributor;
    provides build.jenesis.repository.server.kernel.ServerModuleProvider
            with build.jenesis.repository.format.terraform.web.TerraformDiscoveryModule;
}
