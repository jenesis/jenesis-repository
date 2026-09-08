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
