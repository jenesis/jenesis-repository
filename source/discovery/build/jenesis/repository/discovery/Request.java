package build.jenesis.repository.discovery;

import module java.base;

/**
 * What a request path asks a discovered leg for: a Maven file, a Maven group's metadata, or a module's file in the
 * module service's layout - {@code /module/<name>/<version>/<name>[-<classifier>].jar} and its Maven view
 * {@code /artifact/<name>/<version>/<name>[-<classifier>].<extension>}, each with a version-less latest pointer beside
 * it. A path of any other shape, or one with a segment no store may address, asks for nothing.
 */
public sealed interface Request {

    /** A file of a Maven artifact at a version: {@code classifier} {@code null} for none, {@code type} the extension
     *  as the file ends ({@code jar}, {@code pom}, {@code jar.sha256}). */
    record MavenFile(String groupId, String artifactId, String version, String classifier, String type)
            implements Request {
    }

    /** A Maven artifact's {@code maven-metadata.xml}, or the checksum {@code digest} names beside it ({@code null} for
     *  the document itself). */
    record MavenMetadata(String groupId, String artifactId, String digest) implements Request {
    }

    /** A module's file: at {@code version}, or the newest where {@code version} is {@code null}; {@code mavenView} for
     *  the {@code /artifact/} route a build reading POMs asks, {@code type} the extension as the file ends. */
    record ModuleFile(String module, String version, String classifier, String type, boolean mavenView)
            implements Request {
    }

    /** The checksum extensions a metadata document is asked for beside itself, with the algorithm each names. */
    Map<String, String> DIGESTS = Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512");

    /** What {@code path} - a repository-relative request path such as {@code /maven/org/acme/lib/1.0/lib-1.0.jar} -
     *  asks for, or empty. */
    static Optional<Request> of(String path) {
        if (path == null || path.contains("..") || path.contains("//") || path.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        if (path.startsWith("/maven/")) {
            return maven(path.substring("/maven/".length()).split("/", -1));
        }
        if (path.startsWith("/module/")) {
            return module(path.substring("/module/".length()).split("/", -1), false);
        }
        if (path.startsWith("/artifact/")) {
            return module(path.substring("/artifact/".length()).split("/", -1), true);
        }
        return Optional.empty();
    }

    private static Optional<Request> maven(String[] segments) {
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return Optional.empty();
            }
        }
        String file = segments[segments.length - 1];
        if (file.startsWith("maven-metadata.xml") && segments.length >= 3) {
            String rest = file.substring("maven-metadata.xml".length());
            String digest = rest.isEmpty() ? null : rest.startsWith(".") ? rest.substring(1) : "";
            if (digest != null && !DIGESTS.containsKey(digest)) {
                return Optional.empty();
            }
            return Optional.of(new MavenMetadata(String.join(".", Arrays.copyOf(segments, segments.length - 2)),
                    segments[segments.length - 2], digest));
        }
        if (segments.length < 4) {
            return Optional.empty();
        }
        String artifactId = segments[segments.length - 3];
        String version = segments[segments.length - 2];
        String stem = artifactId + "-" + version;
        if (!file.startsWith(stem)) {
            return Optional.empty();
        }
        return named(file.substring(stem.length())).map(parts -> new MavenFile(
                String.join(".", Arrays.copyOf(segments, segments.length - 3)), artifactId, version, parts[0],
                parts[1]));
    }

    private static Optional<Request> module(String[] segments, boolean mavenView) {
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return Optional.empty();
            }
        }
        String module = segments[0];
        if (segments.length == 2) {
            return named(segments[1].startsWith(module) ? segments[1].substring(module.length()) : "")
                    .filter(parts -> parts[0] == null)
                    .map(parts -> new ModuleFile(module, null, null, parts[1], mavenView));
        }
        if (segments.length == 3 && segments[2].startsWith(module)) {
            return named(segments[2].substring(module.length()))
                    .map(parts -> new ModuleFile(module, segments[1], parts[0], parts[1], mavenView));
        }
        return Optional.empty();
    }

    // [classifier or null, type] of what follows a file's stem: "-sources.jar", ".pom" or ".jar.sha256".
    private static Optional<String[]> named(String rest) {
        if (rest.startsWith(".") && rest.length() > 1) {
            return Optional.of(new String[]{null, rest.substring(1)});
        }
        int dot = rest.indexOf('.');
        if (rest.startsWith("-") && dot > 1 && dot < rest.length() - 1) {
            return Optional.of(new String[]{rest.substring(1, dot), rest.substring(dot + 1)});
        }
        return Optional.empty();
    }
}
