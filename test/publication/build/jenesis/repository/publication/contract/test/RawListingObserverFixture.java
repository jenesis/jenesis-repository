package build.jenesis.repository.publication.contract.test;

/** The {@code build.jenesis.repository.format.raw.RawListingObserver} hook under the publication-hook contract. */
final class RawListingObserverFixture extends ListingObserverFixture {

    @Override
    public String hook() {
        return "raw-listing";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.raw.RawListingObserver";
    }
}
