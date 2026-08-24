package build.jenesis.repository.format.java;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

import module java.base;

/**
 * The primitives the Maven layout needs to cross-publish into the Jenesis module layout: reading the module name a jar
 * declares, and parsing a Maven request path into its coordinate. These live in the shared Java-layout module so the
 * module-descriptor reading and the coordinate convention sit in one place rather than in the core.
 */
public final class JavaLayout {

    /**
     * The package-ecosystem name a Jenesis module reports - distinct from the {@code jenesis} format id that routes
     * the paths. It lives here rather than on the format because the name is part of the <em>grammar</em>: anything
     * that describes a module artifact reports it, including describers that must not take an edge to the layout
     * implementation.
     */
    public static final String MODULE_ECOSYSTEM = "Jenesis";

    /** The request-path prefix every Jenesis module view is served under. */
    public static final String MODULE_ROUTE = "/module/";

    private JavaLayout() {
    }

    /**
     * The {@code [moduleName, version]} a {@code /module/<name>/<version>/<file>.jar} request path names, or null when
     * the path is not that shape or either segment is one a store must not address.
     *
     * <p>Null rather than an exception, and both refusals fold into it deliberately: a caller here is deciding
     * whether it recognises a path, not validating a request, so "not a module coordinate" and "not one I would
     * store" are the same answer - it does not handle the path either way.
     */
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

    /** The "latest" view of a module - the pointer that names whichever version published last. */
    public static String latestModule(String moduleName) {
        return MODULE_ROUTE + moduleName + "/" + moduleName + ".jar";
    }

    /**
     * A file published <em>beside</em> a Maven coordinate: {@code <dir>/<artifact>-<version><suffix>}.
     *
     * <p>This is the one grammar a describing consumer keeps re-deriving - the sibling POM, the CycloneDX attachment
     * a build publishes next to the jar - and each re-derivation is a chance to get the directory or the separator
     * subtly wrong against a layout that owns the answer.
     *
     * @param requestPath a {@code /maven/...} path whose directory the sibling shares
     * @param suffix      what follows the version, including its separator - {@code ".pom"}, {@code "-cyclonedx.json"}
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

    /** The module name a jar declares - its {@code module-info} name, or its {@code Automatic-Module-Name} - or null
     *  when it carries neither (a plain jar, not a module). The jar is walked as a stream (typically opened back from
     *  storage after the blob was streamed in), so the artifact is never buffered whole in memory, and both of the
     *  product's archive bounds apply: the walk itself runs under {@link ArchiveWalk}, so a jar cannot be made to
     *  spend an unbounded amount of this thread's time on entries it streams past, and the only entries read into heap
     *  - the manifest and {@code module-info.class} - go through {@link ArchiveInflation}, so a decompression bomb in
     *  either cannot inflate unbounded. A module name is an optional declaration (a plain jar simply is not a module
     *  and publishes fine), so both bounds take the degrading accessor ({@link ArchiveWalk.Found#orNull()},
     *  {@link ArchiveInflation.Entry#orNull()}) and read as "declares no module" rather than failing the publish -
     *  a lost module name can only under-declare, never admit anything unscreened. */
    public static String moduleName(InputStream jar) {
        try {
            return ArchiveWalk.walk(jar, JavaLayout::declaredModule).orNull();
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** The module name declared inside an already-bounded jar stream, or null when it declares none. */
    private static String declaredModule(InputStream jar) throws IOException {
        try (ZipInputStream in = new ZipInputStream(jar)) {
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
            // A module-info name is JVM-validated by read(); an Automatic-Module-Name is a raw manifest string that
            // becomes a /module/<name>/ store key, so validate it is a legal module name first - a crafted value (a
            // '/'- or '..'-laced or empty name) is treated as no module rather than reaching a pointer key.
            return automatic == null ? null : validModuleName(automatic);
        }
    }

    /** The current zip entry's decompressed bytes, or null once they exceed the shared archive-inflation bound - so a
     *  high-ratio decompression bomb is abandoned at the ceiling instead of inflated whole into heap, and a
     *  bound-stopped entry declares nothing rather than declaring a prefix. */
    private static byte[] bounded(InputStream in) throws IOException {
        return ArchiveInflation.entry(in).orNull();
    }

    /** The name if it is a legal Java module name (dot-separated Java identifiers), else null. Uses the JDK's own
     *  module-name validation so the rule matches exactly what a real module name may contain. */
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
     *  coordinate (a group directory, a checksum root) or not on the Maven route at all.
     *
     *  <p>The route check is part of the grammar rather than the caller's job: without it this returns a mangled
     *  coordinate for any path that happens to be long enough, which is a silently wrong answer rather than a
     *  refusal. Callers that had their own copy of this split guarded it; the shared one now does. */
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
