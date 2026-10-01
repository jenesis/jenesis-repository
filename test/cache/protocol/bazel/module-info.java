/**
 * Unit test for Bazel's cache protocol: the two namespaces it claims, the write policy each answers, and the
 * separation between them that keeps one hash value in both spaces from becoming one stored entry.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.protocol.bazel
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.protocol.bazel.test {
    requires build.jenesis.repository.cache.protocol;
    requires build.jenesis.repository.cache.protocol.bazel;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
