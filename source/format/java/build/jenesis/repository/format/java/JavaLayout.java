package build.jenesis.repository.format.java;

import module java.base;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The primitives the Maven layout needs to cross-publish into the Jenesis module layout: reading the module name a jar
 * declares and parsing a Maven request path into its coordinate.
 */
public final class JavaLayout {

    /** The ecosystem name a Jenesis module reports, distinct from the {@code jenesis} format id that routes paths. It
     *  belongs to the grammar, so describers that may not depend on the layout implementation report it too. */
    public static final String MODULE_ECOSYSTEM = "Jenesis";

    /** The request-path prefix every Jenesis module view is served under. */
    public static final String MODULE_ROUTE = "/module/";

    private JavaLayout() {
    }

    /** The {@code [moduleName, version]} a {@code /module/<name>/<version>/<file>.jar} path names, or null when the
     *  path is not that shape or a segment is one a store must not address. A caller here decides whether it recognises
     *  a path, so both refusals are the same answer. */
    public static String[] moduleCoordinate(String requestPath) {
        if (requestPath == null || !requestPath.startsWith(MODULE_ROUTE)) {
            return null;
        }
        String[] segments = requestPath.substring(MODULE_ROUTE.length()).split("/");
        if (segments.length != 3) {
            return null;
        }
        return ArtifactLayout.addressable(segments[0], segments[1]) ? new String[]{segments[0], segments[1]} : null;
    }

    /** The version-addressed view of a module - the pointer a client resolving by name and version reaches. */
    public static String versionedModule(String moduleName, String version) {
        return MODULE_ROUTE + moduleName + "/" + version + "/" + moduleName + ".jar";
    }

    /** The "latest" view of a module - the pointer that names its highest version published. */
    public static String latestModule(String moduleName) {
        return MODULE_ROUTE + moduleName + "/" + moduleName + ".jar";
    }

    /** The prefix a module's Maven view is served under: its POM and jars by module name. The build tool resolves a
     *  {@code requires} here - {@code /artifact/<module>/<version>/<module>.pom}, or
     *  {@code /artifact/<module>/<module>.pom} for the latest - then fetches the jar by the coordinate that POM
     *  names. */
    public static final String ARTIFACT_ROUTE = "/artifact/";

    /** The version-addressed view of one of a module's jars: its own when {@code classifier} is empty, else the one
     *  Maven published under that classifier. */
    public static String versionedModule(String moduleName, String version, String classifier) {
        return MODULE_ROUTE + moduleName + "/" + version + "/" + file(moduleName, classifier, "jar");
    }

    /** A module's file at a version in its Maven view - a jar, own or classified, or its {@code pom}. */
    public static String versionedArtifact(String moduleName, String version, String classifier, String extension) {
        return ARTIFACT_ROUTE + moduleName + "/" + version + "/" + file(moduleName, classifier, extension);
    }

    /** The "latest" of a module's files in its Maven view - its jar or its {@code pom}. */
    public static String latestArtifact(String moduleName, String extension) {
        return ARTIFACT_ROUTE + moduleName + "/" + moduleName + "." + extension;
    }

    private static String file(String moduleName, String classifier, String extension) {
        return (classifier.isEmpty() ? moduleName : moduleName + "-" + classifier) + "." + extension;
    }

    /** A snapshot's timestamped build, as Maven names a file of one: {@code <yyyyMMdd.HHmmss>-<build number>}. */
    private static final Pattern SNAPSHOT_BUILD = Pattern.compile("\\d{8}\\.\\d{6}-\\d+(?:-(.+))?");

