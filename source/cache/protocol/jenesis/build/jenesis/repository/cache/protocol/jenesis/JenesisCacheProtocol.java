package build.jenesis.repository.cache.protocol.jenesis;

import module java.base;
import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * The cache protocol this build tool speaks, with the project, the credential and the digest algorithm in headers of
 * their own, so none is written to an intermediary's access log. A build tool's {@code cache.uri} is the tenant's
 * cache, {@code https://<host>/build/<tenant>}, and the tool appends the address.
 *
 * <p>A step's result is not one opaque entry: {@code /step/<step>/<inputs>} holds a manifest, one line
 * {@code <digest> <size> <path>} per file the step wrote, and {@code /blobs} takes and answers the files themselves in
 * batches, each named by the digest of its own bytes. A file is therefore stored once however many steps hand it on,
 * and a build uploads and downloads only what the other side lacks.
 *
 * <p>Neither {@code step} nor {@code blobs} is a foreign tool's root ({@link CacheProtocol#RESERVED}), and neither
 * shape has two segments, which is the shape Gradle's {@code /gradle/<key>} has.
 *
 * <p>{@code step} is a build step and {@code inputs} the digest of everything that went into it, so a manifest at an
 * address is a function of the address and {@link CacheProtocol.Existing#DEDUPE} keeps the stored copy.
 */
public final class JenesisCacheProtocol implements CacheProtocol {

    private static final String STEP = "/step/";
    private static final String BLOBS = "/blobs";
    /** The step and inputs of a batch's address, which names no entry: each file of the batch has its own. */
    private static final String BATCH = "00";

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
        if (path.equals(BLOBS)) {
            return true;
        }
        if (!path.startsWith(STEP)) {
            return false;
        }
        // Exactly two segments under the prefix.
        int separator = path.indexOf('/', STEP.length());
        return separator > STEP.length() && separator < path.length() - 1 && path.indexOf('/', separator + 1) < 0;
    }

    @Override
    public Shape shape(String path) {
        return path.equals(BLOBS) ? Shape.CONTENT : Shape.MANIFEST;
    }

    @Override
    public Optional<Address> address(Request request) {
        if (!handles(request.path())) {
            return Optional.empty();
        }
        // An unpresented project or credential is an address with nulls, which the caller answers with a challenge
        // rather than as a malformed request.
        if (request.path().equals(BLOBS)) {
            return Optional.of(new Address(BATCH, BATCH, request.header(PROJECT_HEADER), request.header(KEY_HEADER),
                    Existing.DEDUPE));
        }
        int separator = request.path().indexOf('/', STEP.length());
        return Optional.of(new Address(request.path().substring(STEP.length(), separator),
                request.path().substring(separator + 1), request.header(PROJECT_HEADER), request.header(KEY_HEADER),
                Existing.DEDUPE));
    }
}
