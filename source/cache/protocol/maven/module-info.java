/**
 * The Apache Maven Build Cache Extension's layout as its own module. The project rides in the path because the
 * extension sends only its configured URL, and the credential is the Basic password Maven Resolver already
 * sends; both key components are hashed, since a coordinate and a build id are not hex.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.protocol.maven {
    requires build.jenesis.repository.cache.protocol;
    exports build.jenesis.repository.cache.protocol.maven;
    provides build.jenesis.repository.cache.protocol.CacheProtocol
            with build.jenesis.repository.cache.protocol.maven.MavenCacheProtocol;
}
