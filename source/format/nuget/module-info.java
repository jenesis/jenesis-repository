/**
 * The NuGet v3 format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat} for
 * the {@code /nuget/...} layout - the service index, a multipart {@code .nupkg} push, the flat-container version list
 * and downloads, and the registration index (so {@code dotnet restore} resolves version ranges and the transitive
 * graph, reading each version's {@code dependencyGroups} from its {@code .nuspec}). The multipart push body is read
 * through the shared streaming reader ({@code build.jenesis.repository.multipart}, the one the PyPI upload and the
 * console's settings import also walk), the {@code .nuspec} with the JDK XML, and JSON is emitted with Jackson.
 * Discovered through {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.nuget {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires java.xml;
    requires build.jenesis.repository.multipart;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.nuget to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.nuget.NuGetFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.nuget.NuGetListingObserver;
}
