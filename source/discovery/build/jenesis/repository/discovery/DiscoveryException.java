package build.jenesis.repository.discovery;

import module java.base;

/**
 * A domain's discovery file that cannot be honoured - a grammar the proposal refuses, a latest link that names no
 * version or leads somewhere its template does not describe, a certificate that does not verify - naming the file. It
 * is never read as an absent file: an absent file leaves a request to the repository's other legs, while one of these
 * says the domain published something wrong.
 */
public final class DiscoveryException extends RuntimeException {

    public DiscoveryException(String message) {
        super(message);
    }

    public DiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
