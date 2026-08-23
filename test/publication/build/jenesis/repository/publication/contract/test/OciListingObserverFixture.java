package build.jenesis.repository.publication.contract.test;

/** The {@code build.jenesis.repository.format.oci.OciListingObserver} hook under the publication-hook contract. */
final class OciListingObserverFixture extends ListingObserverFixture {

    @Override
    public String hook() {
        return "oci-listing";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.oci.OciListingObserver";
    }
}
