package build.jenesis.repository.format.rpm;

import module java.base;
import module java.xml;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The per-package checksum a yum repository's own {@code repodata} declares, read for the one pool path a proxy fill is
 * about to cache - so an upstream {@code .rpm} is held to its declaring index instead of being cached on the upstream's
 * word.
 *
 * <h2>Why RPM can do what Debian cannot</h2>
 * Both ecosystems publish a per-package digest in an index rather than beside the artifact. Debian's is unreachable
 * from the request, because a {@code .deb} lives in a {@code pool/} tree shared by many suites and the pool {@code GET}
 * carries no suite, component or architecture with which to find the {@code Packages} file that declares it. An RPM
 * request does not have that problem: {@code /rpm/<repo>/<location>} names its repository in its first segment, so the
 * declaring index is at {@code <upstream>/<repo>/repodata/repomd.xml}, which names the {@code primary} index, whose
 * {@code <package>} carrying {@code <location href="<location>">} carries the
 * {@code <checksum type="sha256" pkgid="YES">} of exactly those bytes.
 *
 * <h2>The index is huge, so it is read once and remembered</h2>
 * A distribution mirror's {@code primary.xml.gz} runs to tens of megabytes compressed and hundreds decompressed.
 * Re-reading it per package would make one {@code dnf install} of thirty packages pull the index thirty times, and a
 * verification an operator turns off is not a verification. So the first fill of a repository streams the index once
 * and writes a compact {@code <type> <hex> <location>} map into the content-addressed store, keyed by the index's own
 * identity (the {@code <checksum>} {@code repomd.xml} publishes for it); every later fill reads {@code repomd.xml}
 * (three kilobytes), finds the identity unchanged, and answers from the map. The index moves, the identity moves, and
 * the map is rebuilt - so a stale map cannot outlive the index that produced it, and the superseded blob becomes
 * unreferenced for the collector rather than accumulating a pointer per revision.
 *
 * <p><b>Streaming on both sides.</b> The index is parsed with StAX one {@code <package>} at a time and the map is
 * <em>generated</em> into the store as the store reads it ({@link LineStream}), so neither the index nor the map is
 * ever materialised. Nothing here holds more than one package's worth of strings.
 *
 * <p><b>Bounded.</b> The decompressed index is capped through the product's shared, operator-settable archive-walk
 * ceiling scaled by {@link #INFLATION_RATIO} - on the decompressed size, as {@code ProxyFormat} clause 7 requires,
 * because an upstream chooses the compression ratio. Reaching it is an {@link UnreadableIndex} that aborts the map and
 * therefore the fill: a map built from a truncated index would silently claim a package "is not in the index", which is
 * the one answer a bound must never be allowed to fabricate.
 */
final class RpmPackageDigests {

    private static final String REPODATA = "repodata/";

    /**
     * How far past its own transferred size the {@code primary} index may decompress before the read is cut off,
     * applied through the shared {@link ArchiveWalk#largestWalk(long, long)} floor. The ratio is this format's own
     * judgement about its own container and stays at the call site that applies it: {@code primary.xml} is XML and
     * gzips at roughly ten to one, so twenty leaves an honest mirror ample room while a body claiming a hundred-to-one
     * ratio is stopped.
     */
    private static final long INFLATION_RATIO = 20L;

    /** The map's own pointer, one per proxied repository - a sibling of the generated {@code repodata}, so the pool
     *  walks that build {@code blobKeys} (which skip the {@code repodata} subtree) never see it as a package. */
    private static String mapKey(String repo) {
        return "rpm/" + repo + "/" + REPODATA + "proxy-digests";
    }

    private RpmPackageDigests() {
        throw new UnsupportedOperationException("RpmPackageDigests is a static utility");
    }

    /** An index this repository could not read <em>through</em> - a malformed {@code repomd.xml} or {@code primary},
     *  a primary index that does not answer, or one that ran past the decompression bound. Its own type, because it
     *  must never be confused with {@link ProxyRelay.Declared#NONE} ("the repodata declares no checksum", which caches
     *  the fill unverified): {@link #declared} turns it into an {@linkplain ProxyRelay.Declared#unreadable unreadable}
     *  verdict, so the fill is declined rather than either downgraded or thrown out of a proxy read as a {@code 500}. */
    static sealed class UnreadableIndex extends IOException permits RefusedTarget {

        UnreadableIndex(String message) {
            super(message);
        }

        UnreadableIndex(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The outbound screen refused an upstream-ADVERTISED index URL - the first of the unreadable shapes to be
     *  given the refusal, and the reason it keeps its own name is that its message names the dial an operator would
     *  set to permit the target. */
    static final class RefusedTarget extends UnreadableIndex {

        RefusedTarget(String message) {
            super(message);
        }
    }

    /**
     * The checksum the repository's {@code repodata} declares for {@code location}.
     *
     * <p>{@link ProxyRelay.Declared#NONE} - the fill caches unverified - for the shapes {@link RpmFormat} states,
     * every one of them the upstream <em>answering</em> that it declares nothing: a {@code <repo>/repodata/repomd.xml}
     * that answers {@code 404}/{@code 410}, a {@code repomd.xml} naming no {@code primary} index, an index that lists
     * no {@code <package>} at that {@code <location href>}, and a listed package whose {@code <checksum>} is absent,
     * malformed, or of an algorithm this JVM has no digest for.
     *
     * <p>{@linkplain ProxyRelay.Declared#unreadable Unreadable} - the fill is declined - when the declaring index could
     * not be read at all: a {@code repomd.xml} the transport never reached or that answered a {@code 429}/{@code 5xx}/
     * challenge, a malformed {@code repomd.xml} or {@code primary}, a primary index that does not answer, one past the
     * decompression bound, and one whose URL the outbound screen refuses. Before this, the first two of those returned
     * the same "declares no checksum" as a plain file mirror and the package was cached with no point check at all.
     *
     * @throws IOException when the <em>store</em> fails while the index map is read or rebuilt - a real failure of this
     *                     repository, not a statement about the upstream, so it is not folded into a verdict
     */
    static ProxyRelay.Declared declared(ProxyFormat.Fetcher fetcher, URI upstream, String repo, String location,
                                        Blobs blobs, boolean allowInternal) throws IOException {
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        URI repository = URI.create(root + repo + "/");
        URI index = repository.resolve(REPODATA + "repomd.xml");
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, index, Map.of());
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        String line;
        try {
            Primary primary = primary(sidecar.document().body(), index);
            if (primary == null) {
                return ProxyRelay.Declared.NONE;
            }
            line = lookup(blobs, repo, primary, repository, fetcher, location, allowInternal);
        } catch (UnreadableIndex unreadable) {
            return ProxyRelay.Declared.unreadable(unreadable.getMessage());
        }
        if (line == null) {
            return ProxyRelay.Declared.NONE;
        }
        String[] parts = line.split(" ", 3);
        if (parts.length != 3) {
            return ProxyRelay.Declared.NONE;
        }
        String algorithm = algorithm(parts[0]);
        if (algorithm == null) {
            return ProxyRelay.Declared.NONE;   // a checksum type no JDK digest implements is not a check to run
        }
        try {
            return ProxyRelay.Declared.of(algorithm, HexFormat.of().parseHex(parts[1]));
        } catch (IllegalArgumentException _) {
            // a malformed digest declares nothing, exactly as a malformed npm integrity string does
            return ProxyRelay.Declared.NONE;
        }
    }

    /** The {@code <data type="primary">} entry of a {@code repomd.xml}: where the index is and what identifies its
     *  current revision. {@code null} when the document names no primary index. */
    private static Primary primary(byte[] repomd, URI index) throws UnreadableIndex {
        try {
            XMLStreamReader reader = factory().createXMLStreamReader(new ByteArrayInputStream(repomd));
            boolean primary = false;
            String href = null;
            String identity = null;
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (reader.getLocalName()) {
                    case "data" -> {
                        primary = "primary".equals(reader.getAttributeValue(null, "type"));
                        href = null;
                        identity = null;
                    }
                    case "checksum" -> {
                        if (primary) {
                            identity = reader.getAttributeValue(null, "type") + ":" + reader.getElementText().trim();
                        }
                    }
                    case "location" -> {
                        if (primary) {
                            href = reader.getAttributeValue(null, "href");
                        }
                    }
                    default -> {
                    }
                }
                if (primary && href != null) {
                    // The index's own checksum identifies its revision; a repository that publishes none falls back on
                    // the href, which createrepo's --unique-md-filenames already makes revision-specific.
                    return new Primary(href, identity == null ? "href:" + href : identity);
                }
            }
            return null;
        } catch (XMLStreamException e) {
            throw new UnreadableIndex("Unreadable repomd.xml at " + index, e);
        }
    }

    /** Where the {@code primary} index is, and what identifies the revision of it that is current. */
    private record Primary(String href, String identity) {
    }

    /** The map line for {@code location}, rebuilding the map first when it is absent or was built from a different
     *  index revision. */
    private static String lookup(Blobs blobs, String repo, Primary primary, URI repository,
                                 ProxyFormat.Fetcher fetcher, String location, boolean allowInternal)
            throws IOException {
        String hash = blobs.hash(mapKey(repo)).orElse(null);
        if (hash != null) {
            String line = read(blobs, hash, primary.identity(), location);
            if (line != null) {
                return line.isEmpty() ? null : line;   // the empty answer means "this map is current and lists no such package"
            }
        }
        rebuild(blobs, repo, primary, repository, fetcher, allowInternal);
        String rebuilt = blobs.hash(mapKey(repo)).orElse(null);
        if (rebuilt == null) {
            return null;
        }
        String line = read(blobs, rebuilt, primary.identity(), location);
        return line == null || line.isEmpty() ? null : line;
    }

    /**
     * Scan a stored map for {@code location}: its map line, or {@code ""} when the map is current but lists no such
     * package, or {@code null} when the map was built from a different index revision (so the caller rebuilds). Read a
     * line at a time off the content-addressed store, so a map with a hundred thousand packages never lands in heap.
     */
    private static String read(Blobs blobs, String hash, String identity, String location) throws IOException {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(blobs.open(hash), StandardCharsets.UTF_8))) {
            String head = lines.readLine();
            if (head == null || !head.equals(identity)) {
                return null;
            }
            String suffix = " " + location;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                // The cheap suffix test first, then the exact one: "hello.rpm" is a suffix of "Packages/hello.rpm",
                // and a package must never be verified against a different package's digest.
                if (line.endsWith(suffix)) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length == 3 && parts[2].equals(location)) {
                        return line;
                    }
                }
            }
            return "";
        }
    }

    /** Stream the upstream {@code primary} index once and write its {@code <type> <hex> <location>} map into the
     *  content-addressed store, pointing this repository's map key at it. */
    private static void rebuild(Blobs blobs, String repo, Primary primary, URI repository,
                                ProxyFormat.Fetcher fetcher, boolean allowInternal) throws IOException {
        URI index = repository.resolve(primary.href());
        // The href comes from an untrusted upstream repomd.xml, and java.net.URI.resolve returns an absolute argument
        // as-is - so an index href pointing at a loopback, metadata or plaintext host would be fetched server-side from
        // here. Screened by the one shared outbound call every peer leg makes: the http(s)/host
        // capability floor no dial lifts, then the upstream's own ORIGIN admitted, then the full transport-and-host
        // screen on anything cross-origin under the one ProxyLeg.ALLOW_INTERNAL dial. Two things moved: this leg used
        // to admit an index href naming NO host at all (resolvesToPrivate(null) answers false), which the importer's
        // HttpRequest.newBuilder then threw on, and its same-origin test lived here as a private copy.
        String refusal = OutboundTargets.advertisedRefusal(index, repository, allowInternal);
        if (refusal != null) {
            // A distinct exception rather than a null Digest, because a null here means "the repodata declares no
            // checksum" and downgrades the fill to UNVERIFIED caching - the one outcome a refused index must never
            // produce (§9). RpmFormat.cache turns it into the clause-2 decline: nothing cached,
            // nothing served, the local 404 stands, and a WARN naming the dial.
            throw new RefusedTarget("Refusing the primary index at " + index + ": " + refusal
                    + " (set proxy-allow-internal to permit an internal or http target)");
        }
        try (ProxyFormat.Download download = fetcher.download(index, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                throw new UnreadableIndex("The repodata names a primary index at " + index + " that does not answer");
            }
            // The ratio scales the TRANSFERRED size into a budget on the DECOMPRESSED one, and the counter sits on the
            // far side of the decompressor - a bound on the transferred bytes would be no bound at all, since the
            // upstream chooses the ratio (ProxyFormat clause 7).
            long ceiling = ArchiveWalk.largestWalk(length(download.header("Content-Length")), INFLATION_RATIO);
            InputStream inflated = primary.href().endsWith(".gz")
                    ? new GZIPInputStream(download.body())
                    : download.body();
            XMLStreamReader reader;
            try {
                reader = factory().createXMLStreamReader(new Bounded(inflated, ceiling, index));
            } catch (XMLStreamException e) {
                throw new UnreadableIndex("Unreadable primary index at " + index, e);
            }
            try (InputStream map = new LineStream(new Packages(reader, primary.identity()))) {
                blobs.link(mapKey(repo), blobs.store(map));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    private static long length(String header) {
        try {
            return header == null ? -1L : Long.parseLong(header.trim());
        } catch (NumberFormatException _) {
            return -1L;
        }
    }

    /** The {@link MessageDigest} name for a {@code repodata} checksum type, or {@code null} for one this JVM cannot
     *  compute - which declares no check rather than a check that always passes. */
    private static String algorithm(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "sha256", "sha-256" -> "SHA-256";
            case "sha512", "sha-512" -> "SHA-512";
            case "sha1", "sha-1", "sha" -> "SHA-1";
            case "md5" -> "MD5";
            default -> null;
        };
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /** Pulls the map's identity line, then one {@code <type> <hex> <location>} line per indexed package, off the
     *  streaming reader - so the map is produced exactly as fast as the store consumes it. */
    private static final class Packages implements Iterator<String> {

        private final XMLStreamReader reader;
        private String next;

        private Packages(XMLStreamReader reader, String identity) {
            this.reader = reader;
            this.next = identity + "\n";
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            String type = null;
            String checksum = null;
            String href = null;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("package")) {
                        if (type != null && checksum != null && href != null) {
                            next = type + " " + checksum + " " + href + "\n";
                            return true;
                        }
                        type = null;
                        checksum = null;
                        href = null;
                        continue;
                    }
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    switch (reader.getLocalName()) {
                        case "package" -> {
                            type = null;
                            checksum = null;
                            href = null;
                        }
                        case "checksum" -> {
                            String declared = reader.getAttributeValue(null, "type");
                            String value = reader.getElementText().trim();
                            if (declared != null && !value.isEmpty() && declared.indexOf(' ') < 0) {
                                type = declared;
                                checksum = value;
                            }
                        }
                        case "location" -> {
                            String value = reader.getAttributeValue(null, "href");
                            // A newline or a leading space would break the one-line-per-package encoding; such an href
                            // simply does not enter the map, so the package it names stays unverified rather than
                            // corrupting its neighbours' lines.
                            href = value == null || value.indexOf('\n') >= 0 || value.startsWith(" ") ? null : value;
                        }
                        default -> {
                        }
                    }
                }
                return false;
            } catch (XMLStreamException e) {
                throw new UncheckedIOException(new UnreadableIndex("Unreadable primary index", e));
            }
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String line = next;
            next = null;
            return line;
        }
    }

    /** An {@link InputStream} over an iterator of lines - the pull-based generator that lets the content-addressed
     *  store read the map into existence rather than being handed a buffer someone built first. */
    private static final class LineStream extends InputStream {

        private final Iterator<String> lines;
        private byte[] chunk = new byte[0];
        private int offset;

        private LineStream(Iterator<String> lines) {
            this.lines = lines;
        }

        @Override
        public int read() {
            return fill() ? chunk[offset++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int start, int count) {
            Objects.checkFromIndexSize(start, count, buffer.length);
            if (count == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int written = Math.min(count, chunk.length - offset);
            System.arraycopy(chunk, offset, buffer, start, written);
            offset += written;
            return written;
        }

        private boolean fill() {
            while (offset >= chunk.length) {
                if (!lines.hasNext()) {
                    return false;
                }
                chunk = lines.next().getBytes(StandardCharsets.UTF_8);
                offset = 0;
            }
            return true;
        }
    }

    /** Counts what the index read draws and fails, by name, at the ceiling - the visible bound {@code ProxyFormat}
     *  clause 7 requires, applied where it belongs: on the bytes that come OUT of the decompressor. */
    private static final class Bounded extends FilterInputStream {

        private final long ceiling;
        private final URI index;
        private long drawn;

        private Bounded(InputStream in, long ceiling, URI index) {
            super(in);
            this.ceiling = ceiling;
            this.index = index;
        }

        @Override
        public int read() throws IOException {
            int read = in.read();
            if (read >= 0) {
                charge(1);
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int start, int count) throws IOException {
            int read = in.read(buffer, start, count);
            if (read > 0) {
                charge(read);
            }
            return read;
        }

        private void charge(long bytes) throws IOException {
            drawn += bytes;
            if (drawn > ceiling) {
                throw new UnreadableIndex("The primary index at " + index + " exceeds the " + ceiling
                        + "-byte read bound (jenreg.archive.largest-walk); refusing to build a package-digest map "
                        + "from a truncated index");
            }
        }
    }
}
