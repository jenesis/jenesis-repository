package build.jenesis.repository.upstream.aws;

import module java.base;
import build.jenesis.repository.upstream.UpstreamTokenIssuer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;

/**
 * ECR and CodeArtifact authorization tokens, issued to this deployment's AWS identity.
 *
 * <p>An ECR registry host names its account and region ({@code <account>.dkr.ecr.<region>.amazonaws.com}), and its
 * token is already the {@code Basic} credential a registry client sends. A CodeArtifact host names the domain, its
 * owning account and the region ({@code <domain>-<owner>.d.codeartifact.<region>.amazonaws.com}), and its token is
 * sent as the password of the {@code aws} user, which every format CodeArtifact serves accepts. Both last up to
 * twelve hours; the credential source renews them before they lapse.
 *
 * <p>One client per service and region is built on first use and kept, since building one resolves the identity
 * chain and an HTTP client each time.
 */
public final class AwsTokenIssuer implements UpstreamTokenIssuer {

    private static final Pattern ECR =
            Pattern.compile("(\\d{12})\\.dkr\\.ecr(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?");

    private static final Pattern CODEARTIFACT =
            Pattern.compile("([a-z][a-z0-9-]*)-(\\d{12})\\.d\\.codeartifact\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?");

    private final Function<String, Optional<URI>> endpoints;
    private final AwsCredentialsProvider identity;
    private final ConcurrentHashMap<String, EcrClient> ecr = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodeartifactClient> codeartifact = new ConcurrentHashMap<>();

    /** The issuer a deployment discovers: AWS's own endpoints, and the SDK's default identity chain. */
    public AwsTokenIssuer() {
        this(_ -> Optional.empty(), DefaultCredentialsProvider.create());
    }

    /** The issuer over explicit endpoints by region and an explicit identity - the seam a test points at a stand-in
     *  for the AWS APIs. */
    public AwsTokenIssuer(Function<String, Optional<URI>> endpoints, AwsCredentialsProvider identity) {
        this.endpoints = endpoints;
        this.identity = identity;
    }

    @Override
    public String name() {
        return "aws";
    }

    @Override
    public boolean issues(String host) {
        return host != null && (ECR.matcher(host).matches() || CODEARTIFACT.matcher(host).matches());
    }

    @Override
    public Token issue(String host) throws IOException {
        try {
            Matcher registry = ECR.matcher(host);
            if (registry.matches()) {
                return ecr(registry.group(1), registry.group(2));
            }
            Matcher domain = CODEARTIFACT.matcher(host);
            if (domain.matches()) {
                return codeartifact(domain.group(1), domain.group(2), domain.group(3));
            }
        } catch (SdkException e) {
            throw new IOException("AWS refused a token for " + host + ": " + e.getMessage(), e);
        }
        throw new IllegalArgumentException(host + " is neither an ECR registry nor a CodeArtifact domain host.");
    }

    private Token ecr(String account, String region) throws IOException {
        EcrClient client = ecr.computeIfAbsent(region, _ -> {
            var builder = EcrClient.builder().region(Region.of(region)).credentialsProvider(identity)
                    .httpClient(UrlConnectionHttpClient.create());
            endpoints.apply(region).ifPresent(builder::endpointOverride);
            return builder.build();
        });
        List<AuthorizationData> issued = client.getAuthorizationToken(request -> request.registryIds(account))
                .authorizationData();
        if (issued.isEmpty()) {
            throw new IOException("ECR issued no token for registry " + account + " in " + region);
        }
        AuthorizationData data = issued.getFirst();
        return new Token("Authorization", "Basic " + data.authorizationToken(), data.expiresAt());
    }

    private Token codeartifact(String domain, String owner, String region) {
        CodeartifactClient client = codeartifact.computeIfAbsent(region, _ -> {
            var builder = CodeartifactClient.builder().region(Region.of(region)).credentialsProvider(identity)
                    .httpClient(UrlConnectionHttpClient.create());
            endpoints.apply(region).ifPresent(builder::endpointOverride);
            return builder.build();
        });
        var issued = client.getAuthorizationToken(request -> request.domain(domain).domainOwner(owner));
        String basic = Base64.getEncoder().encodeToString(
                ("aws:" + issued.authorizationToken()).getBytes(StandardCharsets.UTF_8));
        return new Token("Authorization", "Basic " + basic, issued.expiration());
    }
}
