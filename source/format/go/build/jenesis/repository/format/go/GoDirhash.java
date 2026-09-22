package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The Go ecosystem's one artifact digest: the {@code h1:} <em>dirhash</em> a {@code go.sum} line carries and the
 * checksum database publishes for every module version. It is not a hash of the file that was transferred - it is a
 * hash of the module's <em>content</em>, so it is stable across re-zips, and computing it is the only way a mirror can
 * hold a proxied {@code .zip} or {@code .mod} to what {@link GoChecksumDatabase} advertises for it.
 *
 * <h2>The algorithm (golang.org/x/mod/sumdb/dirhash, {@code Hash1})</h2>
 * For a set of named files, each file's SHA-256 is rendered as {@code "%x  %s\n"} (lower-case hex, <b>two</b> spaces,
 * the name, a newline); those lines are concatenated <b>in order of the file names</b> - not in the order the lines
 * happen to sort - and the SHA-256 of that concatenation, base64-encoded, is the digest behind the {@code h1:} prefix.
 * The two shapes this format proxies map onto it as:
 * <ul>
 * <li>a <b>{@code .mod}</b> is hashed as the single file {@code go.mod} whose content is the body ({@link #ofGoMod}),
 *     so the digest follows straight from the body's SHA-256 - which the content-addressed store already computed on
 *     the way in, so the body is never read a second time;</li>
 * <li>a <b>{@code .zip}</b> is hashed as every entry it carries, under the entry's own name ({@link #ofZip}). Go reads
 *     those names from the central directory; this reads them from the entry stream, which is the same set for any
 *     zip a Go toolchain produced and differs only for a zip whose two directories disagree - and such a body simply
 *     fails the comparison, which is the fail-closed answer.</li>
 * </ul>
 *
 * <h2>Bounded, and never materialised</h2>
 * The zip walk streams: each entry is digested 8 KiB at a time and only its 32-byte digest is kept, so a module of any
 * size costs one pass and no member ever lands in heap. Two dimensions are bounded, both off the product's one shared,
 * operator-settable archive-walk ceiling ({@link ArchiveWalk#largestWalk(long, long)}, {@code jenreg.archive.largest-walk}):
 * the bytes drawn from the archive itself (the walk's own screen) and the <em>decompressed</em> bytes the entries
 * inflate to, which the screen cannot see because a zip chooses its own ratio. A bomb reaches whichever comes first.
 * The entry <em>count</em> is bounded too ({@link #MAX_ENTRIES}), because the names have to be held to be ordered.
 *
 * <p>Reaching any of the three is reported the way every bound in this product is: as nothing at all
 * ({@code null}), never as a digest computed over the part that fitted. The caller then has no digest to compare and
 * refuses the fill - "we stopped looking" must never wear the clothes of "this matches".
 */
public final class GoDirhash {

    /** The prefix every dirhash carries; a checksum-database record that does not start with it is not one. */
    public static final String PREFIX = "h1:";

    /**
     * The largest number of zip entries one dirhash will order. The names must be held to be sorted - that is the
     * algorithm, not an implementation choice - so this is the bound on the only thing the walk accumulates. A module
     * zip is capped at 500 MiB by the ecosystem itself and a real one carries thousands of files, not hundreds of
     * thousands; a body past this is refused rather than allowed to size a heap structure from the network.
     */
    private static final int MAX_ENTRIES = 100_000;

    /**
     * How far past a module zip's own stored size its entries may inflate before the walk is cut off, applied through
     * the shared {@link ArchiveWalk#largestWalk(long, long)} floor. A zip is walked entry by entry, so the walk's own
     * screen only ever sees the <em>stored</em> bytes; the digest, however, is computed over the decompressed content
     * of every entry, which is the dimension a zip bomb chooses. Twenty is generous for source (deflate on text runs
     * to about a tenth of that) and still turns a kilobyte of hostile input into a bounded read.
     */
    private static final long INFLATION_RATIO = 20L;

    private GoDirhash() {
        throw new UnsupportedOperationException("GoDirhash is a static utility");
    }

    /**
     * The {@code h1:} dirhash of a {@code .mod} body whose SHA-256 is {@code sha256Hex} - the module's {@code go.mod}
     * hashed as the single file {@code go.mod}. The body itself is not needed: {@code Hash1} only ever consumes the
     * per-file digest, and the content-addressed store returns exactly that when it stores the body, so a proxied
     * {@code .mod} is verified without a second read of anything.
     */
    public static String ofGoMod(String sha256Hex) {
        MessageDigest outer = sha256();
        outer.update(line(sha256Hex, "go.mod"));
        return PREFIX + Base64.getEncoder().encodeToString(outer.digest());
    }

    /**
     * The {@code h1:} dirhash of a module {@code .zip} read from {@code archive}, or {@code null} when a bound stopped
     * the walk, the entry count exceeded {@link #MAX_ENTRIES}, or an entry name carries a newline (which {@code Hash1}
     * itself refuses, since the line format could not then be parsed back). {@code storedLength} is the zip's own size,
     * used only to derive the walk ceiling; a non-positive value falls back to the shared flat tier.
     *
     * <p>The stream is neither drained nor closed here, matching {@link ArchiveWalk#walk}.
     */
    public static String ofZip(InputStream archive, long storedLength) throws IOException {
        long ceiling = ArchiveWalk.largestWalk(storedLength, INFLATION_RATIO);
        // Names order the digest, so they are held; the content is not - only its 32 bytes of digest survive an entry.
        SortedMap<String, byte[]> entries = new TreeMap<>(GoDirhash::compareUtf8);
        long[] inflatable = {ceiling};
        ArchiveWalk.Found<Boolean> walked = ArchiveWalk.walk(archive, ceiling, screened -> {
            // Deliberately not closed: closing this would close the screened view and with it the caller's archive,
            // which ArchiveWalk documents as the walker's own business, and the caller owns the blob stream.
            ZipInputStream zip = new ZipInputStream(screened);
            byte[] buffer = new byte[8192];
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entries.size() >= MAX_ENTRIES || entry.getName().indexOf('\n') >= 0) {
                    return null;
                }
                MessageDigest content = sha256();
                for (int read; (read = zip.read(buffer)) >= 0; ) {
                    inflatable[0] -= read;
                    if (inflatable[0] < 0) {
                        // The decompressed dimension of the same ceiling - the one the walk's own screen cannot see,
                        // because a zip chooses its ratio. Answered as nothing found, never as a partial digest.
                        return null;
                    }
                    content.update(buffer, 0, read);
                }
                // A directory entry is hashed as the empty file it reads as, exactly as Go's own walk over the central
                // directory does - nothing is skipped, because anything skipped here would change the digest.
                entries.put(entry.getName(), content.digest());
            }
            return Boolean.TRUE;
        });
        if (walked.value() == null) {
            return null;
        }
        MessageDigest outer = sha256();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            outer.update(line(HexFormat.of().formatHex(entry.getValue()), entry.getKey()));
        }
        return PREFIX + Base64.getEncoder().encodeToString(outer.digest());
    }

    /** One {@code Hash1} line: the file's lower-case SHA-256 hex, two spaces, its name, a newline. */
    private static byte[] line(String sha256Hex, String name) {
        return (sha256Hex + "  " + name + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Order two entry names by their UTF-8 bytes, which is what Go's {@code sort.Strings} does over its own UTF-8
     *  strings. {@link String#compareTo} would order by UTF-16 code unit instead and disagree wherever a name carries a
     *  supplementary character, producing a digest that silently never matches. */
    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is a required JDK algorithm
        }
    }
}
