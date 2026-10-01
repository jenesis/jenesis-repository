/**
 * The RubyGems format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /rubygems/...} - the compact index, a raw {@code .gem} push, downloads, and pull-through of an upstream
 * compact index - reading the gem with Commons Compress and its gemspec with SnakeYAML.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.gems {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires org.yaml.snakeyaml;
    requires build.jenesis.repository.settings;
    // A push with attestations is a multipart form; the attestations array is read one bundle per element.
    requires build.jenesis.repository.multipart;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.gems to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.gateway.contract.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.gems.RubyGemsFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.gems.RubyGemsListingObserver;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.gems.RubyGemsSettingsContributor;
}
