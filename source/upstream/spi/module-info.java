/**
 * The upstream-credential contracts: the header source the pull-through proxies consult per fetch to reach a
 * private registry, the {@code ServiceLoader} SPI a backing module implements to supply it, and the issuers that
 * mint the short-lived tokens a cloud registry takes in place of a static secret. With no module
 * installed the none source stands in - no credential is ever attached and the management surface says so.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.upstream {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.upstream;
    uses build.jenesis.repository.upstream.UpstreamCredentialSourceProvider;
    uses build.jenesis.repository.upstream.UpstreamTokenIssuer;
}
