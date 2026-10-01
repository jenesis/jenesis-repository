package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.HeldBy;
import build.jenesis.repository.store.Withheld;

/**
 * How a sweep places a hold on an already-published version, whichever pass decided to: a KEV listing that landed
 * later, a signature dial tightened after the fact. The pass decides; this places, in the order that keeps a crash
 * recoverable.
 *
 * <p>The kind's own {@link Record} is written first, so no {@code /quarantine} pointer exists without the record that
 * says what it holds. Each served path with a {@code publish/} pointer then gets its {@code /quarantine} pointer and
 * its {@link QuarantineLog} row, pointer first, so the pointer-keyed review queue shows a half-written hold. Last, the
 * blobs-namespace leg marks every content hash the version serves from {@code blobs/} and links a review handle at each
 * served path without a pointer, so a pure blobs-namespace release (npm, PyPI, Go) is retracted and reviewable. Every
 * step is idempotent.
 */
public final class RetroactiveHolds {

    private RetroactiveHolds() {
    }

    /** The kind's own record, written before any pointer - what the hold is a hold on, in the kind's vocabulary. */
    @FunctionalInterface
    public interface Record {

        void write() throws IOException;
    }

    /** Whether any of a release's served paths carries a {@code /quarantine} pointer, the read the gate screen's
     *  {@code withheld} makes. */
    public static boolean anyHeld(ArtifactStore store, List<String> paths) throws IOException {
        for (String path : paths) {
            if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places a fresh hold on a version. Answers whether anything was held: a version with no served pointer and no
     * content hash holds and records nothing.
     */
    public static boolean hold(ArtifactStore store, Publication publication, StoreRepositoryInventory inventory,
                               QuarantineLog log, Instant now, String ecosystem, String coordinate, String version,
                               List<String> paths, String reason, String subject, Record record) throws IOException {
        LinkedHashMap<String, String> holdable = new LinkedHashMap<>();
        for (String path : paths) {
            publication.blob(path).ifPresent(hash -> holdable.put(path, hash));
        }
        List<String> blobHashes = inventory.blobHashes(ecosystem, coordinate, version);
        if (holdable.isEmpty() && blobHashes.isEmpty()) {
            return false;   // nothing served from either namespace - there is nothing to withhold or review
        }
        record.write();
        for (Map.Entry<String, String> entry : holdable.entrySet()) {
            Withheld.mark(store, entry.getValue(), new ArtifactDescriptor(ecosystem, coordinate, version,
                    entry.getKey(), null, false, null, -1L));   // the blobs-namespace read side
            // The path-to-coordinate record precedes the pointer, so the hold stays answerable without the format.
            HeldSubjects.hold(publication, store, entry.getKey(), entry.getValue(), ecosystem, coordinate, version);
            log.record(now, entry.getKey(), subject, Verdict.QUARANTINE, List.of(reason));
        }
        withholdBlobs(store, publication, log, now, ecosystem, coordinate, version, paths, blobHashes, reason, subject);
        return true;
    }

    /**
     * Converges a version this kind already holds, where a crash or a path added later (a {@code -sources.jar}, a
     * cross-published mirror) left paths serving: every holdable path without a pointer gets one and its row.
     */
    public static void converge(ArtifactStore store, Publication publication, StoreRepositoryInventory inventory,
                                QuarantineLog log, Instant now, String ecosystem, String coordinate, String version,
                                List<String> paths, String reason, String subject) throws IOException {
        for (String path : paths) {
            Optional<String> blob = publication.blob(path);
            if (blob.isEmpty()) {
                continue;
            }
            Withheld.mark(store, blob.get(), new ArtifactDescriptor(ecosystem, coordinate, version, path, null,
                    false, null, -1L));   // idempotent
            if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                continue;
            }
            HeldSubjects.hold(publication, store, path, blob.get(), ecosystem, coordinate, version);
            log.record(now, path, subject, Verdict.QUARANTINE, List.of(reason));
        }
        withholdBlobs(store, publication, log, now, ecosystem, coordinate, version, paths,
                inventory.blobHashes(ecosystem, coordinate, version), reason, subject);
    }

    /**
     * The blobs-namespace leg: {@link Withheld#mark marks} every content hash the version serves from {@code blobs/},
     * the retraction {@code Blobs.read} honours, and links a {@code /quarantine<servedPath>} review handle and log row
     * at each served path without a {@code publish/} pointer. A no-op for a version serving nothing from
     * {@code blobs/}.
     */
    private static void withholdBlobs(ArtifactStore store, Publication publication, QuarantineLog log, Instant now,
                                      String ecosystem, String coordinate, String version, List<String> paths,
                                      List<String> blobHashes, String reason, String subject) throws IOException {
        ArtifactDescriptor held = new ArtifactDescriptor(ecosystem, coordinate, version, null, null, false, null, -1L);
        for (String hash : blobHashes) {
            Withheld.mark(store, hash, held);
            // Every served path is recorded under every hash, whichever one its pointer names, so a release of a
            // byte-identical sibling finds it.
            HeldBy.record(store, hash, paths);
        }
        if (blobHashes.isEmpty()) {
            return;
        }
        String target = blobHashes.getFirst();   // the review handle's link target; serving reads Withheld, not it
        for (String path : paths) {
            if (publication.blob(path).isPresent()) {
                continue;   // a publish/-namespace path carries its own /quarantine pointer beside its blob mark
            }
            if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                continue;   // idempotent: the review handle is already linked
            }
            // Without a publish/ pointer, only the format's BlobLayout maps this path, so the record matters most here.
            HeldSubjects.hold(publication, store, path, target, ecosystem, coordinate, version);
            log.record(now, path, subject, Verdict.QUARANTINE, List.of(reason));
        }
    }
}
