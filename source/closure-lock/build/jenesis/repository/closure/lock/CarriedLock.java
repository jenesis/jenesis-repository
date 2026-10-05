package build.jenesis.repository.closure.lock;

import module java.base;
import module org.apache.commons.compress;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The lock file a release's gzipped tarball carries at its top directory - {@code package/npm-shrinkwrap.json},
 * {@code <name>-<version>/Cargo.lock} - read out of the first of the release's files that has one, bounded by the
 * shared archive walk. A walk the bound stopped finds nothing, so the next source answers rather than a lock read in
 * part.
 */
final class CarriedLock {

    /** The most bytes of a lock file read: a lock pinning a large tree runs to megabytes, which the shared member
     *  ceiling for a manifest does not admit. */
    static final int LARGEST_LOCK = 16 << 20;

    private CarriedLock() {
    }

    /** The bytes of the member named {@code file} at the top directory of the first of {@code coordinate} at
     *  {@code version}'s files that carries one, or empty. */
    static Optional<byte[]> read(ClosureWalk walk, String ecosystem, String coordinate, String version, String file)
            throws IOException {
        ClosureWalk.Member own = walk.members().getFirst();
        for (String key : new StoreRepositoryInventory(own.store()).contentKeys(ecosystem, coordinate, version)) {
            byte[] found;
            try (InputStream in = own.store().open(key)) {
                found = ArchiveWalk.walk(in, screened -> member(screened, file)).orNull();
            }
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** The member {@code file} directly under a gzipped tar's top directory, or {@code null} where it carries none or
     *  is no gzipped tar. */
    private static byte[] member(InputStream screened, String file) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(screened);
        buffered.mark(2);
        boolean gzip = buffered.read() == 0x1f && buffered.read() == 0x8b;
        buffered.reset();
        if (!gzip) {
            return null;
        }
        TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(buffered), "UTF-8");
        for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null; ) {
            String name = entry.getName();
            int slash = name.indexOf('/');
            if (entry.isFile() && slash > 0 && name.indexOf('/', slash + 1) < 0
                    && name.substring(slash + 1).equals(file)) {
                return ArchiveInflation.entry(tar, LARGEST_LOCK).orNull();
            }
        }
        return null;
    }
}
