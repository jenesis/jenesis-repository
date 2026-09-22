package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.Checksums;

/**
 * A content-addressed view over an {@link ArtifactStore}, so a format stores bytes the way Maven and OCI do:
 * {@link #write} stores the content once under {@code blobs/<sha256>} and writes the key as a small pointer to that
 * hash, and {@link #read} resolves the pointer back to its blob. Identical bytes - the same wheel published twice, a
 * tarball that matches an OCI layer - therefore dedupe to a single object and share the {@code blobs/} namespace
 * with every other layout, since the hash is the lower-case SHA-256 hex the others already use. The pointers stay at
 * the format's own keys, so a format's layout (and its {@link #list enumeration}) is unchanged; only the bytes move.
 */
public final class Blobs {

    private final ArtifactStore store;
    private final ServableNames servableNames;

    public Blobs(ArtifactStore store) {
        this.store = store;
        this.servableNames = new ServableNames(store);
    }

    public void write(String key, byte[] content) throws IOException {
        write(key, new ByteArrayInputStream(content));
    }

    /**
     * Reject a pointer key that would escape its intended namespace before any blob is stored or any pointer is
     * written - the single generic guard every {@code blobs}-namespace format inherits, so a format that forwards a
     * user-supplied coordinate (an npm version, a PyPI filename, a Debian architecture) into a key can never smuggle
     * a {@code ..} traversal, an absolute path, a backslash, or a control character past the choke point, regardless
     * of whether its own front-door validation caught it. A key is a {@code /}-separated sequence of segments; each
     * segment must be non-empty and must not be {@code .} or {@code ..}, and no segment may contain a backslash or a
     * control character. This mirrors the segment rule the store and the per-format guards apply, applied
     * once here so path injection cannot slip through a format that forgot to check.
     */
    private static void requireSafeKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("blank blob key");
        }
        int start = 0;
        while (start <= key.length()) {
            int slash = key.indexOf('/', start);
            int end = slash < 0 ? key.length() : slash;
            String segment = key.substring(start, end);
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("unsafe blob key: " + key);
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character == '\\' || character < 0x20) {
                    throw new IllegalArgumentException("unsafe blob key: " + key);
                }
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
    }

    /** Stream the content once, content-addressed while it is read, and point {@code key} at it - the streaming
     *  counterpart of {@link #write(String, byte[])} for a large artifact that must not be buffered whole. The pointer
     *  is load-bearing (it is what serves the content), so a compare-and-set conflict re-reads the token and retries
     *  rather than silently dropping the losing write: a concurrent republish of the same key resolves to
     *  last-writer-wins, and a caller whose pointer cannot land is told so instead of believing it published. */
    public void write(String key, InputStream content) throws IOException {
        requireSafeKey(key);
        Stored stored = stored(content);
        link(key, stored.hash(), stored.size());
    }

    /** A blob {@link #stored} content-addressed: its hash and its length, for a {@link #link} that records both. */
    public record Stored(String hash, long size) {
    }

    /** {@link #store} answering the length beside the hash, counted as the bytes stream in - so a pointer linked
     *  from it records the length without a stat. */
    public Stored stored(InputStream content) throws IOException {
        long[] counted = {0L};
        String hash = store.writeBlob(new FilterInputStream(content) {
            @Override
            public int read() throws IOException {
                int one = super.read();
                if (one >= 0) {
                    counted[0]++;
                }
                return one;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0) {
                    counted[0] += read;
                }
                return read;
            }
        });
        return new Stored(hash, counted[0]);
    }

    /** Stream the content into the content-addressed store and return its lower-case SHA-256 hex, without pointing any
     *  key at it yet - the primitive a bundled upload (a {@code .gem}, a {@code .nupkg}) streams through when it must
     *  spool the whole artifact to storage first and only then reopen its front to read the metadata that names the
     *  pointer. The caller {@link #open reopens} the blob by the returned hash to parse it, then {@link #link}s the
     *  pointer. The returned hash is exactly the artifact's SHA-256, so a caller that needs that checksum (a compact
     *  index line, a {@code #sha256=} fragment) reuses it rather than hashing the blob a second time. */
    public String store(InputStream content) throws IOException {
        return store.writeBlob(content);
    }

    /** Open the blob with this SHA-256 hex for reading - reopen the just-stored artifact to parse its front rather than
     *  buffering it from the network. The symmetric counterpart of {@link #store}; the caller closes the stream. */
    public InputStream open(String hash) throws IOException {
        return store.open("blobs/" + hash);
    }

    /**
     * Stream {@code content} into the content-addressed store while digesting it under {@code algorithm}, and point
     * {@code key} at it only when the computed digest equals {@code expected} - the point-integrity twin of the Maven
     * proxy leg's checksum check (which streams a proxied artifact under a digest, compares it to the ecosystem's
     * published checksum, and refuses the artifact on a mismatch). This is the one place a {@code blobs}-namespace
     * caching proxy leg verifies a proxied artifact against the checksum its ecosystem declares (an npm
     * {@code dist.integrity}, a Cargo {@code cksum}, a Conda {@code sha256}, ...) before it becomes servable, so a
     * corrupted or tampered upstream body is caught here rather than cached and served as authentic.
     *
     * <p>On a <b>match</b> the pointer is {@linkplain #link written} exactly as {@link #write} would, so the artifact
     * is a local hit on the next read. On a <b>mismatch</b> the pointer is <b>not</b> written: nothing in the serving
     * layout resolves to the just-streamed bytes (they sit unreferenced in the CAS for a later sweep, never linked into
     * a format key), and {@code false} is returned so the caller surfaces a clear negative instead of serving
     * unverified-mismatched bytes. The body is digested as it streams - a {@link DigestInputStream} over the same read
     * that stores it, never buffered whole - so an artifact of unbounded size is verified without landing in heap.
     *
     * @param algorithm a {@link MessageDigest} algorithm name matching {@code expected} (e.g. {@code SHA-256},
     *                  {@code SHA-512}, {@code SHA-1}, {@code MD5})
     * @param expected  the raw (decoded) digest bytes the artifact must hash to
     * @return {@code true} when the streamed body matched {@code expected} and the pointer was linked; {@code false} on
     *         a mismatch, with no pointer written
     */
    public boolean writeVerified(String key, InputStream content, String algorithm, byte[] expected)
            throws IOException {
        requireSafeKey(key);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("unsupported digest algorithm: " + algorithm, e);
        }
        String hash = store(new DigestInputStream(content, digest));
        if (!MessageDigest.isEqual(digest.digest(), expected)) {
            return false;
        }
        link(key, hash);
        return true;
    }

    /** Point {@code key} at an already-stored blob hash with the same load-bearing compare-and-set retry {@link #write}
     *  uses, so a concurrent republish resolves last-writer-wins and a pointer that cannot land is reported rather than
     *  silently dropped. Once the pointer lands, any garbage collector's {@code gc/condemned/<hash>} marker on the
     *  blob is cleared - the same guard the {@code Publication.link} gives its {@code publish/} namespace:
     *  identical content dedupes to one blob, so a "new" publish through a blobs-namespace format may link a blob a
     *  collector already judged unreferenced, and clearing the marker on the write path un-condemns it before the
     *  collecting sweep's final marker re-read. One existence probe per link, a no-op wherever collection never
     *  condemned the blob; the marker key is the store-layout convention the {@code gc} SPI documents. */
    public void link(String key, String hash) throws IOException {
        link(key, hash, -1L);
    }

    /**
     * {@link #link(String, String)} with the blob's stored length in hand, so the pointer records it without the stat
     * the two-argument form pays to learn it - the same split the {@code Publication.link} makes. The length is
     * what {@link #locate} answers a serve from, so a caller that has it (the pipeline's serving step, a
     * {@link #stored} body) passes it, and one that has not lets this method read it once at the moment the pointer
     * is written, against the stat every download would otherwise pay.
     */
    public void link(String key, String hash, long size) throws IOException {
        requireSafeKey(key);
        long length = size < 0 ? store.size("blobs/" + hash) : size;
        byte[] body = ServableNames.Pointer.render(hash, length);
        Retries.update(store, key, _ -> body);
        String condemned = "gc/condemned/" + hash;
        if (store.exists(condemned)) {
            store.delete(condemned);
        }
    }

    /**
     * Record a small note at {@code key} - a reverse-index entry, a marker - as a plain versioned write that stores no
     * blob: the value is the note itself, last writer wins, and a lost race is a peer writing the same note, which
     * the re-read finds and leaves. A note's body is never a content hash, so a collector reading pointers skips it.
     * A note that loses every retry to a peer writing a <em>different</em> value throws, as every other pointer
     * write here does: this loop used to fall out silently, so a reverse-index entry could go missing with nothing
     * said, against the stance the rest of the class takes.
     */
    public void note(String key, String value) throws IOException {
        requireSafeKey(key);
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        Retries.update(store, key, current ->
                current.isPresent() && Arrays.equals(current.get().content(), content) ? null : content);
    }

    /** Produces the value a key is established with, once, on the path that first needs it. */
    @FunctionalInterface
    public interface Fresh {

        byte[] get() throws IOException;
    }

    /**
     * Establish a value at {@code key} <b>exactly once</b> and return what is stored there afterwards - this call's
     * content if it won, and the winner's if it lost.
     *
     * <p><b>Why this exists, and what it is not.</b> {@link #write} is last-writer-wins, which is right for content
     * a peer would write identically. It is wrong for a value that is <em>generated</em>, because two generators
     * produce two different values and the loser's is not a duplicate of the winner's - it is a second answer to a
     * question that must have one. A signing key is the case that bites: every caller that generated one goes on to
     * use the value it generated, so a lost race means signing with a key nobody can verify against.
     *
     * <p><b>Measured, not theorised.</b> Four formats provisioned a key exactly that way - {@code if absent,
     * generate, write the secret, write the public} - and a full lane caught apk's: two first publishes interleaved
     * so the stored public half came from one pair and the private half from the other, and the index this
     * repository signed no longer verified against the key it served. It passed every time the module ran alone.
     *
     * <p>The loser's generated value becomes an unreferenced blob, which is what a collector is for. Paying that on
     * a genuine race is the cheap side of the trade; the expensive side is two keys.
     */
    public byte[] establish(String key, Fresh fresh) throws IOException {
        requireSafeKey(key);
        for (int attempt = 0; attempt < Retries.COMPARE_AND_SET; attempt++) {
            ByteArrayOutputStream established = new ByteArrayOutputStream();
            if (read(key, established)) {
                return established.toByteArray();
            }
            byte[] candidate = fresh.get();
            String hash = store(new ByteArrayInputStream(candidate));
            // A null token is "I expect nothing here", so exactly one caller can ever win. A loser does not retry
            // its own value - the next turn of the loop reads the winner's and returns that.
            if (store.writeVersioned(key, ServableNames.Pointer.render(hash, candidate.length), null)) {
                return candidate;
            }
            Retries.backoff(attempt);
        }
        throw new IOException("could not establish " + key + " after repeated version conflicts");
    }

    /** The lower-case SHA-256 hex the pointer at {@code key} resolves to - the content address the blob is stored
     *  under - so a caller that needs the digest reads it straight off the small pointer instead of re-reading and
     *  re-hashing the whole blob. Empty when nothing is published there. */
    public Optional<String> hash(String key) throws IOException {
        Optional<String> content = contentHash(key);
        if (content.isPresent()) {
            return content;
        }
        return store.readVersioned(key)
                .map(pointer -> ServableNames.hash(pointer.content()));
    }

    /**
     * The hash a key names when it is the content pool's own key for a blob rather than a pointer to one:
     * {@code blobs/<hex>}, and empty for every other key. A format that serves by digest - OCI, whose manifests and
     * layers are pulled straight out of the pool and whose only pointers are its tags - answers a request path with
     * that key from {@link BlobLayout#servingKey}, and the readers here take it for what it is: the hash is in the
     * key, the bytes are the key's own, and the hold is the hash's marker. Every other key is a pointer whose body
     * names the hash, which is what a plain {@code store.open} of it would miss in the other direction.
     */
    static Optional<String> contentHash(String key) {
        if (!key.startsWith("blobs/")) {
            return Optional.empty();
        }
        String hex = key.substring("blobs/".length());
        return Checksums.isSha256Hex(hex) ? Optional.of(hex) : Optional.empty();
    }

    /** The stored byte length of the blob the pointer at {@code key} resolves to, or {@code -1} if nothing is
     *  published there, the blob is gone, or the blob is {@linkplain build.jenesis.repository.store.Withheld withheld} - so a serve sets its
     *  {@code Content-Length} and streams the blob rather than buffering it whole to learn its length, and a HEAD
     *  answers exactly what a GET would for a held version (absent). */
    /** A servable artifact located for one download: the pointer resolved, the withheld marker probed and the blob
     *  sized - once - so the stream that follows opens the blob and reads nothing else. */
    public record Located(String hash, long size) {
    }

    /**
     * Locate the artifact at {@code key} for a download: empty when nothing is published there or the blob is
     * {@linkplain build.jenesis.repository.store.Withheld withheld}. Two point reads - the pointer, the marker - with
     * the blob's length read off the pointer where {@link #link} recorded it ({@code -1} for a pointer written before
     * it was, which is served without a length until the reconcile pass regenerates it), and the {@link #serve} that
     * follows opens the blob for its bytes: that open is what proves the blob present, so a pointer whose blob is
     * gone answers a clean 404 there rather than a truncated 200 here. Measured 2026-09-12: the stat this used to
     * make was the fifth of a download's five reads on every backing.
     */
    public Optional<Located> locate(String key) throws IOException {
        Optional<String> content = contentHash(key);
        if (content.isPresent()) {
            if (servableNames.withheldHash(content.get()) || !store.exists(key)) {
                return Optional.empty();
            }
            return Optional.of(new Located(content.get(), store.size(key)));
        }
        // A key this node recently read and found absent is absent still, from the store's memory of misses - the
        // same memory the serve probe consults, so a format's own pointer namespace pays the pointer read once
        // per ttl for a name that is not there, and a link through the store forgets the key at once.
        Optional<MissMemory> memory = NodeMemoStore.misses(store);
        if (memory.isPresent() && memory.get().remembered(store, key)) {
            return Optional.empty();
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
        if (pointer.isEmpty()) {
            memory.ifPresent(remembering -> remembering.remember(store, key));
            return Optional.empty();
        }
        ServableNames.Pointer parsed = ServableNames.parse(pointer.get().content());
        if (servableNames.withheldHash(parsed.hash())) {
            return Optional.empty();
        }
        return Optional.of(new Located(parsed.hash(), parsed.size()));
    }

    /** Stream a {@link #locate located} artifact's bytes into a stream the caller already holds - a buffer a format
     *  parses, never a response: a response is {@link #serve served}, so that the blob is opened before it is
     *  committed to. */
    public void stream(Located located, OutputStream out) throws IOException {
        store.read("blobs/" + located.hash(), out);
    }

    /**
     * Serve a {@link #locate located} artifact as a {@code 200} with its recorded length and its bytes - the one blob
     * read of a download - or a clean {@code 404} where the blob turns out to be gone. The blob is opened
     * <em>before</em> the response is committed, so the open is the existence check: the store's typed
     * {@link NoSuchFileException} for an absent key is the one way a pointer's blob can be missing (a torn write the
     * reconcile has not reached, the collector's two-pass grace mid-way), and it answers a {@code 404} with no body
     * rather than headers followed by nothing. Every blobs-namespace format streams its artifact through here, so the
     * order is decided once.
     */
    public void serve(Located located, FormatExchange exchange) throws IOException {
        InputStream in;
        try {
            in = store.open("blobs/" + located.hash());
        } catch (NoSuchFileException gone) {
            exchange.respond(404);
            return;
        }
        try (in; OutputStream out = exchange.respond(200, located.size())) {
            in.transferTo(out);
        }
    }

    /** The blob's recorded length for the pointer at {@code key}, or {@code -1} if nothing is published there, the
     *  blob is {@linkplain build.jenesis.repository.store.Withheld withheld}, or the pointer predates the length -
     *  read off the pointer, never off the blob, like {@link #locate}. */
    public long size(String key) throws IOException {
        return locate(key).map(Located::size).orElse(-1L);
    }

    /** Resolve the pointer at {@code key} to its blob and stream it to {@code out}; false if nothing is published
     *  there or the blob is {@linkplain build.jenesis.repository.store.Withheld withheld} - the blobs-namespace twin of the {@code publish/}
     *  namespace's withheld screen, so a retroactive compliance hold retracts serving here exactly as it does for a
     *  Maven path. This is the one choke point every blobs-namespace format streams through, so the check covers all
     *  of them at once. */
    public boolean read(String key, OutputStream out) throws IOException {
        Optional<String> content = contentHash(key);
        if (content.isPresent()) {
            if (!store.exists(key) || servableNames.withheldHash(content.get())) {
                return false;
            }
            store.read(key, out);
            return true;
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
        if (pointer.isEmpty()) {
            return false;
        }
        String hash = ServableNames.hash(pointer.get().content());
        if (!store.exists("blobs/" + hash) || servableNames.withheldHash(hash)) {
            return false;
        }
        store.read("blobs/" + hash, out);
        return true;
    }

    public boolean exists(String key) throws IOException {
        return store.readVersioned(key).isPresent();
    }

    /** Whether the pointer at {@code key} resolves to a blob that is {@linkplain build.jenesis.repository.store.Withheld withheld} - the
     *  enumeration-side twin of the {@link #read}/{@link #size} withheld screen. A blobs-namespace format screens a
     *  held version out of its index / listing / registration surface with this the same way {@link #read} retracts
     *  its download, so a client cannot even learn a quarantined coordinate exists (an existence-disclosure the
     *  serving-path screen alone left open). It is exactly the check the serve makes - the pointer resolved to its
     *  content hash, then the {@code withheld/<hash>} marker on that hash - not a re-implementation, so a listing and a
     *  download agree on what is held. One existence probe per listed entry (the OCI catalog/tags screen's shape);
     *  {@code false} when nothing is published at {@code key}, since an absent pointer lists nothing to screen.
     *
     *  <p>Routed through the servable-name seam's {@link ServableNames#disclosableKey} under
     *  {@link ServableNames.Policy#HIDE_WITHHELD}, which wraps the WHOLE pointer -&gt; hash -&gt; marker probe
     *  fail-closed: a hostile pointer key (or a stored pointer whose target carries a NUL / encoding-hostile char, so
     *  {@code FilesystemArtifactStore.resolve} throws {@link java.nio.file.InvalidPathException}) is treated as
     *  undisclosable - i.e. withheld - rather than propagating an uncaught {@code RuntimeException} that would 500 every
     *  later packument GET. The former hand-rolled {@code hash(key)} did an UNWRAPPED {@code store.readVersioned(key)}
     *  ahead of the fail-closed {@code withheldHash}, losing the seam's fail-closed guarantee at this choke point.
     *  {@code disclosableKey} returns whether the key is disclosable; withheld is its negation, preserving this method's
     *  contract for every normal case (absent pointer / servable hash =&gt; not withheld; withheld hash =&gt; withheld). */
    public boolean withheld(String key) throws IOException {
        Optional<String> content = contentHash(key);
        if (content.isPresent()) {
            return servableNames.withheldHash(content.get());
        }
        return !servableNames.disclosableKey(key, ServableNames.Policy.HIDE_WITHHELD);
    }

    /** The immediate child names under a pointer prefix; the pointers live at the format's keys, so this is its
     *  layout. A prefix nothing could be stored under lists nothing without asking the store - see
     *  {@link #nameable(String)}. */
    public List<String> list(String prefix) {
        return nameable(prefix) ? store.list(prefix) : List.of();
    }

    /** Whether nothing is stored under {@code prefix} - one child answers it, where {@code list(..).isEmpty()}
     *  would materialise the whole container to say the same thing. See {@link ArtifactStore#isEmpty}. */
    public boolean isEmpty(String prefix) throws IOException {
        return !nameable(prefix) || store.isEmpty(prefix);
    }

    /**
     * Whether anything could be stored under this prefix at all: a prefix already past the store's own
     * {@link ArtifactStore#MAX_KEY_BYTES key-byte cap} can hold no child, because every child key is longer than the
     * prefix it sits under and {@link ArtifactStore#key} refuses to write a key that long. Request paths are shaped by
     * clients, so a format that composes a store prefix from one can be handed a prefix of any length.
     *
     * <p>This is a derived fact, not a swallowed failure, and the distinction is the whole reason it is written here
     * rather than as a {@code catch}. Asking the backend about an unnameable prefix gets a different answer from each
     * one: an object store pages nothing, while a filesystem raises {@code ENAMETOOLONG} - which the default store
     * used to answer as an empty listing along with every other {@code IOException}, and which since 0.13.0
     * it correctly raises, because "I could not look" is not "there is nothing here". Neither answer belongs at a
     * request seam: the correct one is knowable without the round trip, so it is taken before it.
     *
     * <p>Deliberately only the byte cap, and not {@link ArtifactStore#key}'s full screen. The segment and
     * traversal-freedom clauses are about what a <em>format</em> may compose and are answered by each format's own
     * request screen (a {@code .}/{@code ..} path is that format's {@code 404}); folding them in here would turn those
     * refusals into exceptions out of a listing.
     */
    public static boolean nameable(String prefix) {
        return prefix.getBytes(StandardCharsets.UTF_8).length <= ArtifactStore.MAX_KEY_BYTES;
    }

    /**
     * The tenant-scoped {@link ArtifactStore} this view stores its blobs and pointers through - handed out so a caller
     * can drive a <em>shared</em> store primitive (the bounded and screened enumerations, which take an
     * {@code ArtifactStore}) over a format's own pointer namespace.
     *
     * <p>This accessor exists precisely so no second copy of those primitives, and no {@code Blobs}-shaped overload of
     * them, ever gets written: a format that must page or screen a container reaches the one implementation in the
     * walk module through here, rather than a {@code Blobs}-flavoured re-spelling growing beside it. It is the store
     * this view was constructed with and nothing more - every write path still goes through this class's own methods,
     * which is where the key guard, the CAS retry and the withheld screen live.
     */
    public ArtifactStore store() {
        return store;
    }

    /**
     * The one servable-name screen this view already composes - the same instance {@link #withheld}, {@link #size} and
     * {@link #read} make their disclosure decision through, so an enumeration screened through it and a download served
     * through this class can never disagree on what is held.
     *
     * <p>Handed out for the same reason as {@link #store()}: the screened enumeration needs the seam and the
     * store together, and it must receive <em>this</em> seam rather than construct a second one, which would rediscover
     * its own interceptor chain and could then answer differently from the serve path.
     */
    public ServableNames servableNames() {
        return servableNames;
    }

    /** One bounded, ordered, seek-resume page of the immediate child names under a pointer prefix - the paged form of
     *  {@link #list(String)} a format uses to walk a very large id space (a flat container's package ids) without
     *  materialising and sorting the whole set in heap. Names arrive in lexicographic order; {@code after} is the last
     *  name of the previous page ({@code ""} for the first), and a page shorter than {@code limit} is the last. */
    public void page(String prefix, String after, int limit, java.util.function.Consumer<String> names) {
        store.page(prefix, after, limit, names);
    }

    public void delete(String key) throws IOException {
        requireSafeKey(key);
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
    }
}
