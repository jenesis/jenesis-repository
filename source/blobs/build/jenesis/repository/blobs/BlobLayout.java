package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

/**
 * The retroactive-enforcement capability a {@code RepositoryFormat} implements when it stores its artifacts as
 * {@link Blobs} pointers under its own key roots ({@code npm/}, {@code pypi/}, {@code cargo/}, ...) rather than through
 * {@code Publication} (whose pointers live under {@code publish/}, the namespace Maven and the raw layout use).
 * Detected by {@code instanceof}, like {@code ArtifactLayout}, so a format opts in without a core edit.
 *
 * <p>Extends {@link BlobRoots} (the reference-scan seam garbage collection needs) with the full coordinate-to-pointer
 * mapping enforcement needs, and declares that mapping with NO defaults: a format that serves from the blobs namespace
 * must map its coordinates to their pointers, or it cannot compile. There is no empty-default posture in which a format
 * serves a coordinate whose hold cannot retract it - that state (which shipped unenforceable retroactive holds in five
 * formats) is now undeclarable. A format that genuinely wires no enforcement implements only {@code BlobRoots}, a
 * reviewed decision rather than a silent omission.
 *
 * <p>The mapping seams:
 * <ul>
 *   <li>{@link #blobKeys} maps one coordinate version to the specific pointer keys an eviction deletes, so retention
 *       reclaims a blobs-namespace release: the pointers go, then the next garbage collection reclaims the
 *       now-unreferenced blob. This is what {@code ArtifactLayout.paths(coordinate, version, store)} is for a
 *       {@code publish/}-namespace layout - the reverse mapping from a coordinate version to the pointers an eviction
 *       removes.</li>
 *   <li>{@link #describe} and {@link #servedPaths} let a retroactive hold enumerate and retract a whole
 *       blobs-namespace release from serving.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@code RepositoryFormat} - the blobs-namespace twin of {@code ArtifactLayout} -
 * so that contract still binds and the clauses below state what mapping a coordinate onto content-addressed
 * pointer keys adds. The format testkit's {@code FormatContract} proves the serve-side clauses per format and
 * {@code BlobLayoutCoordinateSeamTest} proves clauses 3 and 5 over every discovered layout.
 * <ol>
 * <li><b>Thread-safety.</b> Every method is a stateless read on the format singleton, called concurrently from request
 *     threads and from the retroactive-enforcement sweeps; an implementation keeps no per-call state on itself.</li>
 * <li><b>Absence sentinel.</b> {@link #describe} answers {@link Optional#empty()} for a path that names no versioned
 *     artifact (an index, a packument, a metadata read) and {@link #blobKeys} / {@link #blobHashes} /
 *     {@link #servedPaths} answer an <em>empty list</em> for a coordinate version that maps to no live pointer;
 *     {@code null} is never returned and no input is refused with an exception.</li>
 * <li><b>Traversal refusal.</b> A coordinate and a version are as client-supplied as a request path - they arrive from
 *     a published name, an advisory feed, a console form or a stored {@code published/} sidecar - so a coordinate or
 *     version part that is not a single addressable path segment ({@link #addressable}) maps <em>nowhere</em>: every
 *     method here answers empty rather than composing a pointer key carrying a {@code .} or {@code ..} segment. This
 *     is not cosmetic: {@link #blobKeys} is what an eviction <em>deletes</em>, and {@code ArtifactStore.delete} is not
 *     screened - only writes are - so a traversal-shaped coordinate that composed a key here would aim that delete at
 *     a neighbouring key space. It is deliberately the same rule the {@code ArtifactLayout.addressable} states
 *     for the {@code publish/}-namespace layouts, applied per {@code /}-separated part so a legitimately multi-segment
 *     coordinate (an npm {@code @scope/name}, a Go module path, an RPM {@code <repo>/<name>}) still resolves.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #describe} derives from the request path <em>alone</em> - no store read, no
 *     blob opened - so a serving read path may call it freely. {@link #blobKeys} / {@link #servedPaths} read the store,
 *     but only its small pointers ({@code readVersioned}, {@code list}, {@code page}); no artifact body is ever opened
 *     to answer them, and neither method writes anything.</li>
 * <li><b>Round-trip fidelity.</b> {@link #describe} and {@link #servedPaths} are inverses over live content: the
 *     coordinate and version {@code describe} reports for a served path must be the pair {@code servedPaths} maps back
 *     to that same path, and {@link #blobKeys} must report the pointer keys carrying <em>that</em> version's bytes and
 *     no sibling version's. A layout whose two directions disagree makes a retroactive hold mark the wrong bytes (or
 *     none), which is a hold that silently does nothing.</li>
 * <li><b>Bounded work (&sect;7).</b> Every enumeration behind these methods is paged and bounded - a pool or registry
 *     tree is descended iteratively through {@code ArtifactStore.page}, never self-recursion over an unpaged
 *     {@code list()} - and a bound that binds raises a named failure rather than returning a short list: a pointer key
 *     these methods omit is a held version that keeps serving.</li>
 * <li><b>Error visibility (&sect;9).</b> A store failure while resolving a coordinate propagates. Answering an empty
 *     list because a probe failed would report "this version has no live pointer", which a hold reads as "nothing to
 *     retract" - the one wrong answer these methods must never invent.</li>
 * <li><b>Reference agreement (&sect;13).</b> Every hash {@link #blobHashes} reports for a live version must also be
 *     reachable to the collector's reference scan while that version is live - either as the bare-hex body of one of
 *     the version's pointer keys, which the scan reads for itself, or lent back by
 *     {@link build.jenesis.repository.format.BlobReferences#references} for a key beneath one of the
 *     {@link #blobRoots() declared roots}. The default {@code blobHashes} satisfies this by construction (it reports
 *     exactly the bare-hex pointer bodies the scan already counts) and only an override can break it. A hash on one
 *     side and not the other is a defect in whichever direction it falls: a hash the hold knows and the scan does not
 *     is a blob that gets deleted under a held artifact, and a hash the scan knows and the hold does not is a blob
 *     that keeps serving through a hold reporting itself enforced. The two derivations deliberately fail in opposite
 *     directions - a hold that cannot enumerate degrades and under-enforces, a scan that cannot enumerate throws
 *     rather than under-report to a deleter (that seam's clause 3) - so an override that derives its own set proves
 *     the agreement in a test over its own stored shapes rather than by inspection.</li>
 * </ol>
 */
