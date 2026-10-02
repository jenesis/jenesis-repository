package build.jenesis.repository.cache.protocol.jenesis;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * The cache protocol this build tool speaks: {@code /<step>/<inputs>} within a tenant's cache, with the project and
 * the credential in headers of their own, so neither is written to an intermediary's access log. A build tool's
 * {@code cache.uri} is the tenant's cache, {@code https://<host>/build/<tenant>}, and the tool appends the address.
 *
 * <p><b>A step may not be a reserved segment</b> ({@link CacheProtocol#RESERVED}): Gradle's {@code /gradle/<key>} has
 * the same two-segment shape, and the contract has no precedence to decide between two protocols claiming one path.
 *
 * <p>{@code step} is a build step and {@code inputs} the digest of everything that went into it, so the bytes at an
 * address are a function of the address and {@link CacheProtocol.Existing#DEDUPE} keeps the stored copy.
 */
public final class JenesisCacheProtocol implements CacheProtocol {

    private static final String PREFIX = "/";

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public String endpoint() {
        return "";
    }

    @Override
    public boolean handles(String path) {
        // Exactly two segments under the prefix.
        if (!path.startsWith(PREFIX)) {
            return false;
        }
        int separator = path.indexOf('/', PREFIX.length());
        if (separator <= PREFIX.length() || separator >= path.length() - 1
                || path.indexOf('/', separator + 1) >= 0) {
            return false;
        }
        // A step may not root a foreign tool's space, or two protocols would claim /gradle/<key>.
        return !RESERVED.contains(path.substring(PREFIX.length(), separator));
    }

    @Override
    public Optional<Address> address(Request request) {
        if (!handles(request.path())) {
            return Optional.empty();
        }
        int separator = request.path().indexOf('/', PREFIX.length());
        String step = request.path().substring(PREFIX.length(), separator);
        String inputs = request.path().substring(separator + 1);
        // An unpresented project or credential is an address with nulls, which the caller answers with a challenge
        // rather than as a malformed request.
        return Optional.of(new Address(step, inputs, request.header(PROJECT_HEADER), request.header(KEY_HEADER),
                Existing.DEDUPE));
    }
}
