/**
 * Upstream tokens for AWS registries: an {@link build.jenesis.repository.upstream.UpstreamTokenIssuer} answering to
 * {@code aws} that mints an ECR registry's or a CodeArtifact domain's authorization token from this deployment's own
 * AWS identity - the environment, a profile, an instance or container role, found by the SDK's default chain. The
 * account, region and domain are read from the upstream host, so setting the {@code aws} scheme for
 * {@code <account>.dkr.ecr.<region>.amazonaws.com} or {@code <domain>-<owner>.d.codeartifact.<region>.amazonaws.com}
 * is the whole configuration, and no secret is ever stored.
 *
 * @jenesis.release 25
 * @jenesis.exclude software.amazon.awssdk.services.ecr io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.exclude software.amazon.awssdk.services.codeartifact io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.upstream.aws {
    requires build.jenesis.repository.upstream;
    requires software.amazon.awssdk.services.ecr;
    requires software.amazon.awssdk.services.codeartifact;
    requires software.amazon.awssdk.core;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.http.urlconnection;
    exports build.jenesis.repository.upstream.aws to build.jenesis.repository.upstream.aws.test;
    provides build.jenesis.repository.upstream.UpstreamTokenIssuer
            with build.jenesis.repository.upstream.aws.AwsTokenIssuer;
}