public interface BlobLayout extends BlobRoots {

    /**
     * Whether a coordinate and a version may be composed into this layout's pointer keys - the screen every method
     * here applies before it builds a key, so a hostile or malformed coordinate maps to nothing instead of to a
     * traversal-shaped key an eviction would then delete under (clause 3).
     *
     * <p>It is the {@link ArtifactLayout#addressable} rule, applied to each {@code /}-separated part of
     * the coordinate rather than to the coordinate as a whole: a blobs-namespace coordinate is legitimately
     * multi-segment for several formats (npm's {@code @scope/name}, a Go module path, RPM's {@code <repo>/<name>}),
     * so screening the whole string would refuse every scoped package while screening nothing extra. Stated once here
     * for all fourteen layouts rather than re-derived per format, exactly as {@code ArtifactLayout.addressable} is
     * stated once for the {@code publish/}-namespace ones (&sect;13). The rule carries the control-character
     * screen the request seam already applies, so the two seams refuse the same shapes.
     */
    static boolean addressable(String coordinate, String version) {
        if (coordinate == null || coordinate.isEmpty()) {
            return false;
        }
        for (String part : coordinate.split("/", -1)) {
            if (!addressablePart(part)) {
                return false;
            }
        }
        return addressablePart(version);
    }

    /** One name part: a single addressable path segment by the shared rule, which is the whole rule. It used to add
     *  {@code && !Keys.unsafe(part)} because the shared screen said nothing about control characters and this one
     *  did; the shared screen carries them now, so the second half was the same question asked twice - and
     *  keeping it would have hidden the gap in the shared screen rather than closed it, which is exactly what it had been
     *  doing. */
    private static boolean addressablePart(String part) {
        return ArtifactLayout.addressable(part);
    }

    /** The {@link Blobs} pointer keys this format holds for one coordinate version - the pointers an eviction deletes
     *  to release the version's content blob to garbage collection. Resolved against {@code store} so a format can
     *  discover a version's stored keys (whose {@code <repo>} registry segment or uploaded filename is not derivable
     *  from the coordinate alone) by listing. Returns only the named version's keys, never a sibling version's; empty
     *  when the coordinate version maps to no live pointer. */
    List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException;

