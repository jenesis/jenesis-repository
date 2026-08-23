package build.jenesis.repository.publication.contract.test;

/** The {@code build.jenesis.repository.format.maven.MavenMetadataObserver} hook under the publication-hook contract. */
final class MavenMetadataObserverFixture extends ListingObserverFixture {

    @Override
    public String hook() {
        return "maven-metadata-listing";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.maven.MavenMetadataObserver";
    }
}
