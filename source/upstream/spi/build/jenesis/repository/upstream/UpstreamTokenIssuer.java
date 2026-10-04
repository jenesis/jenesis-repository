package build.jenesis.repository.upstream;

import module java.base;
import build.jenesis.repository.store.Providers;

/**
 * Mints the short-lived credential a cloud registry expects instead of a static secret - an AWS ECR or CodeArtifact
 * authorization token issued to the deployment's own cloud identity, valid for hours. A pasted header would expire
 * before anyone pasted the next, so an issued credential stores which issuer serves a host, never a secret, and the
 * credential source mints and renews the token. An issuer is discovered with {@link ServiceLoader} and chosen by the
 * host it serves, reading account, region and domain out of the host.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} and {@link #issues} are pure; {@link #issue} is called from any request
 *       thread, concurrently for different hosts. The credential source serialises minting per host.</li>
 *   <li><b>Idempotency / replay.</b> {@link #issue} may be called any time and answers a fresh, independently valid
 *       token; issuing never revokes another.</li>
 *   <li><b>Absence.</b> With no issuer installed, setting an issued credential is refused naming the host, never stored
 *       to fail later.</li>
 *   <li><b>Selection.</b> Every installed issuer contributes ({@code ALL}); the one that {@link #issues} a host serves
 *       it. Two claiming one host is a packaging error, and {@link #serving} throws naming both.</li>
 *   <li><b>Lifecycle / ownership.</b> Whoever resolves the issuers holds them for its own life rather than resolving
 *       them per token, and {@link #close() closes} each when it closes: an issuer releases the clients it opened to
 *       mint, and is asked for nothing after.</li>
 *   <li><b>Error visibility.</b> {@link #issue} throws {@link IOException} naming the host and cause when the identity
 *       is missing or refused, since a proxy fetching without its token answers an anonymous {@code 401}.</li>
 *   <li><b>Network.</b> {@link #issue} is the one outbound call, to the cloud provider's token endpoint, made only for
 *       a host an operator set an issued credential for.</li>
 *   <li><b>Tenant scoping.</b> None: a token authenticates the deployment's own outbound fetches.</li>
 * </ol>
 */
public interface UpstreamTokenIssuer extends AutoCloseable {

    /** The name an issued credential records, e.g. {@code aws}. */
    String name();

    /** Whether this issuer mints tokens for {@code host}. */
    boolean issues(String host);

    /** A fresh token for {@code host}. */
    Token issue(String host) throws IOException;

    /** Release the clients this issuer opened to mint; its owner closes it once, when it closes. */
    @Override
    void close();

    /** One minted credential: the header to send, its value, and when it stops being accepted. */
    record Token(String header, String value, Instant expires) {

        public Token {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(expires, "expires");
        }
    }

    /** Every installed issuer, ordered by name. */
    static List<UpstreamTokenIssuer> installed() {
        return Providers.all("upstream-token-issuer",
                ServiceLoader.load(UpstreamTokenIssuer.class),
                UpstreamTokenIssuer::name,
                _ -> true,
                Optional::of);
    }

    /** The issuer among {@code issuers} called {@code name}, or empty. */
    static Optional<UpstreamTokenIssuer> named(List<UpstreamTokenIssuer> issuers, String name) {
        return issuers.stream().filter(issuer -> issuer.name().equals(name)).findFirst();
    }

    /** The one issuer among {@code issuers} serving {@code host}, or empty; two claiming it throws. */
    static Optional<UpstreamTokenIssuer> serving(List<UpstreamTokenIssuer> issuers, String host) {
        List<UpstreamTokenIssuer> serving = issuers.stream().filter(issuer -> issuer.issues(host)).toList();
        if (serving.size() > 1) {
            throw new IllegalStateException("More than one upstream token issuer serves " + host + ": "
                    + serving.stream().map(UpstreamTokenIssuer::name).toList());
        }
        return serving.stream().findFirst();
    }
}
