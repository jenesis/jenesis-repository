/**
 * The AWS token issuer against a stand-in for the ECR and CodeArtifact APIs: which hosts it serves, the request it
 * signs for a registry or a domain read out of the host, the credential each token becomes, and the failure a
 * refused identity is reported as.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.upstream.aws
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.upstream.aws.test {
    requires build.jenesis.repository.upstream;
    requires build.jenesis.repository.upstream.aws;
    requires software.amazon.awssdk.auth;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