    /** The content hashes this format's blobs-namespace pointers for one coordinate version resolve to - the set a
     *  retroactive withhold marks under the {@code withheld/<hash>} convention, and the set a name-enumeration screen
     *  probes to hide a held version. The default resolves {@link #blobKeys} and keeps only the pointer bodies that are a
     *  bare lower-case 64-hex SHA-256 (a format's small timestamp/revision marker under the same root never counts as a
     *  hash) - byte-for-byte the resolution the store-backed inventory made inline before this seam. A format whose
     *  content digests are NOT reachable as bare-hex pointer bodies overrides this to derive its own set: OCI's tag
     *  pointer body is {@code sha256:<hex>} (not bare hex) and its config/layer digests live INSIDE the manifest JSON
     *  behind no pointer key at all, so without an override a held OCI image would never be marked or screened. */
    default List<String> blobHashes(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> hashes = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isPresent()) {
                String named = ServableNames.hash(pointer.get().content());
                if (Checksums.isSha256Hex(named)) {
                    hashes.add(named);
                }
            }
        }
        return hashes;
    }

    /** The format-neutral coordinate a request path carries, from the path alone - the blobs-namespace twin of
     *  {@code ArtifactLayout.describe}, and what lets the inventory write the {@code published/} sidecar
     *  for a blobs-namespace publish so the retroactive enforcement sweeps enumerate the version (before this, a
     *  KEV-listed npm or PyPI package admitted before its CVE landed was invisible to every sweep while the "held"
     *  gauge reported the repository clean). Empty for a path that names no versioned artifact - an index, a
     *  packument, a metadata read. */
    /**
     * The store key that SERVES {@code requestPath}, or empty when this layout stores nothing there.
     *
     * <p>The inverse of {@link #servedPaths}, and the direction a reader needs rather than a sweeper. A request
     * path otherwise resolves through the generic {@code publish/<path>} pointer, which is the whole story for a
     * format that publishes through it - and no story at all for one that keeps its own key space, which is every
     * format implementing this interface. Before this, anything asking "what is stored at this path" outside the
     * format itself got {@link Optional#empty()} for npm, PyPI, Go, Conan and Hugging Face alike, however plainly
     * the artifact was being served.
     *
     * <p>The caller that needed it first is the compliance screen's SIBLING read: an inspector holding one file of a
     * publish asks for the document beside it that declares the licence - a Hugging Face model card, and the same
     * shape wherever a format's declaration lives in a neighbour rather than in the artifact. That read resolves the
     * way SERVING does now, through the format that owns the layout, instead of through one pointer namespace.
     *
     * <p>Derives from the path and the store's own pointers only - no blob opened, no walk - so a read path may call
     * it freely. A path this layout does not recognise, or one whose pointer is absent, answers empty rather than
     * throwing: not recognising a path is an answer, and the caller has its own fall-back.
     *
     * <p>The key is a pointer whose body names the blob for every format that keeps pointers, and the blob's own
     * {@code blobs/<hex>} key for one that serves by digest - OCI's manifests and layers - which {@code Blobs} reads
     * as content rather than as a pointer, so a reader needs no second code path for the difference.
     */
    default Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        return Optional.empty();
    }

    /**
     * Which coordinate version a stored pointer under this layout's roots belongs to, or empty when the key is not a
     * per-version serving pointer - a shared index, a dist-tag, a checksum, or another format's key entirely.
     *
     * <p><strong>The one direction the layout did not have, and the repair that needs it.</strong> Everything else
     * here maps a coordinate <em>forward</em> - {@link #blobKeys}, {@link #blobHashes}, {@link #servedPaths} - and
     * {@link #servingKey} maps a request path to a store key. Nothing mapped a stored key back to the coordinate it
     * serves, and the {@code by/} indexes are per-format answers to other questions rather than a general one.
     *
     * <p>Without it a blobs-namespace release whose {@code published/} row is lost is never rebuilt. The reconcile's
     * forward-repair leg walks {@code publish/}, so it covers the Publication-namespace layouts only; a blobs-namespace
     * format keeps its pointers under its own roots, which that walk never visits. The row is lost by an ordinary
     * window rather than by an upgrade: the accept path records it <em>after</em> the artifact is committed, so
     * anything that stops the process in between leaves a pointer nobody can name a coordinate for. The artifact
     * keeps serving; what stops is a retroactive sweep seeing it, so it can carry a later-listed CVE while the held
     * gauge reads clean.
     *
     * <p><b>Answering empty is not a failure, it is "no repair for this format".</b> That is why the default is empty
     * rather than an exception: a layout that has not implemented this is exactly as repairable as it was before,
     * and a layout that implements it wrongly would write a row naming the wrong coordinate - which retention then
     * ages by. So a layout answers only for keys it is certain of, and the burden of proof is on the implementor.
     *
     * <p>Derives from the key alone. No store read, no walk: the caller is a walk consumer that already holds the
     * key, and a per-key read would make the repair cost a round trip per pointer.
     */
    default Optional<ArtifactDescriptor> describePointer(String key) {
        return Optional.empty();
    }

    Optional<ArtifactDescriptor> describe(String path);

    /**
     * The coordinate version a served request path names, asked of the format that claims it through whichever layout
     * role it has - an {@link ArtifactLayout}'s or a blobs-namespace one's - and store-free either way. Empty for a
     * format with neither, or a path that names no version.
     *
     * <p>One answer for every caller on the serving edge. The download recorders asked the {@code ArtifactLayout}
     * alone, so every blobs-namespace format - npm, PyPI, NuGet and the rest - recorded no download at all, and a
     * not-downloaded-for retention evicted the versions its clients were downloading.
     */
    static Optional<ArtifactDescriptor> served(RepositoryFormat format, String path) {
        Optional<ArtifactDescriptor> described = format instanceof ArtifactLayout layout ? layout.describe(path)
                : format instanceof BlobLayout layout ? layout.describe(path)
                : Optional.empty();
        return described.filter(artifact -> artifact.coordinate() != null && artifact.version() != null);
    }

    /**
     * {@link #served(RepositoryFormat, String)}, falling back - when the claiming format has no layout of its own -
     * to a capability-only blobs-namespace layout among {@code installed} that describes the path in its own
     * ecosystem. That is the OCI shape: the registry format serves the path, and a separate inventory layout of the
     * same ecosystem is what names the image and tag, exactly as the inventory's own publish recording resolves it.
     */
    static Optional<ArtifactDescriptor> served(RepositoryFormat claiming, String path,
                                               List<RepositoryFormat> installed) {
        Optional<ArtifactDescriptor> described = served(claiming, path);
        if (described.isPresent()) {
            return described;
        }
        for (RepositoryFormat format : installed) {
            if (format != claiming && format instanceof BlobLayout layout) {
                Optional<ArtifactDescriptor> fallback = layout.describe(path)
                        .filter(artifact -> layout.ecosystem().equals(artifact.ecosystem()))
                        .filter(artifact -> artifact.coordinate() != null && artifact.version() != null);
                if (fallback.isPresent()) {
                    return fallback;
                }
            }
        }
        return Optional.empty();
    }

    /** The served request paths one coordinate version currently occupies in this format's blobs namespace - the
     *  inverse of {@link #describe}, so a retroactive hold can retract a whole blobs-namespace release from serving
     *  (a {@code /quarantine<servedPath>} review handle per path) exactly as {@code ArtifactLayout.paths} does for a
     *  {@code publish/}-namespace layout. Derived from {@link #blobKeys} (the version's stored pointer keys) mapped back
     *  to the download request path each key answers - e.g. npm's {@code npm/<coord>/tarballs/<file>} pointer serves at
     *  {@code /npm/<coord>/-/<file>}. Reads only the tiny pointers, never a blob body; empty for a version with no live
     *  served artifact. */
    List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException;

    /**
     * The served request paths a coordinate version <b>would</b> occupy, derived from the coordinate alone with no
     * store read - the blobs-namespace twin of the store-free {@code ArtifactLayout.paths(coordinate,
     * version)} overload, and empty by default because most blobs-namespace paths are not derivable from a coordinate
     * (an npm tarball's filename, a PyPI distribution's build tags and a Debian architecture are recorded, not
     * computed).
     *
     * <p><b>What it is for, and why "would" is the point</b>. A format that screens at its own choke point
     * commits under whatever descriptor it can build <em>before</em> the body is stored, and for a coordinate that
     * lives inside the artifact that descriptor is the push ENDPOINT - one path every push of that format shares. The
     * gate then keys the hold's audit row and its held-subject record on that endpoint while the format, which can
     * read the coordinate once the bytes are down, re-keys the {@code /quarantine} review handle onto the package.
     * A reviewer's handle and their reasons then name different paths for the same hold. The gate cannot ask
     * {@link #servedPaths(String, String, ArtifactStore)} at that moment, because it runs <em>before</em> the layout
     * and the pointer it probes for does not exist yet - the question is not "where is this version served" but
     * "where is the version this upload is about going to be served".
     *
     * <p>A format that overrides this is stating that its served path is a pure function of the coordinate, and it
     * owes the two forms agreement: for a version that IS live, this must be exactly what the store-backed overload
     * enumerates. An override that drifted would key a hold's records at a path nothing serves, which is worse than
     * the endpoint they used to sit at. {@code FormatHoldContractTest} holds every layout to that agreement, so the
     * cheapest safe answer - not overriding at all - stays the default and costs a format at most a hold recorded under
     * its push endpoint rather than under the package.
     */
    default List<String> servedPaths(String coordinate, String version) {
        return List.of();
    }
}
