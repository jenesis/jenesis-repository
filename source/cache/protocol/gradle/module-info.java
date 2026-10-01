/**
 * Gradle's HTTP build cache as its own module, so a node serves Gradle builds by carrying it and not otherwise.
 * The key is hashed into a shard and a digest, and the identity rides in Basic - the user name is the project and
 * the password is the key, which is the whole of what Gradle's client can be configured to send.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.protocol.gradle {
    requires build.jenesis.repository.cache.protocol;
    exports build.jenesis.repository.cache.protocol.gradle;
    provides build.jenesis.repository.cache.protocol.CacheProtocol
            with build.jenesis.repository.cache.protocol.gradle.GradleCacheProtocol;
}
