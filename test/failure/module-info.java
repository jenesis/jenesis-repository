/**
 * Tests for the failure reference: that a failure is logged once with its trace under a reference, and that what
 * Spring's error dispatch answers carries the reference and none of the failure's insides.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.failure
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.failure.test {
    requires build.jenesis.repository.failure;
    requires jakarta.servlet;
    requires spring.web;
    requires spring.boot;
    requires spring.boot.webmvc;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires org.mockito;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
