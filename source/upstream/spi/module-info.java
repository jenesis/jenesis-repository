/**
 * The upstream-credential contracts: the header source the pull-through proxies consult per fetch to reach a private
 * registry, the {@code ServiceLoader} SPI a backing module implements, and the issuers minting the short-lived tokens a
 * cloud registry takes instead of a static secret. Without a module the none source stands in.
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
