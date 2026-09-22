package build.jenesis.repository.format.apk;

import module java.base;

/**
 * One {@code APKINDEX} entry, rendered from a package's own {@code .PKGINFO}.
 *
 * <h2>The mapping, measured against Alpine's published index</h2>
 *
 * <p>Every field below was checked by rendering {@code musl-1.2.5-r3.apk} and comparing the result with the block
 * {@code dl-cdn.alpinelinux.org} publishes for it. Two are worth stating because they are easy to swap:
 * <b>{@code S:} is the size of the {@code .apk} file</b> (musl: 408308) while <b>{@code I:} is the installed
 * size</b>, which is {@code .PKGINFO}'s own {@code size} (musl: 667648). Taking either from the other produces an
 * index that parses and lies.
 *
 * <p>Key order follows Alpine's own, because an index is read by line prefix and not by position - so the order is
 * cosmetic to a client and load-bearing to a person diffing two indexes. {@code D:} and {@code p:} are
 * space-joined from the repeatable {@code depend} and {@code provides} entries; a package declaring none omits the
 * line rather than emitting an empty one, which is what Alpine does and what keeps a rendered block comparable.
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
     * @param pkg   the package, read from its own control segment.
     * @param bytes the size of the {@code .apk} as stored - {@code S:}, which is a fact about the file rather than
     *              anything the package declares about itself, so it is passed in rather than read.
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

    /**
     * The key a block is listed under: {@code <name>-<version>}, which is exactly the {@code .apk} file's stem.
     *
     * <p>Not the package name alone. An {@code APKINDEX} carries a block per package <em>version</em> - a
     * repository holding two versions of one package holds two blocks - so keying by {@code P:} would make the
     * second publish replace the first, and the index would then advertise one version of a package the repository
     * serves two of.
     */
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
