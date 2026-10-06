package build.jenesis.repository.closure.spi;

import module java.base;
import build.jenesis.repository.inventory.Mailbox;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;

/**
 * A bill of materials attached to a published version rather than carried in its files - what a scanner made of an
 * image's content. The carried bill reads an attached bill after the version's own files, so a bill the build published
 * stands before one a scanner made afterwards, and the closure it names is the version's like any other carried
 * closure.
 *
 * <p>Attaching one replaces the bill attached before, clears the closure the version had - resolved before the bill
 * was there, by whichever source answered then - and asks the closure pass to resolve it again ({@link #ATTACHED}), so
 * the next pass over the repository resolves it from the bill. Detaching does the same without it. Kept per
 * repository, at {@code closure/bills/<sha-256 of the version>}, one document per version, as the scanner wrote it -
 * a CycloneDX or SPDX document, which the reader tells apart.
 *
 * <p>An attachment is the repository's own record of the version's bill; a cached copy has none, because what was
 * built into it is its publisher's to say, and the closure of a copy is the walk's over what it declares.
 */
public final class VersionBills {

    /** Where the attached bills are kept, per repository. */
    public static final String ROOT = "closure/bills";

    /** The versions whose bill was attached or detached since the closure pass last drained them, which it resolves
     *  again. */
    public static final Mailbox ATTACHED = new Mailbox("closure/attached");

    private VersionBills() {
    }

    /** Attach {@code document}, a bill of materials, to {@code version} of {@code coordinate} in {@code store}, the
     *  repository publishing it, and have its closure resolved again. */
    public static void attach(ArtifactStore store, String ecosystem, String coordinate, String version,
                              byte[] document) throws IOException {
        store.write(key(ecosystem, coordinate, version), new ByteArrayInputStream(document));
        resolveAgain(store, ecosystem, coordinate, version);
    }

    /** Detach the bill attached to {@code version} of {@code coordinate}, where one is, and have its closure resolved
     *  again without it. */
    public static void detach(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String key = key(ecosystem, coordinate, version);
        if (store.exists(key)) {
            store.delete(key);
            resolveAgain(store, ecosystem, coordinate, version);
        }
    }

    /** Drop the bill attached to {@code version} of {@code coordinate}, which the repository no longer holds: nothing
     *  is left to resolve, so nothing is asked again. */
    public static void forget(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String key = key(ecosystem, coordinate, version);
        if (store.exists(key)) {
            store.delete(key);
        }
    }

    /** The bill attached to {@code version} of {@code coordinate}, opened, or empty where none is. The caller closes
     *  it. */
    public static Optional<InputStream> open(ArtifactStore store, String ecosystem, String coordinate,
                                             String version) throws IOException {
        String key = key(ecosystem, coordinate, version);
        return store.exists(key) ? Optional.of(store.open(key)) : Optional.empty();
    }

    private static void resolveAgain(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        MetadataProvider.installed().over(store).mutate(ecosystem, coordinate, version, ClosureSection.TAG,
                _ -> null);
        ATTACHED.post(store, ecosystem, coordinate, version);
    }

    private static String key(String ecosystem, String coordinate, String version) {
        return ROOT + "/" + Checksums.sha256(ecosystem + "\n" + coordinate + "\n" + version);
    }
}
