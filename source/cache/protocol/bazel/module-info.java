/**
 * Bazel's native HTTP remote cache as its own module: the action cache and the content-addressed store, which
 * disagree about what a repeated write means and therefore answer different write policies per request.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.protocol.bazel {
    requires build.jenesis.repository.cache.protocol;
    exports build.jenesis.repository.cache.protocol.bazel;
    provides build.jenesis.repository.cache.protocol.CacheProtocol
            with build.jenesis.repository.cache.protocol.bazel.BazelCacheProtocol;
}
