/**
 * The Terraform / OpenTofu registry as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * the module and provider registry protocols, so {@code terraform init} and {@code tofu init} resolve from the shared
 * store. The first provider publish generates the repository's OpenPGP key through {@code format/signing}, and every
 * {@code SHA256SUMS} write derives a binary detached signature. Each version list and {@code SHA256SUMS} is a
 * {@link build.jenesis.repository.store.StoredListing} maintained on publish, its
 * {@link build.jenesis.repository.store.StoredListing.Generator} the repair path. A miss pulls through an upstream
 * registry, a provider zip held to its declared digest and a git-sourced module fetched from a listed host
 * ({@code jenrepo.terraform.git-hosts}) held to its first fetch's digest; an importer moves modules and providers off
 * another registry. Discovery, {@code /.well-known/terraform.json}, is outside every format's prefix and served
 * elsewhere.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.terraform {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.format.signing;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.blobs;
    // A module from a registry serving zips is re-packed as the tar this one serves.
    requires org.apache.commons.compress;
    requires org.slf4j;
    requires tools.jackson.databind;
    // Exported for the suites that drive the format directly.
    exports build.jenesis.repository.format.terraform;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.terraform.TerraformFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.terraform.TerraformListingObserver;
}
