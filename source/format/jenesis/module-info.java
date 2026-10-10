/**
 * The Jenesis module layout ({@code /module/...}, {@code /artifact/...}): a
 * {@link build.jenesis.repository.format.RepositoryFormat} over the store module's {@code Publication}, providing the
 * {@code ModuleView} the Maven format uses to give a modular jar its module view - one way, Maven into the module
 * layout - and pulling a module's files through from a module service or a discovered location.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.jenesis {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.java;
    requires org.slf4j;
    exports build.jenesis.repository.format.jenesis to build.jenesis.repository.format.jenesis.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.jenesis.JenesisFormat;
    provides build.jenesis.repository.format.java.bridge.ModuleView
            with build.jenesis.repository.format.jenesis.ModuleViewPublisher;
}
