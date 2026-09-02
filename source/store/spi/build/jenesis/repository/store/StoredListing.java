package build.jenesis.repository.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * A listing a format serves as stored bytes and maintains on its write path: a Debian {@code Packages} file, a conda
 * {@code repodata.json}, an npm packument, a Go {@code @v/list}, an OCI tag list. A repository answers many more reads
 * than writes, so the read of a listing is one stored document streamed to the client, and every byte of it was
 * written when the publication that changed it landed - a read never enumerates, screens or re-renders.
 *
 * <h2>Shape</h2>
 *
 * A listing is a sorted set of <em>entries</em>, each an id and a fragment of bytes - a stanza, a JSON member, a line -
 * and the document is their {@link Codec#join}. The codec also {@link Codec#split splits} a stored document back into its
 * entries, so a write is one read of the document, one change of the affected entries, one compare-and-set write: the
 * cost of a write is one rewrite of the listings the artifact belongs to, whatever else the repository holds. That is
 * the ceiling, and it is the same ceiling a client pays to download the listing once.
 *
 * <p>The document is stored under {@link #ROOT} as a short header - a sequence, the body's length and digests - a blank
 * line, and the body. The header is what a derived document (a {@code .gz} twin, a {@code Release} or {@code repomd.xml}
 * that names the body's digest) is built from without reading the body again, and what {@link #derive} orders derived
 * writes by, so a slow writer can never put an older derived document over a newer one.
 *
 * <h2>Absent documents</h2>
 *
 * A listing that was never materialised - a repository that predates this document, or one {@link #forget forgotten}
 * for repair - is generated from the store by the format's {@link Generator} on first use, by the reader or the writer
 * that finds it absent, and stored with an atomic create. Generation is the format's former on-read enumeration, so an
 * existing repository serves exactly what it served before, once; every read after that is the stored document, and
 * every write after that is incremental. The daily rebuild pass regenerates listings the same way, so a change a
 * writer could not apply (see below) is repaired without anyone noticing more than one slow read.
 *
 * <h2>Contention</h2>
 *
 * Concurrent publishes to one listing are coalesced per node: the first writer to reach a document applies its own
 * change and every change queued behind it in one read-modify-write, and the queued writers wait for that write and
 * return with it. Across nodes the compare-and-set decides; a writer that loses retries with a fresh read, and one that
 * cannot land its change within {@value #ATTEMPTS} attempts regenerates the document in place, and
 * reports {@code false} rather than failing the publish whose pointer already landed.
 *
 * <p>Every method is thread-safe; the lanes are keyed by the store's {@link ArtifactStore#identity identity} and the
 * document key, so two scopes with same-named listings never share a queue.
 */
public final class StoredListing {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredListing.class);

    /** The store root every listing document lives under; the key below it is the format's own, served-path-like key. */
    public static final String ROOT = "listing/";

    /** The compare-and-set attempts a writer makes before it {@link #rebuild regenerates} the document. */
    public static final int ATTEMPTS = Retries.COMPARE_AND_SET;

    private static final String MAGIC = "jenesis-listing/1";

    private static final ConcurrentMap<LaneKey, Lane> LANES = new ConcurrentHashMap<>();

    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder CONFLICTS = new LongAdder();
    private static final LongAdder COALESCED = new LongAdder();
    private static final LongAdder MATERIALISED = new LongAdder();
    private static final LongAdder FORGOTTEN = new LongAdder();

    private StoredListing() {
    }

    /** The store key of a listing: {@link #ROOT} plus the format's key, validated as a storable key. */
    public static String key(String listing) {
        return ArtifactStore.key(ROOT + listing);
    }

    /** How a format splits its document into entries and joins them back - a bijection, so a round trip is exact. */
    public interface Codec {

        /** The entries of a document, by id. */
        SortedMap<String, byte[]> split(byte[] document);

        /** The document of these entries. */
        byte[] join(SortedMap<String, byte[]> entries);

        /**
         * A writer that encodes entries into {@code out} as they arrive, in ascending id order.
         *
         * <p>The counterpart of {@link #join}: same document, without holding the entries that produced it. The
         * default buffers into a map and joins at close, which is correct for any codec that has not implemented
         * streaming and no worse than what it did before - so a codec is never wrong for lacking this, only slower.
         */
        default Appender append(OutputStream out) {
            SortedMap<String, byte[]> entries = new TreeMap<>();
            return new Appender() {
                @Override
                public void append(String id, byte[] entry) {
                    entries.put(id, entry);
                }

                @Override
                public void close() throws IOException {
                    out.write(join(entries));
                }
            };
        }

        /** Encodes a document one entry at a time. Entries arrive in ascending id order; the document is complete
         *  once it is closed. */
        interface Appender extends Closeable {

            void append(String id, byte[] entry) throws IOException;
        }

        /**
         * Read the entries of a stored document of {@code length} bytes out of {@code in}, one at a time.
         *
         * <p>The counterpart of {@link #split}: the same entries, in the same order, without the document. The
         * default reads the stream whole and splits it - exactly what the caller did before this existed - so a
         * codec that has not implemented streaming is never wrong for it, only as heavy as it always was.
         *
         * <p>{@code length} is the document's stored size. A framed codec needs it to know where its footer
         * begins, since a stream cannot be read from the end; a codec that does not need it ignores it.
         */
        default Reader read(InputStream in, long length) throws IOException {
            Iterator<Map.Entry<String, byte[]>> entries = split(in.readAllBytes()).entrySet().iterator();
            return new Reader() {
                @Override
                public Optional<Map.Entry<String, byte[]>> next() {
                    return entries.hasNext() ? Optional.of(entries.next()) : Optional.empty();
                }

                @Override
                public void close() {
                }
            };
        }

        /** Decodes a document one entry at a time, in the ascending id order it is stored in. */
        interface Reader extends Closeable {

            /** The next entry, or empty once the document is exhausted. */
            Optional<Map.Entry<String, byte[]>> next() throws IOException;
        }

        /**
         * A codec for a document that is its fragments joined by {@code delimiter} - a Debian {@code Packages} file's
         * stanzas ({@code "\n\n"}), a list's lines ({@code "\n"}) - with {@code idOf} naming each fragment. An empty
         * document has no entries; a trailing delimiter is tolerated on split and written on join, so a file that is
         * conventionally terminated stays terminated.
         */
        static Codec delimited(String delimiter, Function<String, String> idOf) {
            byte[] separator = delimiter.getBytes(StandardCharsets.UTF_8);
            String latin1 = new String(separator, StandardCharsets.ISO_8859_1);
            return new Codec() {
                @Override
                public SortedMap<String, byte[]> split(byte[] document) {
                    // The delimiter is searched on a Latin-1 view of the bytes - one char per byte, so a char
                    // offset is a byte offset and the search is the JDK's vectorised one - and each fragment is cut
                    // out of the document as it is, decoded once for its id and never re-encoded. A multi-megabyte
                    // index is thus not decoded and re-encoded whole on every write.
                    SortedMap<String, byte[]> entries = new TreeMap<>();
                    String view = new String(document, StandardCharsets.ISO_8859_1);
                    int from = 0;
                    while (from < document.length) {
                        int at = view.indexOf(latin1, from);
                        int end = at < 0 ? document.length : at;
                        if (end > from) {
                            byte[] fragment = Arrays.copyOfRange(document, from, end);
                            entries.put(idOf.apply(new String(fragment, StandardCharsets.UTF_8)), fragment);
                        }
                        if (at < 0) {
                            break;
                        }
                        from = at + separator.length;
                    }
                    return entries;
                }

                @Override
                public Appender append(OutputStream out) {
                    // Nothing is retained: the id is what ORDERS the entries and the fragment already carries it,
                    // which is why join() only ever reads values() and this only ever writes them.
                    return new Appender() {
                        @Override
                        public void append(String id, byte[] entry) throws IOException {
                            out.write(entry);
                            out.write(separator);
                        }

                        @Override
                        public void close() {
                        }
                    };
                }

                @Override
                public Reader read(InputStream in, long ignored) {
                    // Searches a filled window for the delimiter rather than testing the stream a byte at a time,
                    // and the difference is not a nicety: the byte-at-a-time form is a virtual call and a bounds
                    // check per byte, and the suite that measures a put against a growing document turned it into
                    // minutes. Only the tail that could still be the start of a delimiter is carried across a
                    // refill, so a delimiter straddling the window boundary is found exactly as split finds it.
                    // Empty fragments are skipped as split skips them, so a conventionally terminated document
                    // yields the same entries either way.
                    return new Reader() {

                        private final byte[] window = new byte[8192];

                        private final ByteArrayOutputStream fragment = new ByteArrayOutputStream();

                        private int position, limit;

                        private boolean drained;

                        @Override
                        public Optional<Map.Entry<String, byte[]>> next() throws IOException {
                            while (!drained) {
                                int found = indexOf(window, position, limit, separator);
                                if (found >= 0) {
                                    fragment.write(window, position, found - position);
                                    position = found + separator.length;
                                    Optional<Map.Entry<String, byte[]>> entry = take();
                                    if (entry.isPresent()) {
                                        return entry;
                                    }
                                    continue;   // an empty fragment between two delimiters, as split drops it
                                }
                                // Nothing complete in the window: keep back only what could still be a delimiter's
                                // opening bytes, hand the rest to the fragment, and refill behind it.
                                int keep = Math.min(separator.length - 1, limit - position);
                                fragment.write(window, position, limit - position - keep);
                                System.arraycopy(window, limit - keep, window, 0, keep);
                                position = 0;
                                limit = keep;
                                int read = in.read(window, limit, window.length - limit);
                                if (read < 0) {
                                    fragment.write(window, position, limit - position);
                                    drained = true;
                                    return take();
                                }
                                limit += read;
                            }
                            return Optional.empty();
                        }

                        /** The fragment gathered so far, emptied - or nothing, when it is empty. */
                        private Optional<Map.Entry<String, byte[]>> take() {
                            byte[] bytes = fragment.toByteArray();
                            fragment.reset();
                            return bytes.length == 0 ? Optional.empty() : Optional.of(Map.entry(
                                    idOf.apply(new String(bytes, StandardCharsets.UTF_8)), bytes));
                        }

                        @Override
                        public void close() throws IOException {
                            in.close();
                        }
                    };
                }

                @Override
                public byte[] join(SortedMap<String, byte[]> entries) {
                    int length = 0;
                    for (byte[] fragment : entries.values()) {
                        length += fragment.length + separator.length;
                    }
                    byte[] document = new byte[length];
                    int at = 0;
                    for (byte[] fragment : entries.values()) {
                        System.arraycopy(fragment, 0, document, at, fragment.length);
                        at += fragment.length;
                        System.arraycopy(separator, 0, document, at, separator.length);
                        at += separator.length;
                    }
                    return document;
                }
            };
        }
    }

    /**
     * An appender for a document whose opening bytes depend on the entries that follow - an RPM
     * {@code primary.xml} whose root element carries the package count, an index that names its own bounds.
     *
     * <p>Such a document cannot be written in one forward pass, and that is <b>not</b> a reason to hold it. The
     * entries are spooled to a temporary file as they arrive and copied out behind the prologue once the count is
     * known, so heap stays at one entry however wide the document. The alternative is the inherited appender,
     * which collects every entry into a map and calls {@code join} - the whole document in heap, on the publish
     * path, which is what the rest of this class exists to avoid.
     *
     * <p>It is one mechanism rather than one private inner class per codec because the shape recurs: a count in a
     * root element, a summarising header, a pair of sections that split the arrival order. Each of those is the
     * same two-pass write over a spool.
     *
     * <p>The spool is created on the first entry, so a codec's {@code append} may build this without declaring a
     * checked exception, and it is deleted whether the close succeeds or throws.
     */
    public static Codec.Appender spooling(OutputStream out, Written written, Prologue prologue, byte[] epilogue) {
        return new Codec.Appender() {

            private Path spool;

            private OutputStream body;

            private long entries;

            @Override
            public void append(String id, byte[] entry) throws IOException {
                open();
                written.write(body, id, entry);
                entries++;
            }

            @Override
            public void close() throws IOException {
                open();                                 // an empty document still has its prologue and epilogue
                try {
                    body.close();
                    out.write(prologue.of(entries));
                    try (InputStream spooled = new BufferedInputStream(Files.newInputStream(spool))) {
                        spooled.transferTo(out);
                    }
                    out.write(epilogue);
                } finally {
                    Files.deleteIfExists(spool);
                }
            }

            private void open() throws IOException {
                if (spool == null) {
                    spool = Files.createTempFile("jenreg-listing-spool", ".tmp");
                    body = new BufferedOutputStream(Files.newOutputStream(spool));
                }
            }
        };
    }

    /** How one entry is written into the spooled body - the per-entry half of a {@link #spooling} appender. */
    @FunctionalInterface
    public interface Written {

        void write(OutputStream body, String id, byte[] entry) throws IOException;
    }

    /** The document's opening bytes, given how many entries turned out to follow them. */
    @FunctionalInterface
    public interface Prologue {

        byte[] of(long entries) throws IOException;
    }

    /**
     * A codec for a document that wraps another codec's body in a fixed header and footer - an HTML page around its
     * links, an XML root around its elements. The header and footer are stripped before the inner split and written
     * around the inner join; a document without them is split as a bare body.
     */
    public static Codec framed(String header, String footer, Codec inner) {
        return new Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                String text = new String(document, StandardCharsets.UTF_8);
                if (text.startsWith(header)) {
                    text = text.substring(header.length());
                }
                if (text.endsWith(footer)) {
                    text = text.substring(0, text.length() - footer.length());
                }
                return inner.split(text.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Reader read(InputStream in, long length) throws IOException {
                // A stream cannot be read from the end, so the footer's position is derived from the stored
                // length rather than found. The header is checked and consumed if present and pushed back if
                // not - a document stored without the frame is read as a bare body, exactly as split reads one -
                // and the frame is written as a pair, so a document carrying the header carries the footer.
                byte[] head = header.getBytes(StandardCharsets.UTF_8);
                byte[] tail = footer.getBytes(StandardCharsets.UTF_8);
                PushbackInputStream source = new PushbackInputStream(in, Math.max(1, head.length));
                byte[] first = source.readNBytes(head.length);
                long body = length;
                if (Arrays.equals(first, head)) {
                    body = Math.max(0L, length - head.length - tail.length);
                } else {
                    source.unread(first);
                }
                return inner.read(limited(source, body), body);
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(inner.join(entries));
                out.writeBytes(footer.getBytes(StandardCharsets.UTF_8));
                return out.toByteArray();
            }

            @Override
            public Appender append(OutputStream out) {
                Appender delegate;
                try {
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    delegate = inner.append(out);
                } catch (IOException cause) {
                    throw new UncheckedIOException(cause);
                }
                return new Appender() {
                    @Override
                    public void append(String id, byte[] entry) throws IOException {
                        delegate.append(id, entry);
                    }

                    @Override
                    public void close() throws IOException {
                        delegate.close();
                        out.write(footer.getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        };
    }

    /**
     * The format's enumeration of a listing from the store - the first-materialisation and repair path.
     *
     * <h2>Why this emits rather than returns</h2>
     *
     * <p>A generator for a repository-wide listing - a catalogue, a Simple index, a compact-index {@code versions}
     * file - enumerates every package in the repository. Returning that as a {@link SortedMap} means the map, its
     * keys, a {@code byte[]} per entry and (for the generators that read each package's own document on the way)
     * every one of those documents are all live at the same moment, and only then is anything written. That peak is
     * proportional to the repository, and it is reached on two paths a deployment cannot avoid: the daily
     * {@code listing-rebuild} pass, and the first read of a listing that does not exist yet, which materialises
     * inline.
     *
     * <p>Emitting into a {@link Sink} lets the writer encode and release each entry as it arrives, so the peak is
     * the encoded document rather than the document plus the whole map that produced it.
     *
     * <h2>Order is the generator's obligation</h2>
     *
     * <p><b>Entries must be emitted in ascending id order.</b> A {@link SortedMap} used to supply that for free and
     * now nothing does. The store's paged faces are the way to keep it: {@link ArtifactStore#page} takes a cursor,
     * and a cursor is only meaningful over a total order, so paging a prefix yields its children in order without
     * holding them - which is precisely what {@link ArtifactStore#list} cannot offer, since it sorts by
     * materialising everything.
     */
    @FunctionalInterface
    public interface Generator {

        /** Emit every entry of the listing, in ascending id order. */
        void generate(Sink sink) throws IOException;

        /**
         * Adapts a generator that builds the whole listing before returning it.
         *
         * <p>This is the shape being migrated away from, and it is a named adapter rather than an overload of
         * {@code generate} so that every site still paying the full peak says so in its own source. A per-package
         * listing is legitimately this shape - its map is one package's versions - and is not debt; a
         * repository-wide one is.
         */
        static Generator materialising(Materialising generator) {
            return sink -> {
                for (Map.Entry<String, byte[]> entry : generator.generate().entrySet()) {
                    sink.accept(entry.getKey(), entry.getValue());
                }
            };
        }

        /** A generator that returns its listing whole. */
        @FunctionalInterface
        interface Materialising {

            SortedMap<String, byte[]> generate() throws IOException;
        }

        /** Where a generator's entries go. */
        @FunctionalInterface
        interface Sink {

            void accept(String id, byte[] entry) throws IOException;
        }
    }

    /** What a writer does once its change has landed - writing the document's derived twins, usually. */
    @FunctionalInterface
    public interface Derivation {

        /**
         * No twins to write.
         *
         * <p>A named constant rather than an anonymous lambda because the writer asks whether it is this one: a
         * document written from a temporary file need never be read back into heap when nothing is going to look
         * at its bytes, and identity is the only honest way to know that.
         */
        Derivation NONE = ignored -> { };

        void after(Derived document) throws IOException;
    }

    /**
     * One listing as a format defines it: its key under {@link #ROOT}, how its document splits and joins, how it is
     * generated from the store when absent, and what is derived from it after every write (including the first
     * materialisation). A format keeps one of these per listing kind and hands it to every read and write, so a read
     * and a write can never disagree on the document's shape.
     */
    public record Spec(String listing, Codec codec, Generator generator, Derivation derivation, boolean md5) {

        public Spec {
            Objects.requireNonNull(listing, "listing");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(generator, "generator");
            Objects.requireNonNull(derivation, "derivation");
        }

        public static Spec of(String listing, Codec codec, Generator generator) {
            return new Spec(listing, codec, generator, Derivation.NONE, false);
        }

        /**
         * The same, for a generator that builds its listing whole before anything is written.
         *
         * <p><b>Deliberately a different name rather than an overload.</b> An overload is ambiguous for a method
         * reference - {@code TreeMap::new} is inexact, so javac cannot choose between the two shapes and rejects
         * the call before it looks at what the reference returns. A distinct name also puts the cost in the
         * source: a per-package listing is legitimately this shape, since its map is one package's versions, while
         * a repository-wide one paying it is the thing to convert.
         */
        public static Spec materialising(String listing, Codec codec, Generator.Materialising generator) {
            return of(listing, codec, Generator.materialising(generator));
        }

        public Spec deriving(Derivation derivation) {
            return new Spec(listing, codec, generator, derivation, md5);
        }

        /** Also record the body's MD5 in the header - for a format whose own documents publish it (a Debian
         *  {@code Release}, the RubyGems compact index); every other listing pays for the SHA-256 alone. */
        public Spec withMd5() {
            return new Spec(listing, codec, generator, derivation, true);
        }

        String key() {
            return StoredListing.key(listing);
        }
    }

    /** The stored header: the document's sequence (monotone per document), its body's length, its SHA-256 (the
     *  validator every read serves) and, for a listing that {@linkplain Spec#withMd5 asks for it}, its MD5 - empty
     *  otherwise. */
    /**
     * @param entries how many entries the document holds, or {@link #UNKNOWN} when nothing counted them.
     *                <p>It is recorded because emptiness is a question the read path has to answer and could not:
     *                a format whose listing is <em>present with zero entries</em> is a repository that holds nothing,
     *                which several clients must be told about, and the alternative was splitting the document to
     *                count it - materialising exactly the thing that is allowed to be large (&sect;1). A derived twin
     *                carries {@link #UNKNOWN}: it is computed from bytes alone, and inventing a count for it would be
     *                worse than admitting there is none.
     */
    public record Header(long seq, long size, String md5, String sha256, long entries) {

        /** No count was recorded - a derived twin, or a document written before the count existed. */
        public static final long UNKNOWN = -1L;

        /** The header a body is stored with at this sequence, SHA-256 only - what a derivation computes for a twin. */
        public static Header of(long seq, byte[] body) {
            return of(seq, body, false);
        }

        /** The header a body is stored with at this sequence, with its MD5 when {@code md5} asks for it. */
        public static Header of(long seq, byte[] body, boolean md5) {
            return of(seq, body, md5, UNKNOWN);
        }

        /** The same, carrying the entry count the writer counted while it joined the document. */
        public static Header of(long seq, byte[] body, boolean md5, long entries) {
            return new Header(seq, body.length, md5 ? digest("MD5", body) : "", digest("SHA-256", body), entries);
        }

        /** The same, for a body that was rendered somewhere other than heap and digested as it went. */
        /**
         * The header of a body that was never held: the caller digested it as it wrote it.
         *
         * <p>The other factories take the bytes and digest them, which is right for a document small enough to
         * have in hand and wrong for a twin of one that is not - a compressed package index is produced by a
         * stream through a compressor into a file, and its digests fall out of that pass. Asking for them again
         * would mean reading it back, which is the hold the streaming {@link #derive} exists to avoid.
         */
        public static Header of(long seq, long size, String md5, String sha256, long entries) {
            return new Header(seq, size, md5, sha256, entries);
        }

        /** The entry count when one was recorded, empty when it was not - so a caller has to decide what an unknown
         *  count means for it rather than reading {@code -1} as a number of entries. */
        public OptionalLong count() {
            return entries == UNKNOWN ? OptionalLong.empty() : OptionalLong.of(entries);
        }

        private static String digest(String algorithm, byte[] body) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(body));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The document a {@link Derivation} was handed: its header, and its body to read.
     *
     * <h2>Contract</h2>
     *
     * <ol>
     *   <li><b>Lifetime.</b> The body is readable only for the duration of {@link Derivation#after}. The write
     *       that produced it renders to a temporary file and deletes that file when it returns, so a derivation
     *       that hands the document to another thread - {@link #later} - must take its own copy <em>inside</em>
     *       {@code after} before it queues. Reading it afterwards is reading a deleted file.</li>
     *   <li><b>Re-openable.</b> {@link #open()} may be called more than once within that window; each call
     *       returns a fresh stream positioned at the first byte.</li>
     *   <li><b>Bounded reads only.</b> {@link #body()} materialises, and is the right answer for a twin computed
     *       from a document bounded by something other than the repository - one coordinate's versions, a
     *       manifest of one line per index. A twin of a repository-wide document (a compressed package index, a
     *       whole-subdir repodata) must use {@link #open()}: holding it is the peak this class exists to avoid.</li>
     * </ol>
     */
    public interface Derived {

        Header header();

        /** A fresh stream over the body, positioned at its first byte. */
        InputStream open() throws IOException;

        /** The body, materialised - only for a document clause 3 allows to be held. */
        default byte[] body() throws IOException {
            try (InputStream body = open()) {
                return body.readAllBytes();
            }
        }
    }

    /** A stored document, whole. */
    public record Document(Header header, byte[] body) implements Derived {

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(body);
        }
    }

    /** A stored document opened for streaming: the header, and the body positioned at its first byte. */
    public static final class Served implements Closeable {

        private final Header header;
        private final InputStream body;

        Served(Header header, InputStream body) {
            this.header = header;
            this.body = body instanceof BufferedInputStream ? body : new BufferedInputStream(body, 1 << 16);
        }

        public Header header() {
            return header;
        }

        public InputStream body() {
            return body;
        }

        public void copyTo(OutputStream out) throws IOException {
            body.transferTo(out);
        }

        /** The whole body - for a response handed over as one array, the shape a content-derived validator and a
         *  {@code 304} need at the edge. */
        public byte[] bytes() throws IOException {
            return body.readAllBytes();
        }

        /** The whole body with every occurrence of {@code token} replaced by {@code replacement}. */
        public byte[] bytes(String token, String replacement) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, header.size() + 64));
            copyTo(out, token, replacement);
            return out.toByteArray();
        }

        /**
         * Stream the body with every occurrence of {@code token} replaced by {@code replacement} - for a document
         * that names the host it is served from (a download URL in a package index), stored with a placeholder
         * and completed on the way out. A byte transform over one stream, no store access; the length of the output
         * is not known in advance, so a caller sends it without a {@code Content-Length}.
         */
        public void copyTo(OutputStream out, String token, String replacement) throws IOException {
            byte[] needle = token.getBytes(StandardCharsets.UTF_8);
            byte[] substitute = replacement.getBytes(StandardCharsets.UTF_8);
            if (needle.length == 0) {
                body.transferTo(out);
                return;
            }
            int matched = 0;
            int b;
            while ((b = body.read()) >= 0) {
                matched = feed(out, needle, substitute, matched, (byte) b);
            }
            if (matched > 0) {
                out.write(needle, 0, matched);
            }
        }

        private static int feed(OutputStream out, byte[] needle, byte[] substitute, int matched, byte value)
                throws IOException {
            if (value == needle[matched]) {
                matched++;
                if (matched == needle.length) {
                    out.write(substitute);
                    return 0;
                }
                return matched;
            }
            if (matched > 0) {
                out.write(needle, 0, 1);
                byte[] rest = Arrays.copyOfRange(needle, 1, matched);
                matched = 0;
                for (byte again : rest) {
                    matched = feed(out, needle, substitute, matched, again);
                }
                return feed(out, needle, substitute, matched, value);
            }
            out.write(value);
            return 0;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    // ---- reading ----

    /**
     * Open the listing for streaming, materialising it first when it is absent. Empty only when the generator itself
     * answers no entries and the listing is still absent after the create - which the caller reads as "nothing to
     * list", as it would an empty document.
     */
    /**
     * The stored document alone, opened for streaming, without generating one that is absent.
     *
     * <p>The counterpart of {@link #open} for a reader that must not walk. A repository-wide listing's generator
     * enumerates the whole store, and &sect;10 puts that off the request path - so a read that would otherwise
     * materialise answers "not built yet" instead and leaves the work to the rebuild pass. Absent here means
     * absent, never "about to be expensive".
     */
    public static Optional<Served> served(ArtifactStore store, String listing) throws IOException {
        return openStored(store, key(listing));
    }

    public static Optional<Served> open(ArtifactStore store, Spec spec) throws IOException {
        String key = spec.key();
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Served> served = openStored(store, key);
            if (served.isPresent()) {
                return served;
            }
            materialiseOnce(store, spec, key);
        }
        return Optional.empty();
    }

    /**
     * Materialise the document, or wait for the materialisation another caller is already running.
     *
     * <p>A listing is absent exactly when nobody has read or written it yet - which is when a burst of readers is
     * most likely, because whatever made the repository interesting just happened. Ten of them arriving together
     * used to run ten generations of the same document: each probed, found nothing, and walked the store. One
     * write won the compare-and-set and the other nine were thrown away, having cost the same as the winner. On a
     * document that takes tens of seconds to generate, that is the difference between one slow request and ten.
     *
     * <p>So the first caller builds and the rest wait on it. They are not given the result - they return to the
     * loop above and probe the store again, which is what they would have done anyway and keeps this function's
     * only job the suppression of duplicate work. A build that fails is rethrown to the caller that ran it and
     * swallowed by the waiters, whose next probe finds nothing and lets one of them try again.
     */
    private static void materialiseOnce(ArtifactStore store, Spec spec, String key) throws IOException {
        LaneKey lane = new LaneKey(store.identity(), key);
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> running = MATERIALISING.putIfAbsent(lane, mine);
        if (running == null) {
            try {
                materialise(store, spec);
                mine.complete(null);
            } catch (IOException | RuntimeException failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                MATERIALISING.remove(lane, mine);
            }
            return;
        }
        try {
            running.join();
        } catch (CancellationException | CompletionException failedForSomeoneElse) {
            // Their build, their exception. This caller re-probes and may become the one that tries next.
        }
    }

    /** One materialisation per absent document, so a burst of first readers does the work once. */
    private static final ConcurrentMap<LaneKey, CompletableFuture<Void>> MATERIALISING = new ConcurrentHashMap<>();

    /** Open a stored document as a stream - through the store's stream face, or, for a backend that answers a
     *  versioned write only through its versioned read, from the whole versioned read. */
    private static Optional<Served> openStored(ArtifactStore store, String key) throws IOException {
        if (store.exists(key)) {
            InputStream in;
            try {
                in = store.open(key);
            } catch (NoSuchFileException gone) {
                in = null;   // dropped between the probe and the open: read as absent below
            }
            if (in != null) {
                try {
                    return Optional.of(new Served(header(in, key), in));
                } catch (IOException notStreamed) {
                    in.close();
                } catch (RuntimeException e) {
                    in.close();
                    throw e;
                }
            }
        }
        Optional<ArtifactStore.Versioned> stored = readStored(store, key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Document document = parse(stored.get().content(), key);
        return Optional.of(new Served(document.header(), new ByteArrayInputStream(document.body())));
    }

    /** Read the listing whole, materialising it first when it is absent. */
    public static Optional<Document> read(ArtifactStore store, Spec spec) throws IOException {
        String key = spec.key();
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ArtifactStore.Versioned> stored = readStored(store, key);
            if (stored.isPresent()) {
                return Optional.of(parse(stored.get().content(), key));
            }
            materialise(store, spec);
        }
        return Optional.empty();
    }

    /** The stored header alone, or empty when the listing is absent; materialises nothing. */
    public static Optional<Header> header(ArtifactStore store, String listing) throws IOException {
        Optional<Served> served = openStored(store, key(listing));
        if (served.isEmpty()) {
            return Optional.empty();
        }
        try (Served document = served.get()) {
            return Optional.of(document.header());
        }
    }

    /** Open a derived document ({@link #derive}) for streaming; empty when it was never derived. */
    public static Optional<Served> openDerived(ArtifactStore store, String derived) throws IOException {
        return openStored(store, key(derived));
    }

    /** Whether the listing is materialised. */
    public static boolean present(ArtifactStore store, String listing) throws IOException {
        return store.exists(key(listing)) || store.readVersioned(key(listing)).isPresent();
    }

    // ---- writing ----

    /**
     * Apply {@code changes} to the listing - an id to a fragment replaces or adds that entry, an id to an empty value
     * removes it - and run {@code derivation} once they have landed. Coalesced per node and compare-and-set across
     * nodes as described above; materialises the listing through {@code generator} first when it is absent. Answers
     * {@code false} when the change could not land within {@value #ATTEMPTS} attempts and the document was regenerated
     * in place instead.
     */
    public static boolean update(ArtifactStore store, Spec spec, Map<String, Optional<byte[]>> changes)
            throws IOException {
        return update(store, spec, new Changes(changes));
    }

    /** {@link #update} with a change set, which may also remove every entry under an id prefix. */
    public static boolean update(ArtifactStore store, Spec spec, Changes changes) throws IOException {
        LaneKey laneKey = new LaneKey(store.identity(), spec.key());
        Lane lane = LANES.computeIfAbsent(laneKey, ignored -> new Lane());
        Pending mine = new Pending(changes.asMap(), Set.copyOf(changes.prefixes), new CompletableFuture<>());
        boolean runner;
        synchronized (lane) {
            if (lane.running && lane.runner == Thread.currentThread()) {
                // A generator or derivation updating the listing it belongs to would wait for itself: refuse rather
                // than deadlock. A derivation that needs another listing updates THAT listing, never its own.
                throw new IllegalStateException("re-entrant update of listing " + spec.key());
            }
            lane.queue.add(mine);
            runner = !lane.running;
            if (runner) {
                lane.running = true;
                lane.runner = Thread.currentThread();
            }
        }
        if (runner) {
            run(laneKey, lane, store, spec);
        }
        return await(mine.outcome());
    }

    /** {@link #update} with one entry put. */
    public static boolean put(ArtifactStore store, Spec spec, String id, byte[] fragment) throws IOException {
        return update(store, spec, Map.of(id, Optional.of(fragment)));
    }

    /** {@link #update} with one entry removed. */
    public static boolean remove(ArtifactStore store, Spec spec, String id) throws IOException {
        return update(store, spec, Map.of(id, Optional.empty()));
    }

    /** A change set for {@link #update}: puts and removals collected before one write. */
    public static final class Changes {

        private final Map<String, Optional<byte[]>> changes = new LinkedHashMap<>();
        private final Set<String> prefixes = new LinkedHashSet<>();

        public Changes() {
        }

        Changes(Map<String, Optional<byte[]>> changes) {
            this.changes.putAll(changes);
        }

        /** Remove every entry whose id starts with {@code prefix} - applied before the puts, so a put under the
         *  prefix survives. */
        public Changes removePrefix(String prefix) {
            prefixes.add(prefix);
            return this;
        }

        public Changes put(String id, byte[] fragment) {
            changes.put(id, Optional.of(fragment));
            return this;
        }

        public Changes remove(String id) {
            changes.put(id, Optional.empty());
            return this;
        }

        public boolean isEmpty() {
            return changes.isEmpty() && prefixes.isEmpty();
        }

        public Map<String, Optional<byte[]>> asMap() {
            return Map.copyOf(changes);
        }
    }

    /**
     * Write a derived document - a compressed twin, a signed release file - as of the sequence of the listing it was
     * built from, unless a newer one is already stored. Returns whether this body was written.
     */
    public static boolean derive(ArtifactStore store, String derived, long seq, byte[] body) throws IOException {
        return derive(store, derived, Header.of(seq, body), body);
    }

    /** {@link #derive(ArtifactStore, String, long, byte[])} with the header the caller already computed for the
     *  body - a caller that publishes the twin's digests computes them once, here, and not again. */
    public static boolean derive(ArtifactStore store, String derived, Header header, byte[] body) throws IOException {
        return derive(store, derived, header, body.length, () -> new ByteArrayInputStream(body));
    }

    /**
     * The same, over a body too large to hold - a compressed twin of a listing sized by the repository.
     *
     * <p>{@code body} is opened once per attempt, so it must be re-openable: a temporary file, not a stream
     * already consumed. The header is the caller's, computed as the bytes were produced, because a twin's digests
     * are what the manifest naming it carries and computing them twice means holding it twice.
     */
    public static boolean derive(ArtifactStore store, String derived, Header header, long size, Body body)
            throws IOException {
        long seq = header.seq();
        String key = key(derived);
        byte[] head = head(header);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            // The token and the stored SEQUENCE, not the stored document. This used to read the existing twin
            // whole to compare one number in its header - so writing a compressed index re-read the previous
            // compressed index in full, every time, on a document sized by the repository.
            Object token = store.version(key).orElse(null);
            if (token != null) {
                Optional<Served> current = openStored(store, key);
                if (current.isPresent()) {
                    long stored;
                    try (Served document = current.get()) {
                        stored = document.header().seq();
                    }
                    if (stored >= seq) {
                        return false;
                    }
                }
            }
            try (InputStream content = body.open();
                 InputStream framed = new SequenceInputStream(new ByteArrayInputStream(head), content)) {
                if (store.writeVersioned(key, framed, head.length + size, token)) {
                    return true;
                }
            }
            CONFLICTS.increment();
            Retries.backoff(attempt);
        }
        return false;
    }

    /** A re-openable body - a derived twin is written under compare-and-set, so a lost attempt reads it again. */
    @FunctionalInterface
    public interface Body {

        InputStream open() throws IOException;
    }

    /**
     * Run a costly derivation off the write's own thread - a compression that takes longer than the write it
     * follows, as bzip2 of a large document does - on the node's single derivation thread, coalesced per derived key:
     * a derivation queued while an older one for the same key still waits replaces it, since {@link #derive} keeps
     * the newest sequence anyway. The twin lags its source by the time the thread takes to reach it; a read of it
     * meanwhile serves the previous one. The work itself runs {@code derivation} and is expected to call
     * {@link #derive}.
     */
    /**
     * Deferred work that holds something until it runs - and must be told when it will not.
     *
     * <p>Named for the mechanism rather than the timing, because {@code Deferred} beside it is the container's
     * shutdown handle and means something else entirely.
     *
     * <p>{@link #later} coalesces per derived key: a derivation queued while an older one for the same twin still
     * waits <em>replaces</em> it, and the replaced one is dropped without running. A derivation that only reads
     * the store loses nothing by that. One that copied the document first - which a deferred derivation must,
     * since the body it was handed is readable only for the length of the call that queued it - loses the copy,
     * and the file stays on disk forever.
     */
    public interface Coalesced extends Runnable {

        /** Called instead of {@link #run()} when a newer derivation of the same twin has replaced this one. */
        void superseded();
    }

    public static void later(String derived, Runnable derivation) {
        Runnable superseded;
        synchronized (LATER) {
            superseded = LATER.put(derived, derivation);
        }
        // A replaced derivation never runs, so anything it was holding on the queue's behalf is released here.
        // This is not housekeeping: a deferred derivation may hold a COPY of the document, because the body it was
        // handed is only readable for the length of the call that queued it (Derived, clause 1) - so discarding
        // the runnable silently without this leaks that copy, once per coalesced write. Conda's twin does exactly
        // that, and four such files were found on a machine after an evening of test runs.
        if (superseded instanceof Coalesced coalesced) {
            coalesced.superseded();
        }
        LATER_EXECUTOR.execute(() -> {
            Runnable work;
            synchronized (LATER) {
                work = LATER.remove(derived);
            }
            if (work == null) {
                return;   // superseded by a newer derivation of the same twin, which runs in its own turn
            }
            try {
                work.run();
            } catch (RuntimeException e) {
                LOGGER.warn("deferred derivation of {} failed; the next write or rebuild re-derives it", derived, e);
            }
        });
    }

    private static final Map<String, Runnable> LATER = new HashMap<>();

    /**
     * Wait until every derivation queued through {@link #later} before this call has run - a node finishing its
     * derived twins before it stops, or a test finishing them before it tears its store down. Bounded: a derivation
     * still running after thirty seconds is left to the next write or rebuild.
     */
    public static void settle() {
        Future<?> marker = LATER_EXECUTOR.submit(() -> { });
        try {
            marker.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("deferred derivations did not settle; the next write or rebuild re-derives them", e);
        }
    }

    /** The deferred derivations as a closeable for a container's shutdown: closing it {@linkplain #settle settles}
     *  them. */
    public static final class Deferred implements AutoCloseable {

        public Deferred() {
        }

        @Override
        public void close() {
            settle();
        }
    }

    private static final ExecutorService LATER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jenesis-listing-derive");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Regenerate the listing from the store and replace whatever is stored - the rebuild pass's verb. A concurrent
     * incremental writer is not lost: the replace is a compare-and-set against the document the generation started
     * from, so a change that landed meanwhile makes this regenerate again.
     */
    public static Header rebuild(ArtifactStore store, Spec spec) throws IOException {
        String key = spec.key();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<ArtifactStore.Versioned> current = readStored(store, key);
            long seq = current.isPresent() ? parse(current.get().content(), key).header().seq() : 0L;
            // Streamed, like the first materialisation: this is the daily repair pass, and it regenerates a
            // repository-wide listing whole. Rebuilt per attempt rather than hoisted, because a lost
            // compare-and-set below means the store moved and the document has to be produced against it again.
            // Rendered outside heap, exactly as the first materialisation is. This is the DAILY pass's path for
            // every listing in the deployment, so it is the one that meets the largest documents most often - and
            // it was the half left buffering when materialise() was streamed, which is a defect the attribution
            // build found by never finishing: a repository-wide rebuild simply ran out of memory on its own thread,
            // where the only symptom a reader sees is a document that never appears.
            Rendered rendered = render(spec);
            try {
                Header header = Header.of(sequence(seq), rendered.size, rendered.md5, rendered.sha256,
                        rendered.entries);
                if (write(store, key, header, rendered,
                        current.map(ArtifactStore.Versioned::token).orElse(null))) {
                    MATERIALISED.increment();
                    derived(store, spec, header, rendered);
                    return header;
                }
            } finally {
                Files.deleteIfExists(rendered.file);
            }
            CONFLICTS.increment();
            Retries.backoff(attempt);
        }
        throw new IOException("could not rebuild " + key + " after " + ATTEMPTS + " version conflicts");
    }

    /**
     * Regenerate every stored document under a listing prefix ({@code "go/"}, {@code "npm/"}) in place through its
     * rebuilder - the fallback for a transition that cannot be mapped to its entries. Each document is replaced by a
     * compare-and-set, so a reader never finds it missing. Walks the listing namespace only, never the artifacts.
     */
    public static int rebuildUnder(ArtifactStore store, String prefix, Rebuilder rebuilder) throws IOException {
        List<String> keys = new ArrayList<>();
        collect(store, ArtifactStore.key(ROOT + prefix).replaceAll("/+$", ""), keys);
        int rebuilt = 0;
        for (String key : keys) {
            if (rebuilder.rebuild(key.substring(ROOT.length()), store)) {
                rebuilt++;
            }
        }
        return rebuilt;
    }

    /** Drop every stored document under a listing prefix ({@code "go/"}, {@code "npm/"}), so the next readers and
     *  writers regenerate them. Walks the listing namespace only, never the artifacts. A reader racing the drop may
     *  find a document gone and regenerates it; prefer {@link #rebuildUnder} on a live repository. */
    public static int forgetUnder(ArtifactStore store, String prefix) throws IOException {
        List<String> keys = new ArrayList<>();
        collect(store, ArtifactStore.key(ROOT + prefix).replaceAll("/+$", ""), keys);
        for (String key : keys) {
            store.delete(key);
        }
        FORGOTTEN.add(keys.size());
        return keys.size();
    }

    private static void collect(ArtifactStore store, String prefix, List<String> keys) {
        for (String child : store.list(prefix)) {
            String key = prefix + "/" + child;
            if (store.exists(key)) {
                keys.add(key);
            } else {
                collect(store, key, keys);
            }
        }
    }

    /** Drop the stored document, so the next reader or writer regenerates it. */
    public static void forget(ArtifactStore store, String listing) throws IOException {
        String key = key(listing);
        if (store.exists(key)) {
            store.delete(key);
            FORGOTTEN.increment();
        }
    }

    // ---- the lane ----

    private record LaneKey(Object identity, String key) {
    }

    private record Pending(Map<String, Optional<byte[]>> changes, Set<String> prefixes,
                           CompletableFuture<Boolean> outcome) {
    }

    private static final class Lane {

        private final ArrayDeque<Pending> queue = new ArrayDeque<>();
        private boolean running;
        private Thread runner;
    }

    private static void run(LaneKey laneKey, Lane lane, ArtifactStore store, Spec spec) {
        while (true) {
            List<Pending> batch;
            synchronized (lane) {
                batch = List.copyOf(lane.queue);
                lane.queue.clear();
                if (batch.isEmpty()) {
                    lane.running = false;
                    lane.runner = null;
                    // A drained lane leaves the map, so the map holds only the documents being written right now. A
                    // writer that fetched this lane just before the removal runs it on its own - it loses nothing but
                    // the coalescing, since the compare-and-set decides between writers of one document regardless.
                    LANES.remove(laneKey, lane);
                    return;
                }
            }
            if (batch.size() > 1) {
                COALESCED.add(batch.size() - 1);
            }
            try {
                boolean landed = apply(store, spec, batch);
                for (Pending pending : batch) {
                    pending.outcome().complete(landed);
                }
            } catch (Throwable failure) {
                for (Pending pending : batch) {
                    pending.outcome().completeExceptionally(failure);
                }
                if (failure instanceof Error error) {
                    List<Pending> stranded;
                    synchronized (lane) {
                        stranded = List.copyOf(lane.queue);
                        lane.queue.clear();
                        lane.running = false;
                        lane.runner = null;
                        LANES.remove(laneKey, lane);
                    }
                    for (Pending pending : stranded) {
                        pending.outcome().completeExceptionally(failure);
                    }
                    throw error;
                }
            }
        }
    }

    private static boolean apply(ArtifactStore store, Spec spec, List<Pending> batch) throws IOException {
        String key = spec.key();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            // The token is read BEFORE the body, and the order carries the correctness. If the document moves
            // between the two reads, the compare-and-set below is made against the older token, fails, and this
            // attempt retries. Reading the body first would let a newer token authorise a write of a merge
            // computed from older bytes - a lost update rather than a conflict, and a silent one.
            Object token = store.version(key).orElse(null);
            Optional<Served> stored = token == null ? Optional.empty() : openStored(store, key);
            if (stored.isEmpty()) {
                boolean onlyRemovals = batch.stream().allMatch(pending ->
                        pending.changes().values().stream().noneMatch(Optional::isPresent));
                if (onlyRemovals) {
                    return true;   // nothing to take out of a document that does not exist: it is not created for it
                }
                // Generated and changed in ONE write. Materialising first and merging on the next attempt would
                // be tidier to read and wrong to watch: it publishes two documents where a first publish always
                // published one, so every derivation downstream fires twice and the sequence advances twice. So
                // the generator is rendered to a temporary file and the batch merged over that - the same merge
                // the stored path runs, against the document that is about to exist rather than one that does.
                // Holding the generated entries instead would put a first publish into a repository big enough to
                // need this back on the heap the rest of the method exists to stay off.
                Rendered generated = render(spec);
                Rendered created;
                try (InputStream body = new BufferedInputStream(Files.newInputStream(generated.file));
                     Served source = new Served(Header.of(0L, generated.size, generated.md5, generated.sha256,
                             generated.entries), body)) {
                    created = merge(spec, source, batch);
                } finally {
                    Files.deleteIfExists(generated.file);
                }
                try {
                    Header header = Header.of(sequence(0L), created.size, created.md5, created.sha256,
                            created.entries);
                    if (write(store, key, header, created, null)) {
                        UPDATES.increment();
                        MATERIALISED.increment();
                        derived(store, spec, header, created);
                        return true;
                    }
                    CONFLICTS.increment();
                    Retries.backoff(attempt);
                } finally {
                    Files.deleteIfExists(created.file);
                }
                continue;
            }
            Header header;
            Rendered rendered;
            try (Served document = stored.get()) {
                header = document.header();
                rendered = merge(spec, document, batch);
            }
            try {
                if (rendered.sha256.equals(header.sha256())) {
                    return true;   // the changes leave the document as it is: nothing to write, nothing to derive
                }
                Header updated = Header.of(sequence(header.seq()), rendered.size, rendered.md5,
                        rendered.sha256, rendered.entries);
                if (write(store, key, updated, rendered, token)) {
                    UPDATES.increment();
                    derived(store, spec, updated, rendered);
                    return true;
                }
                CONFLICTS.increment();
                Retries.backoff(attempt);
            } finally {
                Files.deleteIfExists(rendered.file);
            }
        }
        LOGGER.warn("listing {} could not be updated after {} version conflicts; regenerating it in place", key,
                ATTEMPTS);
        FORGOTTEN.increment();
        rebuild(store, spec);
        return false;
    }

    /** A derived twin that could not be written leaves the listing itself correct and is re-derived by the next
     *  write or the rebuild pass, so its failure is logged rather than failing the publish that already landed. */
    private static void derive(String key, Derived document, Derivation derivation) {
        try {
            derivation.after(document);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("derived documents of listing {} were not written; the next write or rebuild re-derives them",
                    key, e);
        }
    }

    /** A versioned read that answers absent, rather than failing, when the document vanished between a backend's
     *  existence probe and its read. */
    private static Optional<ArtifactStore.Versioned> readStored(ArtifactStore store, String key) throws IOException {
        try {
            return store.readVersioned(key);
        } catch (NoSuchFileException gone) {
            return Optional.empty();
        }
    }

    private static boolean await(CompletableFuture<Boolean> outcome) throws IOException {
        try {
            return outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for a listing update", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("listing update failed", cause);
        }
    }

    /**
     * First materialisation: render the document to a temporary file and write it conditionally from there.
     *
     * <p>This runs on the first READ of a listing that does not exist yet, so it is a request path, and the
     * document can be proportional to the repository. Nothing of it is held: the generator emits, the codec
     * encodes into the file, the digests are taken as the bytes go past, and the write streams the header and the
     * file into the store's compare-and-set.
     *
     * <p>The one case that still pays is a spec with a {@link Derivation}: a derived twin is written from the
     * document's bytes, so those bytes have to exist. A spec with {@link Derivation#NONE} - which is every spec
     * built by {@link Spec#of} and {@link Spec#materialising} without one - never reads the file back.
     */
    private static void materialise(ArtifactStore store, Spec spec) throws IOException {
        Rendered rendered = render(spec);
        try {
            Header header = Header.of(sequence(0L), rendered.size, rendered.md5, rendered.sha256,
                    rendered.entries);
            if (write(store, spec.key(), header, rendered, null)) {
                MATERIALISED.increment();
                derived(store, spec, header, rendered);
            }
        } finally {
            Files.deleteIfExists(rendered.file);
        }
    }

    /** A document rendered outside heap, with what its header needs already computed. */
    private record Rendered(Path file, long size, String md5, String sha256, long entries) {
    }

    /** Render {@code spec}'s generator into a temporary file, digesting as it goes. */
    private static Rendered render(Spec spec) throws IOException {
        Path file = Files.createTempFile("jenreg-listing", ".tmp");
        Counter counter = new Counter();
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = spec.md5() ? digest("MD5") : null;
        long size;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
             OutputStream digesting = digesting(out, sha256, md5);
             Codec.Appender appender = spec.codec().append(digesting)) {
            spec.generator().generate((id, entry) -> {
                appender.append(id, entry);
                counter.count++;
            });
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            throw failed;
        }
        size = Files.size(file);
        return new Rendered(file, size, md5 == null ? "" : hex(md5.digest()), hex(sha256.digest()), counter.count);
    }

    /**
     * Render the stored document with {@code batch} applied, holding neither.
     *
     * <p>The counterpart of {@link #render}: same output, same temporary file, but the entries come from the
     * stored document rather than from the generator. Both sequences are in ascending id order - the reader
     * because a stored document is, the changes because they are collected into a sorted map - so applying one to
     * the other is a single linear merge, and the update costs a buffer rather than the repository.
     *
     * <p>The batch is replayed rather than unioned, because order is meaningful within it: a prefix removal in one
     * pending change can take out an entry an earlier one added, and a later change can re-add under a prefix an
     * earlier removal cleared. Replaying gives the final state of every id the batch mentions; a stored id the
     * batch never mentions can only be affected by a prefix removal, so those are applied to it directly.
     */
    private static Rendered merge(Spec spec, Served stored, List<Pending> batch) throws IOException {
        SortedMap<String, Optional<byte[]>> resolved = new TreeMap<>();
        List<String> prefixes = new ArrayList<>();
        for (Pending pending : batch) {
            for (String prefix : pending.prefixes()) {
                prefixes.add(prefix);
                resolved.replaceAll((id, value) -> id.startsWith(prefix) ? Optional.empty() : value);
            }
            resolved.putAll(pending.changes());
        }
        Path file = Files.createTempFile("jenreg-listing", ".tmp");
        Counter counter = new Counter();
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = spec.md5() ? digest("MD5") : null;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
             OutputStream digesting = digesting(out, sha256, md5);
             Codec.Appender appender = spec.codec().append(digesting);
             Codec.Reader reader = spec.codec().read(stored.body(), stored.header().size())) {
            Iterator<Map.Entry<String, Optional<byte[]>>> changes = resolved.entrySet().iterator();
            Map.Entry<String, Optional<byte[]>> change = changes.hasNext() ? changes.next() : null;
            Optional<Map.Entry<String, byte[]>> entry = reader.next();
            while (entry.isPresent() || change != null) {
                int order = entry.isEmpty() ? 1
                        : change == null ? -1
                        : entry.get().getKey().compareTo(change.getKey());
                if (order < 0) {
                    // A stored entry the batch never names: it survives unless a prefix removal covers it.
                    String id = entry.get().getKey();
                    if (prefixes.stream().noneMatch(id::startsWith)) {
                        appender.append(id, entry.get().getValue());
                        counter.count++;
                    }
                    entry = reader.next();
                } else {
                    // The batch's own answer for this id, which already accounts for every prefix removal in it.
                    if (change.getValue().isPresent()) {
                        appender.append(change.getKey(), change.getValue().get());
                        counter.count++;
                    }
                    if (order == 0) {
                        entry = reader.next();   // the change replaced or removed the stored entry
                    }
                    change = changes.hasNext() ? changes.next() : null;
                }
            }
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(file);
            throw failed;
        }
        return new Rendered(file, Files.size(file), md5 == null ? "" : hex(md5.digest()), hex(sha256.digest()),
                counter.count);
    }

    /** The conditional write of a rendered document: its header and its bytes, streamed, never joined in heap. */
    private static boolean write(ArtifactStore store, String key, Header header, Rendered rendered, Object expected)
            throws IOException {
        byte[] head = head(header);
        try (InputStream body = Files.newInputStream(rendered.file);
             InputStream framed = new SequenceInputStream(new ByteArrayInputStream(head), body)) {
            return store.writeVersioned(key, framed, head.length + rendered.size, expected);
        }
    }

    /**
     * Hand the document just written to its derivation, as a file rather than as bytes.
     *
     * <p>{@link Derivation#NONE} is still short-circuited, so a spec with no twin touches nothing. What changed is
     * the spec that HAS one: this used to read the rendered file back whole, which put a document sized by the
     * repository into heap immediately after taking the trouble not to. The file is the caller's and is deleted
     * when the write returns - which is clause 1 of {@link Derived}, and why a deferring derivation copies first.
     */
    private static void derived(ArtifactStore store, Spec spec, Header header, Rendered rendered) {
        if (spec.derivation() == Derivation.NONE) {
            return;
        }
        derive(spec.key(), new Derived() {

            @Override
            public Header header() {
                return header;
            }

            @Override
            public InputStream open() throws IOException {
                return new BufferedInputStream(Files.newInputStream(rendered.file));
            }
        }, spec.derivation());
    }

    /** The first offset in {@code buffer[from, to)} where {@code pattern} occurs whole, or {@code -1}. Leftmost,
     *  like the scan {@code split} performs, so a delimiter whose prefix repeats matches at the same place. */
    /**
     * The first occurrence of {@code pattern} in {@code buffer} between {@code from} and {@code to}, or {@code -1}.
     *
     * <p>Public because every codec that reads a listing document in windows needs it - scanning a buffer for the
     * delimiter that ends an entry is what a windowed read <em>is</em> - and it had been written out a second time,
     * identically, in the RPM codec. {@link Arrays#mismatch} does the comparison rather than a nested loop with a
     * labelled continue: it is an intrinsic, so this is faster as well as shorter.
     */
    public static int indexOf(byte[] buffer, int from, int to, byte[] pattern) {
        for (int at = from; at <= to - pattern.length; at++) {
            if (Arrays.mismatch(buffer, at, at + pattern.length, pattern, 0, pattern.length) < 0) {
                return at;
            }
        }
        return -1;
    }

    /** {@code in} cut off after {@code limit} bytes - what lets a framed codec hand its inner codec the body
     *  without the footer, when the end of the body is known by arithmetic rather than by looking. */
    private static InputStream limited(InputStream in, long limit) {
        return new FilterInputStream(in) {

            private long left = limit;

            @Override
            public int read() throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int read = super.read();
                if (read >= 0) {
                    left--;
                }
                return read;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int read = super.read(buffer, offset, (int) Math.min(length, left));
                if (read > 0) {
                    left -= read;
                }
                return read;
            }
        };
    }

    private static OutputStream digesting(OutputStream out, MessageDigest sha256, MessageDigest md5) {
        OutputStream digested = new DigestOutputStream(out, sha256);
        return md5 == null ? digested : new DigestOutputStream(digested, md5);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(algorithm + " is required of every JDK", impossible);
        }
    }


    /** A count a {@link Generator.Sink} lambda can raise; a local cannot be captured mutably. */
    private static final class Counter {

        private long count;
    }

    // ---- framing ----

    /** The sequence a document written after {@code priorSeq} carries. Monotone per document, and past any
     *  sequence a forgotten predecessor could have reached (a wall-clock floor), so a derived document written
     *  against the old sequence never outranks the regenerated one. A first write passes {@code 0}. */
    private static long sequence(long priorSeq) {
        return Math.max(priorSeq + 1, System.currentTimeMillis());
    }

    /**
     * The header and body as one array.
     *
     * <p><b>This holds the document twice for the length of the copy</b>, and there is no way around it while
     * {@link ArtifactStore#writeVersioned} takes a {@code byte[]}: a listing needs compare-and-set, and the
     * streaming {@link ArtifactStore#write(String, InputStream)} has none. The copy is therefore the floor rather
     * than an oversight, and it is worth knowing which of the two is the peak - for a repository-wide index the
     * body dominates, so a deployment sizing its heap against the largest listing should budget twice it.
     */
    /** The header bytes a document is stored behind - the half of {@link #frame} a streamed write needs on its own. */
    private static byte[] head(Header header) {
        return (MAGIC + "\nseq=" + header.seq() + "\nsize=" + header.size() + "\nmd5=" + header.md5()
                + "\nsha256=" + header.sha256() + "\nentries=" + header.entries()
                + "\n\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] frame(Header header, byte[] body) {
        byte[] head = head(header);
        byte[] framed = new byte[head.length + body.length];
        System.arraycopy(head, 0, framed, 0, head.length);
        System.arraycopy(body, 0, framed, head.length, body.length);
        return framed;
    }

    private static Document parse(byte[] framed, String key) throws IOException {
        int end = headerEnd(framed, key);
        Header header = header(new String(framed, 0, end, StandardCharsets.US_ASCII), key);
        return new Document(header, Arrays.copyOfRange(framed, end + 2, framed.length));
    }

    private static int headerEnd(byte[] framed, String key) throws IOException {
        for (int i = 0; i + 1 < framed.length && i < 512; i++) {
            if (framed[i] == '\n' && framed[i + 1] == '\n') {
                return i;
            }
        }
        throw new IOException("not a listing document: " + key);
    }

    private static Header header(InputStream in, String key) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b < 0 || head.size() > 512) {
                throw new IOException("not a listing document: " + key);
            }
            if (b == '\n' && previous == '\n') {
                break;
            }
            head.write(b);
            previous = b;
        }
        String text = head.toString(StandardCharsets.US_ASCII);
        return header(text.endsWith("\n") ? text.substring(0, text.length() - 1) : text, key);
    }

    private static Header header(String head, String key) throws IOException {
        String[] lines = head.split("\n");
        if (lines.length < 4 || !lines[0].equals(MAGIC)) {
            throw new IOException("not a listing document: " + key);
        }
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int equals = lines[i].indexOf('=');
            if (equals <= 0) {
                throw new IOException("malformed listing header line: " + lines[i]);
            }
            fields.put(lines[i].substring(0, equals), lines[i].substring(equals + 1));
        }
        // A document from before the header dropped its SHA-1 carries one more line; it is read and ignored.
        if (!fields.containsKey("seq") || !fields.containsKey("size") || !fields.containsKey("sha256")) {
            throw new IOException("not a listing document: " + key);
        }
        try {
            // An absent entries= is a document written before the count was recorded, and reads as UNKNOWN rather
            // than as zero: "nobody counted" and "counted, and there were none" are the two answers this whole field
            // exists to separate, and defaulting to zero would assert the second from the absence of evidence. The
            // listing-rebuild repair pass regenerates such a document with a count.
            return new Header(Long.parseLong(fields.get("seq")), Long.parseLong(fields.get("size")),
                    fields.getOrDefault("md5", ""), fields.get("sha256"),
                    Long.parseLong(fields.getOrDefault("entries", String.valueOf(Header.UNKNOWN))));
        } catch (NumberFormatException e) {
            throw new IOException("not a listing document: " + key, e);
        }
    }

    // ---- repair ----

    /**
     * A format's face for the repair pass: asked, for every stored listing document, to regenerate the ones it owns.
     * Discovered through the {@link PublicationObserver} list (a format's listing observer implements both), so a
     * format that maintains listings also repairs them.
     */
    public interface Rebuilder {

        /** Regenerate the listing at this key if it is one of this rebuilder's; {@code false} when it is not. A
         *  derived twin is regenerated with its source and answers {@code true} without work of its own. */
        boolean rebuild(String listing, ArtifactStore store) throws IOException;

        /**
         * Create the listings this format's stored content implies but which do not exist yet. Returns how many
         * were created; the default creates none, so a format is never wrong for not implementing this.
         *
         * <p><b>Why this is not covered by {@link #rebuild}.</b> The repair pass walks the listing namespace and
         * asks each rebuilder to claim what it finds, so it regenerates documents that <em>exist</em>. That was
         * held to be sufficient because a format's listing is created by a publish through that format - and it is
         * not: a <em>migration</em> lays content out directly. After an import from an incumbent manager the tag
         * pointers are all there and the tag list is not, so the daily pass has nothing to claim and the first
         * client request generates it inline. Measured at 200,000 tags: <b>35 seconds</b> on a request thread,
         * against 186 ms once the document exists.
         *
         * <p>So this is &sect;5's clause said for stored listings - derived state converges from the durable store
         * rather than being paid for by whoever asks first. It runs on the maintenance pass, which is where a walk
         * of a format's namespace belongs; a format whose content only ever arrives by publish through itself has
         * nothing to add and inherits the empty default.
         */
        default int materialise(ArtifactStore store, Scope scope) throws IOException {
            return 0;
        }

        /**
         * Which of the implied listings a caller wants built.
         *
         * <p>The two callers differ, and the difference is a wrong answer rather than a slow one. The repair pass
         * wants {@link #MISSING}: it regenerates every stored document in its second phase anyway, so building
         * one that already exists here would be work done twice. An import wants {@link #ALL}, because it laid
         * content out <em>without</em> the observers that maintain listings - so a read that raced the walk may
         * have generated a document from the content that existed at that instant, and that document exists, is
         * short, and would be skipped by a probe for absence.
         */
        enum Scope {

            /** Only listings that do not exist yet. */
            MISSING,

            /** Every listing the content implies, whether stored or not. */
            ALL
        }
    }

    /**
     * Regenerate every stored listing of this store through the given rebuilders - the daily repair pass that
     * corrects any drift an incremental write could have left. Walks the listing namespace only; a document no
     * rebuilder owns is left as it is. Returns how many documents were regenerated.
     */
    public static int rebuildAll(ArtifactStore store, List<? extends Rebuilder> rebuilders) throws IOException {
        int created = 0;
        for (Rebuilder rebuilder : rebuilders) {
            // Absent listings first: a document created here is then walked below like any other, so an imported
            // repository converges in one pass rather than needing a second to repair what the first invented.
            // MISSING, because the walk below regenerates everything that already exists.
            created += rebuilder.materialise(store, Rebuilder.Scope.MISSING);
        }
        List<String> keys = new ArrayList<>();
        collect(store, ROOT.substring(0, ROOT.length() - 1), keys);
        int rebuilt = 0;
        for (String key : keys) {
            String listing = key.substring(ROOT.length());
            for (Rebuilder rebuilder : rebuilders) {
                if (rebuilder.rebuild(listing, store)) {
                    rebuilt++;
                    break;
                }
            }
        }
        return rebuilt + created;
    }

    // ---- observability ----

    /** The counters every node keeps over its listing writes, reported as {@code jenreg.listing.*}. */
    public static final class Observability implements ObservabilitySource {

        public Observability() {
        }

        @Override
        public List<Metric> metrics() {
            return List.of(
                    Metric.counter("jenreg.listing.updates", "Listing documents rewritten on the write path",
                            UPDATES.sum(), "writes"),
                    Metric.counter("jenreg.listing.coalesced",
                            "Listing changes that rode along another writer's rewrite instead of their own",
                            COALESCED.sum(), "writes"),
                    Metric.counter("jenreg.listing.conflicts",
                            "Listing writes retried after another node changed the document first",
                            CONFLICTS.sum(), "retries"),
                    Metric.counter("jenreg.listing.materialised",
                            "Listing documents generated from the store - first use, or a rebuild",
                            MATERIALISED.sum(), "documents"),
                    Metric.counter("jenreg.listing.forgotten",
                            "Listing documents dropped for regeneration after a write could not land",
                            FORGOTTEN.sum(), "documents"));
        }
    }
}
