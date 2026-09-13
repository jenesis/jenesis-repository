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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
