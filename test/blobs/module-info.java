/**
 * The falsifier for {@link build.jenesis.repository.blobs.Blobs#establish}: that a value which must exist exactly
 * once really does, under the concurrency that broke the mechanism it replaced.
 *
 * <p>It is its own module rather than a class in a bigger suite because what it needs is a store and nothing else -
 * no server, no format, no container - and because the property it guards belongs to the primitive rather than to
 * any one of the four formats that provision a signing key through it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.blobs
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.blobs.test {
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.store;
    // On the module path for the ServiceLoader, not for its packages: the backend is reached through the SPI
    // home's resolve(), which is the only way this build calls a ServiceLoader.
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
