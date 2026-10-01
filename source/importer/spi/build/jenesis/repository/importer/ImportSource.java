package build.jenesis.repository.importer;

import module java.base;

/**
 * A foreign repository to import from: it enumerates every asset of a source repository and hands each - its format,
 * its path within the repository, its bytes - to a consumer, the read half of a migration; the orchestrator routes each
 * to the {@link build.jenesis.repository.format.RepositoryImporter} of its format. A connector ships as its own module
 * providing an {@link ImportSourceProvider}, discovered with {@link java.util.ServiceLoader}. Every implementation
 * streams through the shared {@link build.jenesis.repository.format.ProxyFormat.Fetcher}, so an import is tested
 * without the network.
 *
 * <h2>Contract</h2>
 * The read half's contract, proven per connector by {@code ImportContract}; {@link ImportSourceProvider} carries the
 * construction side.
 * <ol>
 *   <li><b>Thread-safety.</b> Built per migration and walked by one thread. Immutable in its configuration -
 *       {@code withCredentials}/{@code from} answer a new instance - so a resumed walk never mutates an interrupted
 *       one's source.</li>
 *   <li><b>Idempotency / replay.</b> {@link #forEach} resumed from a reported cursor continues rather than
 *       re-delivering what was consumed, and the same cursor delivers the same assets in the same order. A cursor the
 *       source can no longer place restarts the walk rather than skipping the rest: re-importing is safe, losing assets
 *       is not.</li>
 *   <li><b>Absence sentinel.</b> An empty repository reports no asset and one terminal {@code null} checkpoint, never
 *       an exception. A {@code null} cursor means complete; any other means resume here.</li>
 *   <li><b>Streaming.</b> {@link Content#open} is deferred and unbuffered: nothing is fetched while enumerating, and
 *       the opened stream comes off the network, so the consumer's copy is the one pass. A whole-repository listing
 *       served as one document is streamed.</li>
 *   <li><b>Error visibility.</b> An incumbent that refuses, is absent or cannot answer surfaces as an
 *       {@link ImportFailure} of the matching {@link ImportFailure.Kind}. A malformed <em>entry</em> is skipped and the
 *       walk continues.</li>
 *   <li><b>Traversal refusal.</b> A reported {@link Asset} path is repository-relative and {@link #safePath}, since it
 *       derives from a name published to the incumbent and becomes a store write. The importer refuses one that slipped
 *       through ({@code RepositoryImporter.importable}); the two screens agree by construction.</li>
 *   <li><b>Fetch refusal.</b> A row's location is incumbent-supplied too, so a source fetches only through the fetcher
 *       it was handed, already screened ({@code ImportScreen}, applied by {@code ImportSourceProvider.open}); it may
 *       wrap it to add credentials, never replace it, build its own client or dereference a location another way. A
 *       refused location fails the walk loudly rather than being skipped, which would read as an empty repository.</li>
 *   <li><b>Ordering / concurrency.</b> Enumeration order is deterministic for a source state, so a cursor means
 *       something. {@link Checkpoint#reached} is called only after every asset of a batch is consumed.</li>
 *   <li><b>Bounded work / cancellation.</b> The walk pages, and every recursive descent has a depth cap; reaching a cap
 *       is an explicit {@link ImportFailure}, never a truncated list read as complete.</li>
 *   <li><b>Durability / delivery.</b> A source is stateless; its cursor is the only progress token, persisted by the
 *       caller. A crash between an asset's import and the next checkpoint re-delivers it, which the content-addressed
 *       store absorbs.</li>
 * </ol>
 */
public interface ImportSource {

    /** Enumerate the assets, handing each to {@code consumer} and reporting a resume cursor to {@code checkpoint} after
     *  each batch is fully consumed, {@code null} once complete. A source without pagination reports one {@code null}
     *  at the end. */
    void forEach(Asset consumer, Checkpoint checkpoint) throws IOException;

    /** Whether a listing-derived path is safe to report: relative, with no empty, {@code .} or {@code ..} segment and
     *  no backslash. A source skips an asset failing this, since the path becomes a store write and may derive from a
     *  name published to the incumbent. */
    static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** One asset: its ecosystem {@code format}, its {@code path} in the repository, and a lazy handle to its bytes, so
     *  an asset no importer handles is never downloaded. */
    @FunctionalInterface
    interface Asset {
        void accept(String format, String path, Content content) throws IOException;

        /**
         * A row the connector refused to carry, and why, so a listing whose every row was refused does not finish
         * looking like an empty source; {@link Reason#UNSAFE_PATH} in particular is the signal of a hostile source. A
         * default no-op, so a lambda implementing this interface compiles.
         *
         * @param path the offending path as given, possibly hostile: a caller rendering it treats it as untrusted text,
         *     never as a path it resolves
         * @param reason what was wrong with it
         */
        default void dropped(String path, Reason reason) {
        }
    }

    /** Why a connector refused a row, kept apart since a hostile source and a broken listing are different facts. */
    enum Reason {

        /** The path was not {@link #safePath} - a traversal attempt, an absolute path, or a control character. */
        UNSAFE_PATH,

        /** The listing row was missing a field the connector needs to address the asset at all. */
        INCOMPLETE_ENTRY,

        /** A download URL the source published could not be parsed as a URI. */
        MALFORMED_URL
    }

    /** A deferred download of one asset's bytes, opened once an importer claimed its format; the stream copies from the
     *  source to storage, never buffered, and the caller closes it. */
    @FunctionalInterface
    interface Content {
        InputStream open() throws IOException;
    }

    /** Notified after a batch is consumed with the cursor to resume from, or {@code null} when complete: where a job
     *  persists progress. */
    @FunctionalInterface
    interface Checkpoint {
        void reached(String cursor) throws IOException;
    }
}
