package build.jenesis.repository.upstream;

import module java.base;
import build.jenesis.repository.store.Providers;

/**
 * Mints the short-lived credential a cloud registry expects in place of a static secret - an AWS ECR or CodeArtifact
 * authorization token, issued to the deployment's own cloud identity and valid for hours. A private upstream that
 * only accepts such tokens cannot be proxied with a header pasted into a setting, because the header stops working
 * before anyone pastes the next one; an issued credential stores which issuer serves a host, never a secret, and the
 * credential source mints and renews the token itself.
 *
 * <p>An issuer is discovered with {@link ServiceLoader} and chosen by the host it serves, so the host a credential is
 * set for is the whole configuration: the issuer reads the account, region and domain out of it.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #issues} are pure; {@link #issue} is called from any
 *     request thread and must be safe to call concurrently for different hosts. The credential source serialises
 *     minting per host, so an issuer never has to.</li>
 * <li><b>Idempotency / replay.</b> {@link #issue} may be called again at any time and answers a fresh, independently
 *     valid token; issuing one never revokes another.</li>
 * <li><b>Absence.</b> No issuer installed means no host can take an issued credential: setting one is refused naming
 *     the host, never stored to fail later.</li>
 * <li><b>Selection.</b> Every installed issuer contributes ({@code ALL}); the one that {@link #issues} a host serves
 *     it. Two issuers claiming one host is a packaging error and {@link #serving} throws naming both, rather than
 *     letting module-path order decide which cloud identity a proxy authenticates as.</li>
 * <li><b>Error visibility.</b> {@link #issue} throws {@link IOException} naming the host and the cause when the
 *     identity is missing or refused; nothing is swallowed, because a proxy that silently fetches without its token
 *     answers a {@code 401} that names nothing.</li>
 * <li><b>Network.</b> {@link #issue} is the one outbound call, to the cloud provider's token endpoint, and it is made
 *     only for a host an operator set an issued credential for.</li>
 * <li><b>Tenant scoping.</b> None: like every upstream credential, a token authenticates the deployment's own
 *     outbound fetches.</li>
 * </ol>
 */
public interface UpstreamTokenIssuer {

    /** The name an issued credential records, e.g. {@code aws}. */
    String name();

    /** Whether this issuer mints tokens for {@code host}. */
    boolean issues(String host);

    /** A fresh token for {@code host}. */
    Token issue(String host) throws IOException;

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

    /** The installed issuer called {@code name}, or empty. */
    static Optional<UpstreamTokenIssuer> named(String name) {
        return installed().stream().filter(issuer -> issuer.name().equals(name)).findFirst();
    }

    /** The one installed issuer serving {@code host}, or empty; two claiming it throws. */
    static Optional<UpstreamTokenIssuer> serving(String host) {
        List<UpstreamTokenIssuer> serving = installed().stream().filter(issuer -> issuer.issues(host)).toList();
        if (serving.size() > 1) {
            throw new IllegalStateException("More than one upstream token issuer serves " + host + ": "
                    + serving.stream().map(UpstreamTokenIssuer::name).toList());
        }
        return serving.stream().findFirst();
    }
}
