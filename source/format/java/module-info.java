/**
 * The shared Java repository-layout primitives the Maven and Jenesis formats build on: reading a jar's module name and
 * parsing a Maven request path ({@link build.jenesis.repository.format.java.JavaLayout}), plus the cross-publish bridge
 * ({@link build.jenesis.repository.format.java.bridge}) - the {@code ModuleView} contract by which the Maven layout
 * hands a modular jar to the Jenesis layout - exported only to those two modules, off the public
 * {@code RepositoryFormat} SPI.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.java {
    requires transitive build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    uses build.jenesis.repository.format.java.bridge.ModuleView;
    provides build.jenesis.repository.format.CombinedFormat
            with build.jenesis.repository.format.java.JavaRepository;
    exports build.jenesis.repository.format.java;
    exports build.jenesis.repository.format.java.bridge
            to build.jenesis.repository.format.maven, build.jenesis.repository.format.jenesis;
}
