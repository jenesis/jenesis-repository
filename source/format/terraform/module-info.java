/**
 * The Terraform / OpenTofu registry as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for both of the protocols a client speaks - the module
 * registry and the provider registry - so {@code terraform init} and {@code tofu init} resolve from the shared
 * store.
 *
 * <p><b>Two protocols, one format.</b> A module is {@code <namespace>/<name>/<system>} and resolves through a
 * version list and a {@code download} that answers {@code 204} with an {@code X-Terraform-Get} header; a provider
 * is {@code <namespace>/<type>} whose releases are per-platform zips, resolved through a version list naming the
 * platforms held and a package document naming the zip, its {@code SHA256SUMS}, that file's signature and the
 * public key. OpenTofu speaks the same two, so one format serves both clients.
 *
 * <p><b>This is the format that brings signing material with it.</b> A provider release is installable only if the
 * client can verify the checksums that name its zip's digest, so the first provider publish generates the
 * repository's OpenPGP key through {@code format/signing} - the same key handling the Debian and RPM formats use -
 * and every {@code SHA256SUMS} write derives a fresh detached signature. That signature is <b>binary</b> rather
 * than armoured, measured against {@code registry.terraform.io} rather than assumed.
 *
 * <p><b>The indexes are maintained, not generated.</b> Each version list and each {@code SHA256SUMS} is a
 * {@link build.jenesis.repository.store.StoredListing} whose entry a publish re-decides and whose
 * {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path; a
 * {@code PublicationObserver} keeps them in step with a hold, a release, a lifecycle mark and a removal.
 *
 * <p><b>{@code /v1/} is Terraform's prefix, not ours</b>, adopted for the same reason as OCI's {@code /v2/} and
 * NuGet's {@code /v3/}: a foreign specification fixes it and its clients hardcode it. The artifacts are stored
 * outside it, because the protocol leaves a download URL to the registry.
 *
 * <p><b>A miss pulls through an upstream registry</b>, found the way a client finds one: a provider zip is held to
 * the digest its package document declares, and a module's archive is cached as fetched. Most public modules name a
 * git repository rather than an archive; one on a host the operator lists ({@code jenreg.terraform.git-hosts}) is
 * fetched as that ref's archive and held to the digest of its first fetch, so a moved tag is refused - see
 * {@code TerraformGitSource}. An importer moves modules and providers off another registry. Discovery is separate -
 * {@code terraform init} finds a registry by fetching {@code /.well-known/terraform.json} from the host root, which
 * is outside every format's prefix.
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
    // A module moved off a registry that serves zips is re-packed as the tar this one serves.
    requires org.apache.commons.compress;
    requires org.slf4j;
    requires tools.jackson.databind;
    // Exported to test modules only. The unit suite is named here because its assertions are about
    // the path grammar - a pure function of a string, which belongs in the fastest lane.
    exports build.jenesis.repository.format.terraform to
            build.jenesis.repository.format.contract.ecosystem.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.format.terraform.test,
            build.jenesis.repository.compliance.terraform.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.terraform.TerraformFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.terraform.TerraformListingObserver;
}
