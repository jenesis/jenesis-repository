/**
 * Focused unit tests for the garbage-collection SPI and its {@code mark-sweep} reference implementation, driven
 * against a real {@code FilesystemArtifactStore} on a JUnit
 * {@code @TempDir} with an injectable test clock and the shared fault-injecting store, so every data-safety claim
 * is exercised without a network: the no-op default (nothing installed or selected reclaims nothing); an orphan is
 * condemned by one pass and collected only by a later one, so a blob younger than one collection interval always
 * survives; a referenced, re-linked (the dedup re-publish race: condemn, re-link, un-condemn) or unrecognisable
 * blob is never deleted - including across a crashed reference flush, where the walk's flush-before-checkpoint
 * ordering must keep a committed cursor from lying about a lost reference; the dry-run {@code plan} previews the
 * due blobs and writes nothing; condemned bookkeeping and superseded reference shards converge idempotently across
 * kills at each phase boundary; and per-segment claims keep a second collector off a live pass until the holder's
 * lease expires. The reference-lending seam is exercised against its negative control: a format whose served blobs
 * are reachable only through a stored document loses them to the sweep when it lends nothing, keeps every one when
 * it lends, is asked only about keys beneath its own declared roots, and fails the whole pass rather than answering
 * a short set - a short set being indistinguishable from a complete one, and every hash missing from it a live blob
 * deleted.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.gc
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gc.test {
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.gc.walk;
    requires build.jenesis.repository.settings;
    // The reference-lending seam, exercised through a format-neutral stand-in: the collector's half of is that
    // it unions a lender's set into the shards the sweep reads, not how any one format spells its documents.
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
