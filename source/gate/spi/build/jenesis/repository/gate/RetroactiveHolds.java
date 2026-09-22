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
 * How a sweep places a hold on a version that is already published - the one way, whichever pass decides to. A
 * publish-time hold is placed by the gate at the moment the bytes arrive; a retroactive one is placed later, by a
 * pass that walked the inventory and found a version the current policy would not admit: a CVE that landed on a
 * known-exploited catalogue, a signature dial tightened after the fact. The pass decides; this places, so the two
 * passes cannot disagree on the order that keeps a crash mid-pass recoverable.
 *
 * <p>The order is the record first, then the pointers, then the log: the kind's own record - which CVEs, which
 * signature findings - is written by the pass through {@link Record} before any {@code /quarantine} pointer exists,
 * so no hold pointer can be found without the record that says what it is a hold on; each served path that carries
 * a {@code publish/} pointer gets its {@code /quarantine} pointer and its {@link QuarantineLog} row, pointer before
 * row, so the pointer-keyed review queue keeps a half-written hold visible; and the blobs-namespace leg marks every
 * content hash the version serves from {@code blobs/} and links a review handle at each served path that has no
 * pointer of its own, so a pure blobs-namespace release (npm, PyPI, Go) retracts from serving and still reaches the
 * review queue. Every step is idempotent, so a pass converges on re-run.
 */
public final class RetroactiveHolds {

    private RetroactiveHolds() {
    }

    /** The kind's own record, written before any pointer - what the hold is a hold on, in the kind's vocabulary. */
    @FunctionalInterface
    public interface Record {

        void write() throws IOException;
    }

    /** Whether any of a release's served paths currently carries a {@code /quarantine} hold pointer - the same read
     *  the gate screen's {@code withheld} makes, so serving and a pass agree on what is held. */
    public static boolean anyHeld(ArtifactStore store, List<String> paths) throws IOException {
        for (String path : paths) {
            if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Place a fresh hold on a version: the record, then a {@code /quarantine} pointer and its log row per served
     * path, then the blobs-namespace leg. Answers whether anything was held - a version with no live served pointer
     * and no content hash holds nothing and records nothing, which a pass alarms on for a blobs-namespace format.
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
            // Record-then-link through the one hold-placing primitive: the durable path -> coordinate record is
            // written before the pointer, so the hold stays answerable - by name-enumeration, by release and by
            // discard - once the module that lays the version out is no longer on the graph.
            HeldSubjects.hold(publication, store, entry.getKey(), entry.getValue(), ecosystem, coordinate, version);
            log.record(now, entry.getKey(), subject, Verdict.QUARANTINE, List.of(reason));
        }
        withholdBlobs(store, publication, log, now, ecosystem, coordinate, version, paths, blobHashes, reason, subject);
        return true;
    }

    /**
     * Converge a version this kind already holds: a crash after the first path's pointer, or a path added to the
     * version after it was held (a {@code -sources.jar}, a cross-published mirror), left paths serving that the
     * record says must be held. Every holdable path that lacks a pointer gets one and its row; a fully held version
     * loops over pointers that all exist, so the pass stays idempotent.
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
     * The blobs-namespace leg, idempotent so a pass converges on re-run after a crash: {@link Withheld#mark marks}
     * every content hash the version's blobs-namespace pointers resolve to - the serving retraction
     * {@code Blobs.read} honours - and links a {@code /quarantine<servedPath>} review handle (plus its log row) at
     * each served path that has no {@code publish/} pointer of its own, so the review queue surfaces the otherwise
     * pointer-less hold and the release/discard flow reaches it. A no-op for a version that serves nothing from
     * {@code blobs/} (Maven), and for a {@code publish/}-namespace path that already carries its own pointer.
     */
    private static void withholdBlobs(ArtifactStore store, Publication publication, QuarantineLog log, Instant now,
                                      String ecosystem, String coordinate, String version, List<String> paths,
                                      List<String> blobHashes, String reason, String subject) throws IOException {
        ArtifactDescriptor held = new ArtifactDescriptor(ecosystem, coordinate, version, null, null, false, null, -1L);
        for (String hash : blobHashes) {
            Withheld.mark(store, hash, held);   // the blobs-namespace serving retraction (a dual format's own keys too)
            // The full-hash form of the review index: every served path of the version holds every hash it serves,
            // whichever one hash its review pointer advertises, so a release of a byte-identical sibling finds it.
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
            // Record-then-link. This is the leg that matters most for the record: a pure blobs-namespace hold has NO
            // publish/ pointer at all, so its served path can be turned into a coordinate by exactly one thing - the
            // BlobLayout of the module that may later be uninstalled.
            HeldSubjects.hold(publication, store, path, target, ecosystem, coordinate, version);
            log.record(now, path, subject, Verdict.QUARANTINE, List.of(reason));
        }
    }
}
