/**
 * The collection pass as a walk consumer: it is what turns an installed collector into storage actually being
 * reclaimed, by running it at the end of a walk the deployment already pays for.
 *
 * <p>Separate from the collector itself for the reason every plugin here is separate: the strategy is chosen by
 * configuration, and the thing that drives it should not decide which one that is. Separate from the walk for the
 * same reason in reverse - a walk that had to know about collection could not be used by a deployment that does not
 * collect.
 *
 * <p>The pointer roots it hands the collector are {@code publish} plus what each installed format lends, and a
 * deployment that keeps a durable record of the ecosystems it has seen contributes a {@code GcRoots} that can
 * additionally refuse: the case a plain union cannot see is content stored for a format nobody has installed any
 * more, where a sweep would delete a live blob.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j 2.0.18
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.gc.walk {
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires org.slf4j;
    exports build.jenesis.repository.gc.walk;
    uses build.jenesis.repository.gc.walk.GcRoots;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.gc.walk.GcConsumer;
}
