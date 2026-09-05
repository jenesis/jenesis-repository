package build.jenesis.repository.store;

import module java.base;

/**
 * Files and directories created readable and writable by their owner alone, from the moment they exist - one statement
 * of an intent that was argued seven times over. Everything the product spools through the shared temporary directory
 * is somebody's plaintext: an artifact body waiting for its length or its digest before an upload, an import's
 * downloaded package, a rendered listing on its way into the store, an index segment on its way into the node's cache.
 *
 * <p>What is true, and what the copies believed. On a POSIX filesystem the JDK already creates a temporary
 * <em>file</em> {@code rw-------} and a temporary <em>directory</em> {@code rwx------}, so a plain
 * {@code Files.createTempFile} was never the world-readable spool the quota decorator's, the three object stores', the
 * gateway's and the two importers' paragraphs each said it would be - the listings, which spooled through the plain
 * call, were owner-only all along, and the test that pins it was green before any of this. What the platform does
 * <em>not</em> do is tighten {@code Files.createDirectories}, which follows the umask ({@code 755} under the usual
 * one): that is where the filesystem store's roots and upload directories needed the mode on the creation call, and
 * where this helper earns its keep. On a filesystem that cannot express modes at creation, files and directories alike
 * get a best-effort tightening through the {@link File} API afterwards, which is the most such a filesystem offers.
 *
 * <p>So the helper is here for legibility and for the two cases the platform leaves open, not because the copies had
 * found an exposure: a reader meets the intent once, under one name, and a sweep that finds a plain
 * {@code Files.createTempFile} in shipped code is finding a spool that has not said what it is.
 *
 * <p>A rename preserves the inode's mode, so a spool moved into place with {@code ATOMIC_MOVE} keeps its owner-only
 * mode at its destination; an in-place write ({@code newOutputStream}, truncating) preserves it too, where a
 * {@code Files.copy(REPLACE_EXISTING)} would delete and recreate the file with the source's mode.
 */
public final class OwnerOnly {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final FileAttribute<?>[] DIRECTORY = POSIX
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
            : new FileAttribute<?>[0];

    private static final FileAttribute<?>[] FILE = POSIX
            ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
            : new FileAttribute<?>[0];

    private OwnerOnly() {
    }

    /** Create {@code dir} and any missing parent, each {@code rwx------} - the case the platform's default would
     *  leave to the umask; an existing directory keeps its mode. */
    public static Path createDirectories(Path dir) throws IOException {
        return tightened(Files.createDirectories(dir, DIRECTORY));
    }

    /** A temporary file in the default temporary directory, {@code rw-------} from creation. */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        return tightened(Files.createTempFile(prefix, suffix, FILE));
    }

    /** A temporary file under {@code dir}, {@code rw-------} from creation. */
    public static Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return tightened(Files.createTempFile(dir, prefix, suffix, FILE));
    }

    /** A temporary directory in the default temporary directory, {@code rwx------} from creation. */
    public static Path createTempDirectory(String prefix) throws IOException {
        return tightened(Files.createTempDirectory(prefix, DIRECTORY));
    }

    /** The non-POSIX best effort: nothing to do where the mode rode on the creation call. */
    private static Path tightened(Path created) {
        if (!POSIX) {
            File file = created.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
            if (file.isDirectory()) {
                file.setExecutable(true, true);
            }
        }
        return created;
    }
}
