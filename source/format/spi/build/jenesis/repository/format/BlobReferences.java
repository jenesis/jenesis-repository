package build.jenesis.repository.format;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

import module java.base;

/**
 * The optional reference-scan capability of a {@link RepositoryFormat} that serves its artifacts out of the shared
 * content-addressed {@code blobs/} namespace under its own store-key roots ({@code oci/}, {@code npm/}, {@code pypi/},
 * ...) rather than through the core's {@code Publication} ({@code publish/}). Detected with {@code instanceof},
 * exactly like {@link ArtifactLayout} and {@link ProxyFormat}, so a format opts in without a core edit and a format
 * with no blobs namespace is not forced to implement it.
 *
 * <p>It answers the one question garbage collection must never get wrong: <b>which content blobs is this format still
 * serving?</b> A collector's mark phase enumerates the leaf objects under {@link #blobRoots() the declared roots} and
 * counts the hash each leaf's <em>body</em> names; a blob no live pointer names is reclaimed. That model - one pointer
 * body, one blob - is exact for a format whose every served blob has a pointer, and <em>false</em> for one whose blobs
 * are reachable only through a stored document. OCI is the standing example: an image's config and layer digests live
 * inside the manifest JSON, behind no store key at all, and a manifest pulled by digest carries no tag pointer either -
 * so a reference scan that reads pointer bodies alone condemns and then deletes a live image's layers while its
 * manifest keeps serving. {@link #references} is how such a format declares the rest: given one key beneath its roots,
 * the additional blob hashes that key keeps alive.
 *
 * <p><b>The collector still parses no format's documents.</b> This seam is what keeps that true: the derivation lives
 * in the format that owns the layout, the collector only unions what it is told with the hashes it read itself. It is
 * the same division of labour {@link ArtifactLayout} already draws for eviction - the format is the single owner of
 * its layout knowledge and lends it through an interface.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link RepositoryFormat}: that contract still binds, and the clauses below state
 * what lending a reference set to a <em>deletion</em> path adds. Deletion is the one unrecoverable act in the product,
 * so every clause here is written in the fail-closed direction: over-reporting a reference wastes storage, and
 * under-reporting one destroys a served artifact.
 * <ol>
 * <li><b>Thread-safety.</b> Both methods are stateless reads on the format singleton, called concurrently from the
 *     collection sweeps of several nodes; an implementation keeps no per-call state on itself.</li>
 * <li><b>Absence sentinel.</b> {@link #references} answers an <em>empty list</em> for a key it does not recognise -
 *     its own staging, session and sidecar spaces, or a key under another format's root it was handed anyway - and
 *     {@code null} is never a legal return. Empty means "this key keeps no further blob alive", never "I could not
 *     tell".</li>
 * <li><b>Error visibility (&sect;9) - never degrade to a short list, and say which refusal it is.</b> A key the format
 *     recognises but cannot resolve (a stored document that is absent-but-expected, unparseable, or past the format's
 *     own parse bound) <em>throws</em>. It must never answer a partial set: the caller cannot distinguish a short list
 *     from a complete one, and every hash omitted is a live blob the next sweep deletes. Throwing fails the
 *     enumeration, and a collection pass that did not complete deletes nothing - the safe outcome.
 *     <p>The two ways a call can fail are <em>different facts</em> and carry different types, because a consumer
 *     other than a collector has to tell them apart:
 *     <ul>
 *       <li>{@link Unresolvable} - <b>the stored document refuses to be read</b>: it is unparseable, or past the
 *           bound the format enforces when it ingests one. The store answered; the bytes are the problem, and no
 *           retry changes that. A format raises exactly this at each of its own refusal sites;</li>
 *       <li>any other {@link IOException} - <b>the store failed</b>: an outage, a timeout, a permission. Nothing is
 *           known about the document, and the same call may well succeed a minute later.</li>
 *     </ul>
 *     A collector catches <em>neither</em>: both mean "I cannot enumerate", and the only safe reading of that on a
 *     deletion path is to fail the pass. The distinction exists for the consumers whose blast radius is the other way
 *     round - a browse, an enforcement sweep, a hold release - which derive the same set to decide what to
 *     <em>withhold</em>. There, propagating would fail a whole pass over one corrupt legacy document while degrading
 *     to a shorter set only under-enforces; so such a consumer catches {@link Unresolvable} alone, degrades, and says
 *     so, and still propagates a store failure rather than turning every store hiccup into an under-enforced hold.
 *     One derivation, two postures, chosen by the caller and not by the format - this is deliberately the opposite
 *     posture to a retroactive hold's blob-set derivation, where degrading to fewer hashes under-enforces a hold;
 *     here it destroys data.</li>
 * <li><b>Read purity (&sect;10).</b> The methods read the store and nothing else - no network, no write, not even a
 *     repair. Unlike the other layout seams they <em>may</em> open a stored blob, because a format whose references
 *     live inside a document has nowhere else to read them; that read is bounded (clause 6) and this seam is never
 *     called from a serving read path.</li>
 * <li><b>Ordering / determinism.</b> The answer is a function of the key and the durable store contents only, never of
 *     discovery order, and is stable across repeated calls over an unchanged store - a mark pass replayed after a
 *     crash must re-derive the same reference set.</li>
 * <li><b>Bounded work (&sect;7, &sect;1).</b> A document read to derive references is capped at the same bound the
 *     format enforces when it ingests one, and a document that names further documents is expanded with an explicit
 *     work-list and an emitted set, never self-recursion - a hostile nesting must not overflow the sweep's stack or
 *     read a multi-gigabyte blob into it. Reaching a bound is clause 3's throw, never a short list.</li>
 * <li><b>Completeness.</b> {@link #blobRoots()} must name <em>every</em> root the format pins a blob under and
 *     {@link #references} must name every blob reachable from the given key that the format still serves. A root or a
 *     hash omitted here is a blob the collector reclaims out from under a live artifact.</li>
 * </ol>
 */
