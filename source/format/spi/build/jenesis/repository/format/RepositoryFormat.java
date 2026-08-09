package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

import module java.base;

/**
 * A repository protocol over the shared {@link ArtifactStore}: it claims a set of request paths and serves and
 * accepts artifacts in one client ecosystem's wire format (Maven, OCI/Docker, npm, PyPI, NuGet). Formats are
 * discovered with {@link java.util.ServiceLoader}, exactly as the storage backends are, so a new ecosystem is a
 * module that depends only on this SPI and {@code provides RepositoryFormat} - it inherits the content-addressed
 * storage, multi-tenancy, authorization, retention and console without touching them, and a deployment plugs in
 * whichever layouts it wants. Implementations are stateless: the dispatcher passes the already
 * tenant-and-repository-scoped store on each call.
 *
 * <h2>Contract</h2>
 * Every clause below is executable: {@code FormatContract} in the format testkit states it once and each format runs
 * it through a {@code FormatFixture}, so a clause is proven for Maven, the Jenesis module layout, OCI and raw alike
 * rather than being re-interpreted per format (&sect;13 - a guard one format applies to a shared concern is applied by
 * every format with that concern).
 * <ol>
 * <li><b>Thread-safety.</b> A format is a stateless singleton the server calls concurrently from every request
 *     thread: the dispatcher discovers one instance and hands it the already-scoped store on each call, so a format
 *     keeps no per-request state on itself and any field it does hold is immutable and shared-safe.</li>
 * <li><b>Idempotency / replay.</b> A repeated write of the same bytes to the same path converges on that path serving
 *     those bytes - the content-addressed blob dedupes and the pointer is a compare-and-set - so a client retry after
 *     a lost response never doubles an artifact or leaves a half-published one. A repeated {@code DELETE} of an absent
 *     path is a no-op, not a failure.</li>
 * <li><b>Absence sentinel.</b> A path this format claims but does not serve is answered with a status, never with
 *     {@code null}, an empty {@code 200} body or an escaping exception: an unpublished, withheld or reclaimed path is
 *     a {@code 404}, and a path whose <em>shape</em> this format cannot address is a {@code 404} too (clause 6).
 *     {@link #handles} is a pure predicate over the path and never touches the store.</li>
 * <li><b>Streaming (&sect;1).</b> An artifact body is never materialised: a write streams the request body into the
 *     content-addressed store (hash-on-write), a read streams the stored blob to the response with the length taken
 *     from the store's metadata, and a {@code HEAD} answers <em>from that metadata without opening the blob at all</em>
 *     - so a {@code HEAD} of a multi-gigabyte artifact costs a stat. Only a small generated document (an index, a
 *     metadata file, a manifest) may be buffered whole, and a format that buffers one bounds it explicitly.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} handed to {@link #handle} is already scoped to one
 *     tenant and repository, and it is the only storage a format may touch: every key a format composes stays under
 *     its own namespace within that scope, so no request path can address another format's or another tenant's keys.</li>
 * <li><b>Traversal refusal.</b> A request path is client-supplied and is refused before it becomes a store key. A path
 *     carrying a {@code .} or {@code ..} segment - exactly the shapes {@link ArtifactStore#traversalFree} names -
 *     addresses nothing in this format's namespace and is answered {@code 404}, storing and serving nothing; the same
 *     screen applies to every client-supplied name a format splices into a key (an image name, a tag, a digest). A
 *     format never percent-decodes its own path, so an encoded {@code %2e%2e} stays a literal name rather than
 *     becoming a traversal one layer below the dispatcher that already decoded. Past that shape screen the store's own
 *     caps still bind: a path that maps to a key beyond {@link ArtifactStore#MAX_SEGMENTS} or
 *     {@link ArtifactStore#MAX_KEY_BYTES} is refused by {@link ArtifactStore#key} with an
 *     {@link IllegalArgumentException} and nothing is stored.</li>
 * <li><b>Withhold-on-enumeration (&sect;4).</b> Every surface that materialises published <em>names</em> - a directory
 *     listing, a generated version index, a tag or catalog listing - routes its disclosure decision through the shared
 *     {@code ServableNames} screen (in practice by enumerating with {@code ScreenedNames}), so a version a hold
 *     retracts from serving leaves every enumeration surface in the same instant. A listing may never disclose a name
 *     whose {@code GET} answers {@code 404}.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #handle} on a {@code GET}/{@code HEAD} renders durably stored state only.
 *     Fetching an upstream is the separate, opt-in {@link ProxyFormat} capability, never something the hosted read
 *     path does on a miss.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing on a correctness-bearing path is swallowed: a store failure while
 *     serving or accepting surfaces rather than degrading to a {@code 404}, or to a {@code 201} that stored nothing.</li>
 * <li><b>Lifecycle / ownership.</b> The dispatcher discovers formats through {@link ServiceLoader} and keeps them for
 *     the life of the process; a format owns no thread, no client and no cache, and closes nothing. A format whose
 *     {@link #requiredConfig} is unset self-disables at discovery rather than failing at request time.</li>
 * <li><b>Ordering / concurrency.</b> Two requests against one path may run concurrently; a format's pointer writes are
 *     compare-and-set, so a concurrent republish resolves last-writer-wins rather than tearing. A format imposes no
 *     ordering on the dispatcher and behaves identically whatever order the other formats were discovered in.</li>
 * <li><b>Bounded work / cancellation (&sect;7).</b> Every enumeration is paged and capped: no surface materialises a
 *     whole namespace, and reaching a cap yields an explicit continuation (a {@code Link} header, a cursor) or fails by
 *     name - never a plausible-but-partial index, which a resolver reads as "that version does not exist". A generated
 *     document is a pure function of the stored state it renders, so the bytes are stable across two serves of
 *     unchanged state and a conditional re-fetch of it revalidates.</li>
 * <li><b>Durability / delivery.</b> The commit point of a publish is the moment the serving pointer becomes readable,
 *     and it lands <em>last</em>: bytes are content-addressed first, derived documents and sidecars next, and only then
 *     is the path linked - so a crash at any point leaves an unreferenced blob rather than a pointer to nothing.</li>
 * </ol>
 */
