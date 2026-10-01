/**
 * The NuGet v3 format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /nuget/...} - the service index, a multipart {@code .nupkg} push, the flat-container version list and
 * downloads, and the registration index, so {@code dotnet restore} resolves version ranges and the transitive graph.
 * The {@code .nuspec} is read with the JDK XML and JSON written with Jackson.
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
    requires build.jenesis.repository.xml;
    requires build.jenesis.repository.multipart;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.nuget;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.nuget.NuGetFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.nuget.NuGetListingObserver;
}
