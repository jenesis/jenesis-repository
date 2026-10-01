package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The Go ecosystem's artifact digest: the {@code h1:} dirhash a {@code go.sum} line carries and the checksum database
 * publishes. It hashes the module's content, not the transferred file, so computing it is the only way to hold a
 * proxied {@code .zip} or {@code .mod} to what {@link GoChecksumDatabase} advertises.
 *
 * <h2>The algorithm (golang.org/x/mod/sumdb/dirhash, {@code Hash1})</h2>
 * Each named file's SHA-256 is rendered as {@code "%x %s\n"} (lower-case hex, two spaces, the name, a newline); the
 * lines are concatenated in order of the file names, and the base64 SHA-256 of that is the digest behind {@code h1:}.
 * <ul>
 *   <li>a <b>{@code .mod}</b> is the single file {@code go.mod} ({@link #ofGoMod}), so the digest follows from the
 *       body's SHA-256, which the store computed on the way in;</li>
 *   <li>a <b>{@code .zip}</b> is every entry under its own name ({@link #ofZip}), read from the entry stream where Go
 *       reads the central directory: the same set for any toolchain-produced zip, and a zip whose directories disagree
 *       fails the comparison, the fail-closed answer.</li>
 * </ul>
 *
 * <h2>Bounded, and never materialised</h2>
 * Each entry is digested 8 KiB at a time and only its digest kept. The bytes drawn from the archive and the
 * decompressed bytes the entries inflate to are both bounded off the shared archive-walk ceiling
 * ({@link ArchiveWalk#largestWalk(long, long)}, {@code jenrepo.archive.largest-walk}), and the entry count by
 * {@link #MAX_ENTRIES}, since the names are held to be ordered. Reaching a bound answers {@code null}, never a digest
 * over the part that fitted, and the caller refuses the fill.
 */
public final class GoDirhash {

    /** The prefix every dirhash carries; a checksum-database record that does not start with it is not one. */
    public static final String PREFIX = "h1:";

    /** The most zip entries one dirhash orders; the names must be held to sort them. A module zip is capped at 500 MiB
     *  by the ecosystem and carries thousands of files, so a larger count is refused rather than sizing a heap
     *  structure. */
    private static final int MAX_ENTRIES = 100_000;

    /** How far past its stored size a module zip's entries may inflate, through
     *  {@link ArchiveWalk#largestWalk(long, long)}: the walk's screen sees stored bytes, while the digest covers
     *  decompressed content, the dimension a zip bomb chooses. Twenty is generous for source. */
    private static final long INFLATION_RATIO = 20L;

    private GoDirhash() {
        throw new UnsupportedOperationException("GoDirhash is a static utility");
    }

    /** The {@code h1:} dirhash of a {@code .mod} body whose SHA-256 is {@code sha256Hex}: {@code go.mod} hashed as a
     *  single file, which needs only the per-file digest the store already returned. */
    public static String ofGoMod(String sha256Hex) {
        MessageDigest outer = sha256();
        outer.update(line(sha256Hex, "go.mod"));
        return PREFIX + Base64.getEncoder().encodeToString(outer.digest());
    }

    /** The {@code h1:} dirhash of a module {@code .zip} read from {@code archive}, or {@code null} when a bound stopped
     *  the walk, the entries exceeded {@link #MAX_ENTRIES}, or an entry name carries a newline, which {@code Hash1}
     *  refuses. {@code storedLength} only derives the walk ceiling; a non-positive one falls back to the flat tier. The
     *  stream is neither drained nor closed here, as for {@link ArchiveWalk#walk}. */
    public static String ofZip(InputStream archive, long storedLength) throws IOException {
        long ceiling = ArchiveWalk.largestWalk(storedLength, INFLATION_RATIO);
        // Names order the digest and are held; only each entry's digest survives.
        SortedMap<String, byte[]> entries = new TreeMap<>(GoDirhash::compareUtf8);
        ArchiveWalk.Found<Boolean> walked = ArchiveWalk.walk(archive, ceiling, screened -> {
            // Not closed: that would close the caller's archive. The zip counts inflated bytes against the same
            // ceiling.
            ZipInputStream zip = ArchiveWalk.zip(screened);
            byte[] buffer = new byte[8192];
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entries.size() >= MAX_ENTRIES || entry.getName().indexOf('\n') >= 0) {
                    return null;
                }
                MessageDigest content = sha256();
                for (int read; (read = zip.read(buffer)) >= 0; ) {
                    content.update(buffer, 0, read);
                }
                // A directory entry hashes as the empty file it reads as, as in Go's own walk; skipping it would change
                // the digest.
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

    /** Order two names by their UTF-8 bytes, as Go's {@code sort.Strings} does; {@link String#compareTo} orders UTF-16
     *  code units and would disagree on supplementary characters. */
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