    /** The classifier of a {@code /maven/...} jar: empty for the artifact's own jar, {@code <classifier>} for
     *  {@code <artifact>-<version>-<classifier>.jar}, likewise for a snapshot's timestamped name - or an empty optional
     *  when the file name is none of those shapes. */
    public static Optional<String> mavenClassifier(String requestPath) {
        String[] coordinate = mavenCoordinate(requestPath);
        if (coordinate == null || !requestPath.endsWith(".jar")) {
            return Optional.empty();
        }
        String name = requestPath.substring(requestPath.lastIndexOf('/') + 1, requestPath.length() - ".jar".length());
        String base = coordinate[1] + "-" + coordinate[2];
        if (name.equals(base)) {
            return Optional.of("");
        }
        if (name.startsWith(base + "-") && name.length() > base.length() + 1) {
            return Optional.of(name.substring(base.length() + 1));
        }
        String snapshot = coordinate[1] + "-" + coordinate[2].replaceFirst("SNAPSHOT$", "");
        if (coordinate[2].endsWith("-SNAPSHOT") && name.startsWith(snapshot)) {
            Matcher build = SNAPSHOT_BUILD.matcher(name.substring(snapshot.length()));
            if (build.matches()) {
                return Optional.of(build.group(1) == null ? "" : build.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * A file published beside a Maven coordinate: {@code <dir>/<artifact>-<version><suffix>} - the sibling POM, the
     * CycloneDX attachment - stated here so no consumer re-derives the directory or separator.
     *
     * @param requestPath a {@code /maven/...} path whose directory the sibling shares
     * @param suffix what follows the version, including its separator - {@code ".pom"}, {@code "-cyclonedx.json"}
     * @return the sibling's request path, or null when {@code requestPath} names no full coordinate
     */
    public static String attachment(String requestPath, String suffix) {
        String[] coordinate = mavenCoordinate(requestPath);
        int slash = requestPath.lastIndexOf('/');
        if (coordinate == null || slash < 0) {
            return null;
        }
        return requestPath.substring(0, slash + 1) + coordinate[1] + "-" + coordinate[2] + suffix;
    }

    /** The module name a jar declares - its {@code module-info} name or {@code Automatic-Module-Name} - or null for a
     *  plain jar. The jar is streamed, never buffered, under both archive bounds: the walk runs under
     *  {@link ArchiveWalk}, and the only entries read into heap (the manifest, {@code module-info.class}) go through
     *  {@link ArchiveInflation}. A module name is an optional declaration, so both bounds degrade
     *  ({@link ArchiveWalk.Found#orNull()}, {@link ArchiveInflation.Entry#orNull()}) to "declares no module" - which
     *  can only under-declare. */
    public static String moduleName(InputStream jar) {
        try {
            return ArchiveWalk.walk(jar, JavaLayout::declaredModule).orNull();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** The module name declared inside an already-bounded jar stream, or null when it declares none. */
    private static String declaredModule(InputStream jar) throws IOException {
        try (ZipInputStream in = ArchiveWalk.zip(jar)) {
            String automatic = null;
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.getName().equals("module-info.class")) {
                    byte[] descriptor = bounded(in);
                    if (descriptor != null) {
                        return ModuleDescriptor.read(ByteBuffer.wrap(descriptor)).name();
                    }
                } else if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    byte[] bytes = bounded(in);
                    if (bytes != null) {
                        automatic = new Manifest(new ByteArrayInputStream(bytes))
                                .getMainAttributes().getValue("Automatic-Module-Name");
                    }
                }
            }
            // An Automatic-Module-Name is a raw manifest string that becomes a /module/<name>/ key, so it must be a
            // legal module name first; a crafted value is treated as no module.
            return automatic == null ? null : validModuleName(automatic);
        }
    }

    /** The current entry's bytes, or null past the shared inflation bound, so a decompression bomb declares nothing
     *  rather than a prefix. */
    private static byte[] bounded(InputStream in) throws IOException {
        return ArchiveInflation.entry(in).orNull();
    }

    /** The name if it is a legal Java module name, by the JDK's own validation, else null. */
    private static String validModuleName(String name) {
        try {
            ModuleDescriptor.newAutomaticModule(name);
            return name;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** The request-path prefix the Maven layout serves under. */
    public static final String MAVEN_ROUTE = "/maven/";

    /** The {@code [groupId, artifactId, version]} of a {@code /maven/...} request path, or null when it is not a full
     *  coordinate (a group directory, a checksum root) or not on the Maven route - which is part of the grammar, so a
     *  long path elsewhere is refused rather than mangled into a coordinate. */
    public static String[] mavenCoordinate(String requestPath) {
        if (requestPath == null || !requestPath.startsWith(MAVEN_ROUTE)) {
            return null;
        }
        String[] segments = requestPath.substring(MAVEN_ROUTE.length()).split("/");
        if (segments.length < 4) {
            return null;
        }
        return new String[]{
                String.join(".", Arrays.copyOf(segments, segments.length - 3)),
                segments[segments.length - 3],
                segments[segments.length - 2]};
    }
}
