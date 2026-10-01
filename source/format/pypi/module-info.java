/**
 * The PyPI format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for {@code /pypi/...},
 * accepting twine's multipart upload through {@code build.jenesis.repository.multipart} and serving the PEP 503 simple
 * index from stored pages the upload maintains.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.format.pypi {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires java.xml;
    requires build.jenesis.repository.multipart;
    // PEP 740 attestations are JSON, each turned into the Sigstore bundle the verifier reads.
    requires tools.jackson.databind;
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.format.pypi;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.pypi.PyPiFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.pypi.PyPiListingObserver;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.format.pypi.PyPiSettingsContributor;
}
