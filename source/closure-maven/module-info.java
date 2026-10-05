/**
 * A Maven release's closure resolved by Maven Resolver, as Maven resolves it - parents, imported BOMs, properties,
 * {@code dependencyManagement}, version mediation and exclusions - over the POMs the repository holds and nothing
 * else: the resolver is handed a workspace reader over the store, an offline session and no transport, so it cannot
 * reach an upstream, and a range is resolved against the versions the repository holds and serves.
 *
 * @jenesis.release 25
 * @jenesis.alias org.apache.maven.resolver.supplier org.apache.maven.resolver/maven-resolver-supplier-mvn3
 * @jenesis.exclude org.apache.maven.resolver.supplier org.apache.maven.resolver/maven-resolver-transport-apache
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.closure.maven {
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.store;
    requires org.apache.maven.resolver;
    requires org.apache.maven.resolver.spi;
    requires org.apache.maven.resolver.util;
    requires org.apache.maven.resolver.supplier;
    requires org.slf4j;
    provides build.jenesis.repository.closure.EcosystemClosure
            with build.jenesis.repository.closure.maven.MavenClosure;
}
