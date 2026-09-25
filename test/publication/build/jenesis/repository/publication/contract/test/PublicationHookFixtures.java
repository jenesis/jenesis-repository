package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * Every publication-hook fixture, once: the census reads which hooks they cover, and {@link PublicationHookContractTest}
 * runs the contract over each. One list for both is what keeps a fixture from being counted and never run, and this
 * module requiring every format that ships a listing observer is what keeps a shipped observer from going uncounted -
 * nineteen of the twenty-two were, while the census read a graph carrying three formats.
 */
final class PublicationHookFixtures {

    /** Every format that ships a stored-listing observer, by the observer's class and the format module's package. */
    private static final List<String> LISTING_OBSERVERS = List.of(
            "apk.ApkListingObserver", "cargo.CargoListingObserver", "cocoapods.CocoaPodsListingObserver",
            "composer.ComposerListingObserver", "conan.ConanListingObserver", "conda.CondaListingObserver",
            "debian.DebianListingObserver", "gems.RubyGemsListingObserver", "go.GoListingObserver",
            "helm.HelmListingObserver", "huggingface.HuggingFaceListingObserver", "ivy.IvyListingObserver",
            "maven.MavenMetadataObserver", "npm.NpmListingObserver", "nuget.NuGetListingObserver",
            "oci.OciListingObserver", "pypi.PyPiListingObserver", "raw.RawListingObserver",
            "rpm.RpmListingObserver", "swift.SwiftListingObserver", "terraform.TerraformListingObserver",
            "winget.WingetListingObserver");

    private PublicationHookFixtures() {
    }

    /** A fresh instance of every fixture: the role and delivery archetypes, then every format's listing observer. */
    static List<PublicationHookFixture> all() {
        List<PublicationHookFixture> fixtures = new ArrayList<>(List.of(
                new IndexObserverFixture(), new FeedSplittingObserverFixture(), new OutboxObserverFixture(),
                new RecordingScreenFixture(), new WithholdingScreenFixture(), new AuditingScreenFixture(),
                new OverrideHookFixture()));
        for (String observer : LISTING_OBSERVERS) {
            String format = observer.substring(0, observer.indexOf('.'));
            fixtures.add(new ListingObserverFixture(format + "-listing",
                    "build.jenesis.repository.format." + observer));
        }
        return List.copyOf(fixtures);
    }
}