public interface BlobReferences {

    /** The top-level store-key prefixes under which this format keeps the pointers and documents that name its content
     *  blobs (e.g. {@code "oci"}, {@code "npm"}; some formats own more than one). A reference scan lists every leaf
     *  beneath each, counts the blob its body names, and asks {@link #references} what else that key keeps alive. Must
     *  name EVERY root the format pins a blob under, or garbage collection reclaims a blob the format still serves. */
    List<String> blobRoots();

    /**
     * The blob hashes {@code key} keeps alive <em>beyond</em> the one its own pointer body names - the set a reference
     * scan adds when it visits this key. {@code key} is a full store key beneath one of this format's
     * {@link #blobRoots() roots}; the answer is bare lower-case 64-hex SHA-256 strings, in any order, duplicates
     * harmless.
     *
     * <p>Empty (the default) is exact for a format whose every served blob is named by a pointer body - the scan
     * already counted it - and for any key this format does not recognise. A format overrides it when a served blob is
     * reachable only through a stored document: OCI resolves an image's manifest, config, layer and sub-manifest
     * digests out of the manifest JSON, which no store key names.
     *
     * <p>Answering short is the one failure this seam may not have: a hash omitted here is a live blob the next sweep
     * condemns and the one after that deletes. A key this format recognises but cannot resolve therefore raises an
     * {@link IOException} rather than returning what it managed to collect (clause 3) - the pass fails, and a pass that
     * did not complete deletes nothing. A document that refuses to be <em>read</em> - unparseable, or past the bound
     * the format enforces when it ingests one - raises the named {@link Unresolvable}, so a store failure stays a plain
     * {@link IOException} and the two are never confused by a consumer that must degrade on one of them.
     */
    default List<String> references(String key, ArtifactStore store) throws IOException {
        return List.of();
    }

    /**
     * The named refusal of clause 3: this format recognises the key, but the stored document that names the rest of
     * its blobs <b>cannot be read</b> - it does not parse, or it is past the parse bound the format enforces when it
     * ingests one. The store answered; the bytes are the problem, so no retry and no failover changes the answer.
     *
     * <p>It exists so the one derivation can serve two consumers with opposite blast radii, without either guessing.
     * A garbage collector treats it exactly as it treats any other {@link IOException} - it cannot enumerate, so it
     * fails the pass and deletes nothing. A consumer deriving the same set to decide what to <em>withhold</em> (a
     * console browse, an enforcement sweep, a hold release) catches this one, degrades to what it can name and says
     * so, and lets a plain {@link IOException} propagate - because degrading on a store hiccup would silently
     * under-enforce a hold, while propagating on one corrupt legacy document would fail a whole repository's pass.
     * Catching {@code IOException} to get that behaviour would take both; catching this takes exactly the one that
     * will never resolve itself.
     *
     * <p>It is deliberately <em>not</em> raised for an absent document. An absent one is residue - a blob already
     * collected - and clause 2's empty answer is the honest one there.
     */
    class Unresolvable extends IOException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** @param message names the key and the document, and says which of the two refusals this is, so an operator
         *                can act on it (discard the document) rather than re-run the pass and see it again. */
        public Unresolvable(String message) {
            super(message);
        }

        public Unresolvable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Every installed format that lends its reference sets, discovered through the same {@link ServiceLoader}
     * {@code uses RepositoryFormat} clause the dispatcher and the layout seams ride - never a second registry or a
     * second {@code provides} clause per format (design gate 3). A format switched off
     * ({@code jenreg.<name>=false}, {@link Features}) is skipped exactly as a missing module is, so a
     * disabled format lends nothing.
     *
     * <p>The sanctioned lookup for a neutral consumer - the mark phase of a garbage collector - that must find these
     * without carrying its own {@code uses} clause. Empty when no installed format serves from a blobs namespace,
     * which is the core's own shape: only the collector's caller-supplied {@code publish/} roots are then scanned.
     */
    static List<BlobReferences> installed() {
        List<BlobReferences> lenders = new ArrayList<>();
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format instanceof BlobReferences lender
                    && Features.active(format.name(), format.requiredConfig())) {
                lenders.add(lender);
            }
        }
        return List.copyOf(lenders);
    }
}
