package build.jenesis.repository.cache.protocol.maven;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;

/**
 * The Apache Maven Build Cache Extension's own layout, adapted onto this cache.
 *
 * <p>A Maven build with {@code maven-build-cache-extension} asks for
 * {@code <root>/v1.1/<groupId>/<artifactId>/<checksum>/<file>}, so this adapts that path onto the cache. A UUID segment
 * in the documented example is a build id that appears on {@code build-cache-report.xml} alone and is new every run,
 * so it is not part of the key.
 *
 * <p><b>The project is in the path</b>, since the extension sends only what its configured {@code <url>} contains: an
 * operator points it at {@code .../build/<tenant>/maven/<project>}. Maven Resolver sends HTTP Basic from
 * {@code settings.xml}, and a key is accepted as the Basic password, so this reads the presented credential but not
 * the presented project.
 *
 * <p><b>Both key components are hashed</b>, since a coordinate carries dots and a build id hyphens and the storage
 * predicate admits hex only. The module is the step, so one artifact's entries live together; the inputs are the
 * checksum, the file and the protocol version, since one checksum addresses several files and a later layout version
 * must not collide with this one.
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
        // The project from the path, the credential from Basic.
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
