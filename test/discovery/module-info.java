/**
 * Repository discovery read in process: the file's grammar and every refusal it lists, the domains a name is asked
 * of, and where each request path's file is located - over a transport answering from a table, so which domains
 * were asked, and how often, is part of what the suites read.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.discovery
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.discovery.test {
    requires build.jenesis.repository.discovery;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
