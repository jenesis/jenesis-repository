package build.jenesis.repository.cache.protocol.maven;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * The Apache Maven Build Cache Extension's own layout, adapted onto this cache.
 *
 * <p><b>What a real client asks for, not what the documentation says.</b> A real Maven 3.9.9 build with
 * {@code maven-build-cache-extension} asks for {@code <root>/v1.1/<groupId>/<artifactId>/<checksum>/<file>} -
 * five segments where the native protocol serves two - so this is an adapter rather than a second
 * implementation. The published example URL carries a UUID segment the documentation never explains; it is a
 * BUILD id, appears on {@code build-cache-report.xml} alone, and is new every run, so reading it as part of the
 * key would produce a cache that never hits.
 *
 * <p><b>The project is in the path because Maven cannot put it anywhere else.</b> The native protocol takes it
 * from a header and Gradle presents it as a Basic user name; the extension sends only what its configured
 * {@code <url>} contains, so an operator points it at {@code .../build/<tenant>/maven/<project>} and the segment carries
 * it. The credential needs nothing new: Maven Resolver sends HTTP Basic from {@code settings.xml}, and a key is
 * already accepted as the Basic password - which is why this protocol reads the presented credential but not the
 * presented project.
 *
 * <p><b>Both key components are hashed because the storage predicate requires them to be</b>: a coordinate
 * carries dots and a build id carries hyphens, and neither is hex. Hashing is what makes the mapping total
 * instead of refusing exactly the paths the client sends.
 *
 * <p>The module is the step, so one artifact's cache entries live together as one build step's do; the inputs are
 * the checksum, the file and the protocol version together. The file belongs in the key because one checksum
 * addresses several of them - the jar, the build info - and the version belongs there so a later {@code v1.2}
 * layout cannot collide with entries written under {@code v1.1}.
 */
public final class MavenCacheProtocol implements CacheProtocol {

    private static final String PREFIX = "/maven/";

    /** project, version, group, artifact, segment, file - the six the extension sends under the prefix. */
    private static final int SEGMENTS = 6;

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public String endpoint() {
        return PREFIX + "<project>";
    }

    @Override
    public boolean handles(String path) {
        return segments(path) != null;
    }

    @Override
    public Optional<Address> address(Request request) {
        String[] segments = segments(request.path());
        if (segments == null) {
            return Optional.empty();
        }
        String project = segments[0];
        String version = segments[1];
        String group = segments[2];
        String artifact = segments[3];
        String segment = segments[4];
        String file = segments[5];
        // The project rides in the path; only the credential comes from the shared presentation, because the
        // extension has nowhere but its configured URL to put the first and sends Basic for the second.
        return Optional.of(new Address(digest(group + "/" + artifact),
                digest(version + "/" + segment + "/" + file),
                project, request.presentedKey(), Existing.DEDUPE));
    }

    /** The six segments under the prefix, or {@code null} when the path is not this layout's shape. */
    private static String[] segments(String path) {
        if (!path.startsWith(PREFIX)) {
            return null;
        }
        String[] segments = path.substring(PREFIX.length()).split("/", -1);
        if (segments.length != SEGMENTS) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return null;
            }
        }
        return segments;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
