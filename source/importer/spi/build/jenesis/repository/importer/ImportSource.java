package build.jenesis.repository.importer;

import module java.base;

/**
 * A foreign repository to import from: it enumerates every asset of a source repository and hands each one - its
 * ecosystem format, its path within the repository, and its bytes - to a consumer. A source is the read half of a
 * migration; the repository's import orchestrator is the write half, routing each asset to the
 * {@link build.jenesis.repository.format.RepositoryImporter} that handles its format. An implementation ships as its
 * own module that provides an {@link ImportSourceProvider}; the server discovers them with
 * {@link java.util.ServiceLoader}, so supporting another incumbent is a matter of adding a module, with the server
 * none the wiser. Nexus, Artifactory, the vendor-neutral Maven tree walk, the format-native index walk and jenesis
 * itself are the built-in ones.
 * Every implementation streams through the same {@link build.jenesis.repository.format.ProxyFormat.Fetcher} the proxy
 * uses, so an import is tested without the network.
 *
 * <h2>Contract</h2>
 * The read half's behavioural contract, proven per connector by {@code ImportContract} in the importer testkit. The
 * provider that builds a source ({@link ImportSourceProvider}) carries the construction-side clauses.
 * <ol>
 * <li><b>Thread-safety.</b> A source is built per migration and walked by one thread; it need not be concurrent. It
 *     <em>is</em> immutable in its configuration - {@code withCredentials}/{@code from} answer a new instance - so a
 *     resumed walk never mutates the source an interrupted one held.</li>
 * <li><b>Idempotency / replay.</b> {@link #forEach} is replayable. A walk resumed from a cursor a prior run reported
 *     continues rather than re-delivering what that run had fully consumed, and re-running it from the same cursor
 *     delivers the same assets in the same order. A cursor the source can no longer place (the incumbent's index moved)
 *     restarts the walk rather than skipping the remainder - an import is idempotent, so re-importing is safe where
 *     losing assets is not.</li>
 * <li><b>Absence sentinel.</b> An empty repository is a walk that reports no asset and one terminal {@code null}
 *     checkpoint, never an exception. A {@code null} cursor means "complete"; a non-{@code null} one means "resume
 *     here".</li>
 * <li><b>Streaming (&sect;1).</b> {@link Content#open} is deferred and unbuffered: the asset's bytes are not fetched
 *     while it is enumerated, and when it is opened the stream comes straight off the network, so the consumer's copy
 *     to storage is the only pass over the body. A whole-repository listing is itself streamed where the incumbent
 *     serves one document for it.</li>
 * <li><b>Error visibility (&sect;9).</b> An incumbent that refuses, is absent or cannot answer surfaces as an
 *     {@link ImportFailure} carrying its {@link ImportFailure.Kind} - auth, missing, transient and protocol are
 *     distinguishable without reading the message. A malformed <em>entry</em> is different: an incomplete or
 *     traversal-laced listing row is skipped and the walk continues, because one bad row must not abort a migration.</li>
 * <li><b>Traversal refusal.</b> A reported {@link Asset} path is repository-relative and {@link #safePath} - a listing
 *     path derives from a name someone published to the incumbent, and it becomes a store write on the write half. A
 *     source screens every path it reports; the importer refuses one that slipped through
 *     ({@code RepositoryImporter.importable}), and the two screens agree by construction.</li>
 * <li><b>Fetch refusal.</b> The twin of the clause above, on the half that reaches outward. A listing row carries a
 *     <em>path</em>, which the clause above screens, and frequently also a <em>location</em> the source must
 *     dereference to obtain the bytes - and that location is likewise a value the incumbent supplied, so it is
 *     likewise not to be trusted. A source therefore fetches only through the {@link build.jenesis.repository.format.ProxyFormat.Fetcher} it was
 *     handed, which is already screened ({@code ImportScreen}, applied by {@code ImportSourceProvider.open}); it
 *     may wrap that fetcher to add credentials, and it may not replace it, build an HTTP client of its own, or
 *     dereference a location by any other route. A location the screen refuses fails the walk loudly rather than
 *     being skipped, because a refused download is either an incumbent that is misconfigured or one that is
 *     hostile, and a walk that quietly omits the asset reports the same "nothing here" as an empty repository.
 *     <p>Stated on this side as well as on {@code ImportSourceProvider}'s clause 10 because the two halves are
 *     enforced in different places and a contract that constrains what a provider is <em>given</em> but not what a
 *     source may <em>do</em> with it leaves the more dangerous half unstated - which is structurally why the
 *     download-URL screen was once per-connector and drifted into three different answers across the
 *     connectors.</li>
 * <li><b>Ordering / concurrency.</b> The enumeration order is deterministic for a given source state, because that is
 *     what makes a cursor mean anything: a resumed walk must be able to skip exactly what the interrupted one
 *     completed. {@link Checkpoint#reached} is called only after every asset of a batch has been fully consumed, so a
 *     cursor never claims progress the consumer has not made.</li>
 * <li><b>Bounded work / cancellation.</b> The walk pages rather than materialising a whole catalogue, and every
 *     recursive descent carries a depth cap. Reaching a cap is an explicit {@link ImportFailure}, never a truncated
 *     asset list that a caller would read as a complete migration.</li>
 * <li><b>Durability / delivery.</b> A source is stateless and durable nowhere; the cursor it reports is the only
 *     progress token, and persisting it is the caller's job. A crash between an asset's import and the next checkpoint
 *     re-delivers that asset on resume, which the content-addressed store absorbs.</li>
 * </ol>
 */
public interface ImportSource {

    /** Enumerate the source's assets, handing each to {@code consumer}, and reporting a resume cursor to
     *  {@code checkpoint} after each batch is fully consumed - an opaque token to resume the walk from, or
     *  {@code null} once the walk is complete. A walk that is interrupted can be resumed from the last reported
     *  cursor (a source that supports resuming takes it when created); a source with no pagination reports a single
     *  {@code null} at the end. */
    void forEach(Asset consumer, Checkpoint checkpoint) throws IOException;

    /** Whether a listing-derived path is safe to report as an asset's repository-relative path: relative, with no
     *  empty, {@code .} or {@code ..} segment and no backslash. The path a source reports becomes a store write on
     *  the import's write half, and a foreign listing is only semi-trusted (an asset's path can derive from a name
     *  someone published to the incumbent) - so a source skips an asset whose path fails this instead of letting a
     *  traversal-laced name aim the write outside the import's scope. */
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

    /** One asset of the source: the ecosystem {@code format}, the {@code path} within the repository, and a handle
     *  that downloads its bytes. The content is read lazily, so an asset whose format no importer handles is never
     *  downloaded - the orchestrator skips it without spending the bandwidth. */
    @FunctionalInterface
    interface Asset {
        void accept(String format, String path, Content content) throws IOException;
    }

    /** A deferred download of one asset's bytes, opened only once an importer has claimed the asset's format. The
     *  stream copies straight from the source to storage, so a large artifact is never buffered whole; the caller
     *  owns and closes it. */
    @FunctionalInterface
    interface Content {
        InputStream open() throws IOException;
    }

    /** Notified after a batch is fully consumed with the cursor to resume from (the next page's token), or
     *  {@code null} when the walk is complete - the seam a job uses to persist progress for a resumable re-sync. */
    @FunctionalInterface
    interface Checkpoint {
        void reached(String cursor) throws IOException;
    }
}
