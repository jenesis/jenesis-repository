package build.jenesis.repository.cache.protocol.jenesis;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * The cache protocol this build tool speaks: {@code /cache/<step>/<inputs>}, with the project and the credential
 * in headers of their own.
 *
 * <p>Headers rather than the path, so that neither is written to an intermediary's access log - which is the one
 * respect in which this protocol is better placed than the foreign layouts beside it, since a tool whose wire
 * format this product does not define has to present its credential the way that format already says.
 *
 * <p><b>A step may not be a reserved segment.</b> Gradle's {@code /cache/gradle/<key>} has the same shape as this
 * protocol's two segments, so without that rule both would claim it and the dispatch would need a precedence the
 * contract deliberately does not have. {@link CacheProtocol#RESERVED} is the list, and it costs a build step the
 * three names a foreign tool roots its layout at.
 *
 * <p>The address is the client's own: {@code step} is a build step and {@code inputs} the digest of everything
 * that went into it, so the bytes at an address are a function of the address and a second write of the same
 * address is the same result computed again. That is what makes {@link CacheProtocol.Existing#DEDUPE} correct
 * here - keeping the stored copy costs nothing and saves the upload, where overwriting would only churn.
 */
public final class JenesisCacheProtocol implements CacheProtocol {

    private static final String PREFIX = "/cache/";

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public boolean handles(String path) {
        // Exactly two segments under the prefix, which is what keeps this off /cache/gradle/<key> and the other
        // foreign layouts: they are claimed by their own protocols, and an overlap would be a composition error
        // rather than a precedence question.
        if (!path.startsWith(PREFIX)) {
            return false;
        }
        int separator = path.indexOf('/', PREFIX.length());
        if (separator <= PREFIX.length() || separator >= path.length() - 1
                || path.indexOf('/', separator + 1) >= 0) {
            return false;
        }
        // Gradle's layout is shape-identical to this one - two segments under the prefix - so the step may not be
        // a name that roots a foreign tool's space, or the two protocols would both claim /cache/gradle/<key>.
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
        // An unpresented project or credential is a complete address with nulls, not an empty answer: the caller
        // refuses those as a challenge rather than as a malformed request (the contract's clause 5).
        return Optional.of(new Address(step, inputs, request.header(PROJECT_HEADER), request.header(KEY_HEADER),
                Existing.DEDUPE));
    }
}
