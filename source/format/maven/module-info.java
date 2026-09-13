/**
 * The Maven layout as a plugin module ({@code /maven/...}): it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} and builds on the shared Java-layout module
 * ({@code JavaLayout}) and the store module's format-neutral {@code Publication}. When a modular jar is published, it
 * cross-publishes the jar's module view into the Jenesis layout over the bridge the shared module exports to just these
 * two: it {@code uses} the {@code ModuleView} the Jenesis format provides. This is the one required cross-publish, and
 * it goes one way - Maven into the module layout, never a module back to Maven. Because the cross-view is derived from
 * the Maven coordinate rather than published beside it, this module also {@code provides} the {@code WalkConsumer}
 * ({@code ModuleViewRebuild}) that re-derives it from the durable store, which is what makes a cross-publish
 * interrupted half way a repairable state rather than a permanent one. {@code MavenMetadata} is computed here under
 * an opt-in setting. Discovered through {@code provides}, so the layout plugs in like any other format.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.maven.MavenFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.maven.MavenMetadataObserver;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.format.maven.ModuleViewRebuild;
}
