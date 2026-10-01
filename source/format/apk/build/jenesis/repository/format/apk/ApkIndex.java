package build.jenesis.repository.format.apk;

import module java.base;

/**
 * One {@code APKINDEX} entry, rendered from a package's {@code .PKGINFO}, matching the block Alpine publishes for the
 * same package. <b>{@code S:} is the size of the {@code .apk} file</b> and <b>{@code I:} the installed size</b>,
 * {@code .PKGINFO}'s {@code size}; swapping them gives an index that parses and lies. Keys follow Alpine's order, which
 * a client ignores and a person diffing indexes does not. {@code D:} and {@code p:} join the repeatable {@code depend}
 * and {@code provides}; a package declaring none omits the line, as Alpine does.
 */
final class ApkIndex {

    /** {@code .PKGINFO} key to index key, in the order Alpine writes them. */
    private static final List<String[]> SCALARS = List.of(
            new String[]{"pkgname", "P"},
            new String[]{"pkgver", "V"},
            new String[]{"arch", "A"});

    /** The remainder, after the sizes, again in Alpine's order. */
    private static final List<String[]> DESCRIPTIVE = List.of(
            new String[]{"pkgdesc", "T"},
            new String[]{"url", "U"},
            new String[]{"license", "L"},
            new String[]{"origin", "o"},
            new String[]{"maintainer", "m"},
            new String[]{"builddate", "t"},
            new String[]{"commit", "c"});

    private ApkIndex() {
        throw new UnsupportedOperationException();
    }

    /**
     * The block for one package.
     *
     * @param pkg the package, read from its control segment
     * @param bytes the stored size of the {@code .apk}, {@code S:}, a fact about the file rather than the package
     */
    static String entry(ApkPackage pkg, long bytes) {
        StringBuilder block = new StringBuilder();
        block.append("C:").append(pkg.checksum()).append('\n');
        for (String[] mapping : SCALARS) {
            pkg.field(mapping[0]).ifPresent(value -> block.append(mapping[1]).append(':').append(value).append('\n'));
        }
        block.append("S:").append(bytes).append('\n');
        pkg.field("size").ifPresent(value -> block.append("I:").append(value).append('\n'));
        for (String[] mapping : DESCRIPTIVE) {
            pkg.field(mapping[0]).ifPresent(value -> block.append(mapping[1]).append(':').append(value).append('\n'));
        }
        joined(block, "D", pkg.fields("depend"));
        joined(block, "p", pkg.fields("provides"));
        return block.toString();
    }

    /** A repeatable key as one space-joined line, or no line at all when the package declares none. */
    private static void joined(StringBuilder block, String key, List<String> values) {
        if (!values.isEmpty()) {
            block.append(key).append(':').append(String.join(" ", values)).append('\n');
        }
    }

    /** The key a block is listed under, {@code <name>-<version>}, the {@code .apk} file's stem. Not the name alone: an
     *  {@code APKINDEX} carries a block per version, and keying by name would let a second version replace the
     *  first. */
    static String keyOf(String block) {
        String name = field(block, "P"), version = field(block, "V");
        if (name == null) {
            return "";
        }
        return version == null ? name : name + "-" + version;
    }

    /** One line's value, or {@code null} when the block does not carry that key. */
    static String field(String block, String key) {
        for (String line : block.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }
}
