/**
 * Focused unit tests for the shared Java-layout primitives: reading the module name a jar declares (its
 * {@code Automatic-Module-Name} or {@code module-info}, else none) and parsing a {@code /maven/...} request path into
 * its {@code [groupId, artifactId, version]} coordinate. Pure functions over in-memory jars and paths, so this module
 * needs no store.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.format.java
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.format.java.test {
    requires build.jenesis.repository.format.java;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
