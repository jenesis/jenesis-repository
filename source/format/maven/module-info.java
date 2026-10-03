/**
 * The Maven layout as a plugin module ({@code /maven/...}): a {@link build.jenesis.repository.format.RepositoryFormat}
 * over the shared Java-layout module and the store's {@code Publication}. A published modular jar is cross-published
 * into the Jenesis module layout through the {@code ModuleView} bridge, one way only; the {@code WalkConsumer}
 * {@code ModuleViewRebuild} re-derives that view from the store, so an interrupted cross-publish is repairable.
 * {@code maven-metadata.xml} is computed here under an opt-in setting.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.maven {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.format.java;
    requires build.jenesis.repository.format.jvm;
    requires java.xml;
    // The metadata leg logs which upstream target could not be asked beside the 502 it answers.
    requires org.slf4j;
    exports build.jenesis.repository.format.maven;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.maven.MavenMetadataObserver;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.format.maven.ModuleViewRebuild;
}
