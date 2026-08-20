package build.jenesis.repository.format;

import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

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
 * <p>It extends {@link IconContributor}, which is where {@link #name()} and the optional console mark come from: a
 * format is one of two unrelated plug-in families that lend the console a mark of their own, and the interface they
 * share is what stops the second family from arriving with a second copy of the neutral fallback, the rendering rule
 * and the generated scheme. A format therefore declares a mark exactly as a findings plug-in does, and the console
 * resolves both through {@link Marks} - see {@link FormatMarks} for the format family's own mapping from a storage
 * namespace or an ecosystem to the format that owns it.
 *
 * <h2>Contract</h2>
 * Most clauses below are executable: {@code FormatContract} in the format testkit states one once and each format runs
 * it through a {@code FormatFixture}, so it is proven for Maven, the Jenesis module layout, OCI and raw alike rather
 * than being re-interpreted per format (&sect;13 - a guard one format applies to a shared concern is applied by every
 * format with that concern). The enforcement is named per clause, because it is not uniform:
 * <ul>
 *   <li><b>kit-proven</b> - clauses 2, 3 and 4 ({@code PUBLISH_SERVES_EXACT_BYTES}, {@code HEAD_ANSWERS_FROM_METADATA}),
 *       6 ({@code REQUEST_PATH_TRAVERSAL_REFUSED}, judged by walking the store afterwards rather than by a status
 *       code), 7 ({@code WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION}) and 12's determinism half
 *       ({@code GENERATED_INDEX_IS_REVALIDATABLE});</li>
 *   <li><b>documented only</b> - clauses 1, 4, 5, 7, 8, 9, 10, 11, 12's bounded-listing half, 13, 14, 15 and 16.
 *       They are stated here in a form a test could be written against, not because one exists.
 *
 *       <p>Clauses 4, 7, 12, 14 and 15 were once approximated by scans over the free tree's source text. They are
 *       not any more. Such a scan catches a <em>new</em> offending call site, never a wrong one - it reads this
 *       repository's spelling rather than an implementation's behaviour, and every format that got the spelling
 *       right passed whatever it then did. These clauses bind an implementer of this interface, wherever it is
 *       written; what would hold one to them is a driver in the contract kit, and until that exists they are
 *       stated, not enforced. Saying so is the point: a clause nobody checks is a clause an implementer can still
 *       read, whereas a clause a scan pretended to check is one nobody looks at twice.</li>
 * </ul>
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
 *     carrying a {@code .} or {@code ..} segment, or a {@code \} anywhere - exactly the shapes
 *     {@link ArtifactStore#traversalFree} names, the backslash among them because it is a separator on a
 *     Windows-hosted filesystem backend and a literal on the object stores (D-003) -
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
 * <li><b>Store-then-gate, and screening is not the format's.</b> A hosted single-body publish is
 *     <em>stored before it is judged</em>: the ingress edge streams the body into the content-addressed store
 *     (hash-on-write, never a {@code byte[]}), runs the discovered {@code PublishInterceptor} chain over the resulting
 *     descriptor exactly once, and restreams the accepted blob into {@link #handle} - whose job is then pure layout.
 *     The whole choreography is {@code Publication.commit}'s and is stated once in its own contract; a format's
 *     obligations against it are these three, and they are what implementations have actually disagreed about:
 *     <ul>
 *       <li>a format <b>runs no screen of its own</b>. It does not invoke the interceptor chain, and a second
 *           format-embedded pass over already-screened bytes is not this model - the core structural guard
 *           clause 14 refuses one;</li>
 *       <li><b>the body the edge gates must be the artifact itself</b>, not a container that carries it. A publish
 *           whose request body is an <em>envelope</em> - a JSON document with the artifact base64'd inside it, a
 *           length-prefixed frame, a multipart form - has been screened only if the bytes the chain hashed and
 *           assessed are the bytes that later serve. Gating the envelope and then writing a <em>second</em>
 *           content-addressed object under a hash no interceptor ever saw satisfies the letter of "one body was
 *           gated" and none of its purpose: the artifact a client downloads was never assessed, and the guarantee
 *           stops being statable - "we screened the request that contained it" is not a claim anyone can act on. A
 *           format whose protocol wraps its artifact must therefore unwrap first and drive the commit operation over
 *           the artifact's own bytes, exactly as an unwrapped single-body format does. Screening the envelope
 *           <em>as well</em> is fine and sometimes useful (a manifest carries the coordinate); screening it
 *           <em>instead</em> is the fail-open direction, and a format that cannot unwrap at the edge is in the
 *           {@code screened() == false} case below rather than the {@code true} one;</li>
 *       <li>{@link #screened()} is the declaration of which model this format is in, and it is a statement about the
 *           <em>protocol</em>, never a convenience: {@code true} (the default, and the right answer for every
 *           single-body format whose body <em>is</em> its artifact) means the edge screened the artifact before
 *           {@link #handle} saw it; {@code false} means
 *           this format's wire protocol has no single body an edge could gate - OCI's {@code /v2/} push splits one
 *           artifact across a blob-upload session and a manifest - so the format screens at its own documented choke
 *           point instead, by driving the shared commit operation there. A format that declares {@code false} and then
 *           screens nowhere is unscreened, which is the fail-open direction;</li>
 *       <li>the layout it writes is <b>idempotent and pointer-last</b> (clauses 2 and 13), because the accepted layout
 *           is exactly what a replayed commit re-runs to repair a crash: same bytes in, byte-identical store out.</li>
 *     </ul>
 *     A pull-through cache fill is deliberately <em>outside</em> this: see {@link ProxyFormat}'s own contract for what
 *     gates a proxied body and what does not.</li>
 * <li><b>Archive inflation cap (&sect;13).</b> A format that decompresses part of an artifact to read a declaration -
 *     a jar manifest, a {@code module-info.class}, a control member, an embedded index - bounds the <b>decompressed</b>
 *     size of each entry it materialises, not merely the stored one, because the ratio is the attacker's to choose: a
 *     kilobyte blob can inflate to gigabytes and the read happens on the publish thread of a shared JVM. Entries the
 *     format is not reading are streamed past, never materialised - <b>but streamed past is not free</b>, so the walk
 *     that streams them is bounded too (see the walk bound below): the two bounds are separate dimensions, and a
 *     format that applies only the first is defeated by a one-byte manifest behind a hundred gigabytes of payload.
 *     <ul>
 *       <li><b>The bound is one shared bound, not a constant per format.</b> It is
 *           {@link build.jenesis.repository.store.ArchiveInflation#largestEntry()} - named, documented, and settable by
 *           an operator through {@link build.jenesis.repository.store.ArchiveInflation#LARGEST_ENTRY_KEY} - and the
 *           read that applies it is {@link build.jenesis.repository.store.ArchiveInflation#entry(java.io.InputStream)}.
 *           A format whose own document is legitimately larger than a manifest passes its ceiling explicitly to the
 *           two-argument overload and states its reason at that call site; what it may not do is hold a private
 *           constant, because private constants are parallel by convention only, and a new format then arrives not
 *           with a different bound but with none.</li>
 *       <li><b>Over the bound the entry declares nothing</b> - the format degrades to "this artifact carries no such
 *           declaration" and the publish proceeds ({@code Entry.orNull()}) - rather than inflating on or handing back
 *           the prefix it did read. A member whose content is the artifact's <em>identity</em>, or a guard's only
 *           input, takes {@code Entry.required(...)} and fails closed instead, because degrading that one would admit
 *           an artifact nothing screened. What no format may do is the third thing: treat an unread entry as an
 *           <em>absent</em> guard, which is the fail-open shape. The two outcomes stay distinguishable at the type -
 *           a bound-stopped read answers {@code TRUNCATED} where an empty archive answers {@code EXHAUSTED} - so
 *           "declares nothing" and "we stopped looking" are never the same fact.</li>
 *       <li><b>The walk that finds the entry is bounded by the same kind of rule.</b> How far a read may run
 *           <em>through</em> an archive to reach the member it wants is
 *           {@link build.jenesis.repository.store.ArchiveWalk#largestWalk()}, settable by an operator through
 *           {@link build.jenesis.repository.store.ArchiveWalk#LARGEST_WALK_KEY}, and the walk that applies it is
 *           {@link build.jenesis.repository.store.ArchiveWalk#walk(java.io.InputStream,
 *           build.jenesis.repository.store.ArchiveWalk.Walker)}. A format whose member legitimately sits behind a
 *           large payload derives a body-relative ceiling with
 *           {@link build.jenesis.repository.store.ArchiveWalk#largestWalk(long, long)} and states its ratio at that
 *           call site; what it may not do, here as above, is hold a private constant and a private byte-counting
 *           stream of its own. The <b>outcome</b> obligation is identical and matters more here than anywhere: a walk
 *           the bound stopped answers {@code TRUNCATED} and carries <em>nothing</em> - not even the member it passed
 *           on the way, which a crafted archive may have placed early as a decoy - so "this artifact carries no such
 *           member" and "we never reached one" stay different answers. A format that lets a bound-stopped walk read
 *           as an empty archive has the fail-open shape this clause exists to refuse.</li>
 *       <li><b>Ignoring it is visible.</b> clause 15 refuses a
 *           module that opens a decompressing stream without routing an entry through the shared bound and fails the
 *           build, with a reason-bearing allowlist for the walks that materialise nothing. It catches a <em>new</em>
 *           unbounded inflation the moment it is written, which is what turns this clause from a rule a format could
 *           silently arrive without into one it has to answer.</li>
 *     </ul></li>
 * <li><b>Console mark (the inherited {@link IconContributor} half).</b> A format may lend the console a small SVG
 *     mark, and {@link #name()} is the identity that mark is attributed to. Both obligations are stated once on
 *     {@link IconContributor} rather than restated here, because a format is only one of the families that carry
 *     them; the two that bind hardest on a format are worth naming at this seam anyway. <b>Read purity
 *     (&sect;10):</b> {@link IconContributor#icon()} performs no I/O at all - it is called once per rendered row on
 *     a console page, so the document is a constant in the format's own module, never a classpath resource read
 *     when first asked. <b>The mark stays in the format's module:</b> the core holds no brand mark, and a format
 *     that declares none does not invent a placeholder - {@link Marks} answers with the generated figure derived
 *     from {@link #name()}, so "this format declares no mark" is one fact with one rendering rather than as many as
 *     there are formats. A format's mark is deployment-static and carries no tenant or repository data
 *     (&sect;6), which is why a serving endpoint may hand it out open, immutable and cached.</li>
 * </ol>
 */
public interface RepositoryFormat extends IconContributor {

    /** A stable identifier for the format, e.g. {@code maven}, {@code oci}, {@code npm}. It is the format's feature
     *  toggle key ({@code jenreg.<name>}), the key {@link #installed(String)} looks one up by, and - as
     *  the inherited {@link IconContributor#name()} - the identity its console mark is attributed to and generated
     *  from, so it is chosen once and not renamed. */
    @Override
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
     *  path provides it, or when the format is configured off ({@code jenreg.<name>=false},
     *  {@link Features}) - a disabled format degrades exactly like a missing module. */
    static Optional<RepositoryFormat> installed(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return Features.active(format.name(), format.requiredConfig()) ? Optional.of(format) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Every installed, switched-on format, discovered via {@link ServiceLoader} from this SPI module and resolved
     *  through the shared {@link Providers#all} additive policy - the sanctioned lookup for a neutral consumer that
     *  must see the whole set rather than one by name (the console resolving a namespace's mark, say) without
     *  carrying a {@code uses} clause of its own. Ordered by name, so a consumer's answer does not depend on the
     *  module path's discovery order, and a format configured off ({@code jenreg.<name>=false}) or with
     *  required config unset is absent exactly as a missing module is. Two formats answering to one name is a
     *  packaging error and throws rather than being settled by discovery order. */
    static List<RepositoryFormat> installed() {
        return Providers.all("format",
                ServiceLoader.load(RepositoryFormat.class),
                RepositoryFormat::name,
                format -> Features.active(format.name(), format.requiredConfig()),
                Optional::of);
    }
}
