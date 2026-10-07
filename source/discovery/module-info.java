/**
 * Repository discovery by the domain's own file: where a module's or a Maven group's files are, as the owner of the
 * domain its name reverses into says at {@code https://<domain>/.well-known/java-repository.properties} - the file
 * format proposed for any Java tool, read here for a repository's {@code discovered} legs.
 * {@link build.jenesis.repository.discovery.DiscoveryFile} reads and checks one file,
 * {@link build.jenesis.repository.discovery.Domains} names the domains a name is asked of, and
 * {@link build.jenesis.repository.discovery.RepositoryDiscovery} answers where a request path's file is, remembering
 * each domain's answer for {@code discovery-ttl}. Maven's version order, which a key's {@code .since} is read in, is
 * the resolver's own.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.discovery {
    requires build.jenesis.repository.net.http;
    requires build.jenesis.repository.settings;
    requires org.apache.maven.resolver.util;
    requires java.net.http;
    requires java.xml;
    exports build.jenesis.repository.discovery;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.discovery.DiscoverySettingsContributor;
}
