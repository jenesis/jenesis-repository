/**
 * The collection pass as a walk consumer: it runs the installed collector at the end of a walk the deployment already
 * pays for. It is separate from the collector, whose strategy configuration chooses, and from the walk, which a
 * deployment that does not collect still uses.
 *
 * <p>The pointer roots it hands the collector are {@code publish} plus what each installed format lends; a deployment
 * that keeps a durable record of the ecosystems it has seen contributes a {@code GcRoots} that can also refuse, for
 * content a format stored that this deployment does not install, where a plain union would delete a live blob.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.gc.walk {
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.gc.walk;
    uses build.jenesis.repository.gc.walk.GcRoots;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.gc.walk.GcConsumer;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.gc.walk.CollectionSettingsContributor;
}
