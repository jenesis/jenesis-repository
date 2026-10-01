/**
 * Unit test for Gradle's cache protocol: the paths it claims, the shard and digest it reads a key into, and the
 * Basic presentation its identity rides in.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.protocol.gradle
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.protocol.gradle.test {
    requires build.jenesis.repository.cache.protocol;
    requires build.jenesis.repository.cache.protocol.gradle;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
