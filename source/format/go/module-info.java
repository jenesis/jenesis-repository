/**
 * The Go module proxy format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for the
 * {@code /go/...} GOPROXY layout, serving a module's {@code .info}, {@code .mod} and {@code .zip} and the version list
 * and accepting them by {@code PUT}.
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
    exports build.jenesis.repository.format.go;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.go.GoFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.go.GoListingObserver;
}
