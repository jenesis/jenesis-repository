package build.jenesis.repository.format.java;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

import module java.base;

/**
 * The primitives the Maven layout needs to cross-publish into the Jenesis module layout: reading the module name a jar
 * declares, and parsing a Maven request path into its coordinate. These live in the shared Java-layout module so the
 * module-descriptor reading and the coordinate convention sit in one place rather than in the core.
 */
public final class JavaLayout {

    private JavaLayout() {
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

    /** The {@code [groupId, artifactId, version]} of a {@code /maven/...} request path, or null when it is not a full
     *  coordinate (a group directory, a checksum root). */
    public static String[] mavenCoordinate(String requestPath) {
        String[] segments = requestPath.substring("/maven/".length()).split("/");
        if (segments.length < 4) {
            return null;
        }
        return new String[]{
                String.join(".", Arrays.copyOf(segments, segments.length - 3)),
                segments[segments.length - 3],
                segments[segments.length - 2]};
    }
}
