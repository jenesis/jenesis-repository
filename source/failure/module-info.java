/**
 * What the product answers when something fails that it did not mean to refuse ({@code Failures}): a sentence a
 * person can read, and a reference that finds the whole failure in the server's log.
 *
 * <p>A module of its own because every surface answers failures - the API and the format endpoints, the console, the
 * error pages Spring renders for all of them - and none of them may require another. Each asks this one place to
 * record a failure, so the log line an operator searches for is written the same way whichever surface failed, and no
 * surface is left writing an exception's message, class or stack into a response.
 *
 * <p>It also carries the error page every Boot composition renders, as an auto-configuration: whatever reaches Spring's
 * error dispatch unhandled is answered by {@code ProblemErrorController} over {@code ReferencedErrorAttributes} - an
 * RFC 9457 problem document for a program, the console's error page for a browser - with the reference and nothing
 * of the failure's insides.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.failure {
    requires org.slf4j;
    requires jakarta.servlet;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.webmvc;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.webmvc;
    exports build.jenesis.repository.failure;
}
