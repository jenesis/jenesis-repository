/**
 * The Maven layout as a plugin module ({@code /maven/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Java-layout module
 * ({@code JavaLayout}) and the store module's format-neutral {@code Publication}. When a modular jar is published, it
 * cross-publishes the jar's module view into the Jenesis layout over the bridge the shared module exports to just these
 * two: it {@code uses} the {@code ModuleView} the Jenesis format provides. This is the one required cross-publish, and
 * it goes one way - Maven into the module layout, never a module back to Maven. Because the cross-view is derived from
 * the Maven coordinate rather than published beside it, this module also {@code provides} the {@code WalkConsumer}
 * ({@code ModuleViewRebuild}) that re-derives it from the durable store, which is what makes a cross-publish
 * interrupted half way a repairable state rather than a permanent one. {@code MavenMetadata} is generated on read
 * here. Discovered through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j 2.0.18
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format.maven {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.format.java;
    requires java.xml;
    // The metadata leg's clause-2 refusal says in the log which upstream target could not be asked and how,
    // beside the 502 it answers the resolver - the operator-visible half a status code alone cannot carry.
    requires org.slf4j;
    exports build.jenesis.repository.format.maven;
    uses build.jenesis.repository.format.java.bridge.ModuleView;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.maven.MavenMetadataObserver;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.format.maven.ModuleViewRebuild;
}
