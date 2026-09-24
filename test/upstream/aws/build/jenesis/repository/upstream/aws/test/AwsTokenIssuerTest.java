package build.jenesis.repository.upstream.aws.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.upstream.UpstreamTokenIssuer;
import build.jenesis.repository.upstream.aws.AwsTokenIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * ECR and CodeArtifact tokens from a stand-in for both APIs, so the suite exercises the SDK's real request signing
 * and response parsing without reaching AWS.
 */
class AwsTokenIssuerTest {

    private static final String ECR_HOST = "123456789012.dkr.ecr.eu-west-1.amazonaws.com";
    private static final String CODEARTIFACT_HOST = "packages-210987654321.d.codeartifact.us-east-2.amazonaws.com";
    private static final long EXPIRES = 1_790_000_000L;

    private WireMockServer aws;
    private UpstreamTokenIssuer issuer;

    @BeforeEach
    void start() {
        aws = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        aws.start();
        URI endpoint = URI.create("http://localhost:" + aws.port());
        issuer = new AwsTokenIssuer(_ -> Optional.of(endpoint),
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")));
    }

    @AfterEach
    void stop() {
        aws.stop();
    }

    @Test
    void it_serves_ecr_registries_and_codeartifact_domains_and_nothing_else() {
        assertThat(issuer.issues(ECR_HOST)).isTrue();
        assertThat(issuer.issues("123456789012.dkr.ecr-fips.us-gov-west-1.amazonaws.com")).isTrue();
        assertThat(issuer.issues(CODEARTIFACT_HOST)).isTrue();
        assertThat(issuer.issues("registry-1.docker.io")).isFalse();
        assertThat(issuer.issues("evil.amazonaws.com.attacker.example")).isFalse();
    }

    @Test
    void an_ecr_token_is_the_basic_credential_for_the_account_the_host_names() throws IOException {
        aws.stubFor(post(urlEqualTo("/"))
                .withHeader("X-Amz-Target", equalTo("AmazonEC2ContainerRegistry_V20150921.GetAuthorizationToken"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-amz-json-1.1")
                        .withBody("{\"authorizationData\":[{\"authorizationToken\":\"QVdTOnBhc3N3b3Jk\","
                                + "\"expiresAt\":" + EXPIRES + ",\"proxyEndpoint\":\"https://" + ECR_HOST + "\"}]}")));

        UpstreamTokenIssuer.Token token = issuer.issue(ECR_HOST);

        assertThat(token.header()).isEqualTo("Authorization");
        assertThat(token.value()).isEqualTo("Basic QVdTOnBhc3N3b3Jk");
        assertThat(token.expires()).isEqualTo(Instant.ofEpochSecond(EXPIRES));
        aws.verify(postRequestedFor(urlEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.registryIds[0]", equalTo("123456789012")))
                .withHeader("Authorization", containing("/eu-west-1/ecr/aws4_request")));
    }

    @Test
    void a_codeartifact_token_is_the_password_of_the_aws_user_for_the_domain_the_host_names() throws IOException {
        aws.stubFor(post(urlPathEqualTo("/v1/authorization-token"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"authorizationToken\":\"domain-token\",\"expiration\":" + EXPIRES + "}")));

        UpstreamTokenIssuer.Token token = issuer.issue(CODEARTIFACT_HOST);

        assertThat(token.value()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                "aws:domain-token".getBytes(StandardCharsets.UTF_8)));
        assertThat(token.expires()).isEqualTo(Instant.ofEpochSecond(EXPIRES));
        aws.verify(postRequestedFor(urlPathEqualTo("/v1/authorization-token"))
                .withQueryParam("domain", equalTo("packages"))
                .withQueryParam("domain-owner", equalTo("210987654321"))
                .withHeader("Authorization", containing("/us-east-2/codeartifact/aws4_request")));
    }

    @Test
    void a_refused_identity_is_an_io_failure_naming_the_host() {
        aws.stubFor(post(urlEqualTo("/")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/x-amz-json-1.1")
                .withBody("{\"__type\":\"AccessDeniedException\",\"message\":\"not authorized\"}")));

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> issuer.issue(ECR_HOST))
                .withMessageContaining(ECR_HOST);
    }
}