public interface RepositoryFormat {

    /** A stable identifier for the format, e.g. {@code maven}, {@code oci}, {@code npm}. */
    String name();

    /** Whether this format owns the given request path (the repository prefix already stripped). */
    boolean handles(String path);

    /** Serve or accept the request against the scoped store, writing the response through the exchange. Contract
     *  clauses 3-6 bind here: a path whose shape this format cannot address is a {@code 404}, an artifact body is
     *  streamed rather than buffered, and every composed key stays inside the scoped store's own namespace. */
    void handle(FormatExchange exchange, ArtifactStore store) throws IOException;

    /**
     * Whether this format's single-body writes are screened at the ingress edge (the default). {@code true} means an
     * edge (the free {@link build.jenesis.repository.store.Publication}-driven write path, the downstream deploy edge)
     * stores and runs the discovered {@link build.jenesis.repository.store.PublishInterceptor} chain over a claimed
     * {@code PUT}/{@code POST}/{@code PATCH} body <em>before</em> {@link #handle} sees it, then restreams the accepted
     * blob into {@link #handle}, whose job is now pure layout - lay the bytes out in this format's namespace, no
     * screening of its own. {@code false} means the format owns its whole screening choreography and the edge dispatches
     * its writes unscreened: the body never reaches the edge screen as one atomic upload, so the format screens where it
     * can. Only the OCI/Docker format overrides this to {@code false} - its {@code /v2/} protocol splits one artifact
     * across many requests (blob-upload sessions, then a manifest), which no single-body edge screen can gate, so it
     * screens at its own manifest choke point instead. This is the correct default for a single-body format, not a
     * back-compat shim; a new format inherits edge screening for free by leaving it alone.
     */
    default boolean screened() {
        return true;
    }

    /**
     * The format's mark as a small SVG {@link IconResource} embedded in this module, or empty when it ships none.
     * The console renders it beside the format's repositories and browse rows, and a server icon endpoint serves it
     * (immutable, cached) with a neutral fallback for a format that returns empty. A {@code default} so no existing
     * format is forced to carry one and the core stays icon-agnostic; a format with an icon overrides this to
     * {@code Optional.of(IconResource.svg(...))}, drawing only from permissively-licensed icon sets (its source and
     * licence recorded next to the module).
     */
    default Optional<IconResource> icon() {
        return Optional.empty();
    }

    /**
     * Request paths this format suggests seeding a fresh, empty repository with, so an evaluator has real data to
     * look at - browse rows, a proxied artifact, and (when a coordinate is old and benign-but-vulnerable) a lit-up
     * vulnerability and quarantine surface. Each entry is a plain request path this format {@link #handles claims}
     * (e.g. a Maven jar under {@code /maven/...}), which the demo seeder fetches through the format's own pull-through
     * path - the normal pipeline, so the inspectors screen the proxy leg and the compliance gate populates itself; no
     * blob is embedded here. A {@code default} of nothing, so a format carries none unless it opts in and the demo
     * mode is a no-op for it; suggestions are best-effort over the public registries and never fetch actual malicious
     * bytes. The seeder only runs against a completely empty artifact space and only when the {@code demo} flag is on.
     */
    default List<String> demoArtifacts() {
        return List.of();
    }

    /** The config keys this format cannot run without; empty (the default) for every self-contained format. A
     *  format whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The installed format of the given {@link #name() name}, discovered via {@link ServiceLoader} from this SPI
     *  module - the sanctioned lookup for a neutral consumer (an importer walking a format's upstream index, say)
     *  that must find one format by name without carrying its own {@code uses} clause. Empty when no module on the
     *  path provides it, or when the format is configured off ({@code jenesis.repository.<name>=false},
     *  {@link Features}) - a disabled format degrades exactly like a missing module. */
    static Optional<RepositoryFormat> installed(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return Features.active(format.name(), format.requiredConfig()) ? Optional.of(format) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
