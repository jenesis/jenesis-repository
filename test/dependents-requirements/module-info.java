/**
 * The requirement grammars in isolation: whether a requirement as each ecosystem writes it admits a version, read by
 * the published library that ecosystem's tooling uses, and the version order a closure takes the newest admitted
 * version by.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.dependents.requirements
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.dependents.requirements.test {
    requires build.jenesis.repository.closure;
    requires build.jenesis.repository.dependents.requirements;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
