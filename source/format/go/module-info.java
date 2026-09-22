/**
 * The Go module proxy format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /go/...} GOPROXY layout, serving a
 * module's {@code .info} / {@code .mod} / {@code .zip} and the version list, and accepting them by {@code PUT}.
 * Pure JDK. Discovered through {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.go {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    // The ecosystem suite reads GoDirhash and the escaping directly; the cross-format census additionally reads
    // GoChecksumDatabase, because jenreg.go.sumdb is one of the edition's two operator-configured outbound roots and
    // the census that names them is the one that has to prove this leg reaches their shared screen.
    exports build.jenesis.repository.format.go to build.jenesis.repository.gateway.go.test,
            build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.go.GoFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.go.GoListingObserver;
}
