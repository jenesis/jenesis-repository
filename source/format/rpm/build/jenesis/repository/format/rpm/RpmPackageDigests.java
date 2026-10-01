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
 * about to cache, so an upstream {@code .rpm} is held to its declaring index. The request names its repository, so the
 * index is at {@code <upstream>/<repo>/repodata/repomd.xml}, which names the {@code primary} index, whose
 * {@code <package>} at {@code <location href="<location>">} carries the {@code <checksum type="sha256" pkgid="YES">}.
 *
 * <p><b>Read once and remembered.</b> A mirror's primary index runs to hundreds of megabytes decompressed, so the first
 * fill streams it once into a compact {@code <type> <hex> <location>} map in the content-addressed store, keyed by the
 * index's identity (the checksum {@code repomd.xml} publishes for it). A later fill reads {@code repomd.xml} and
 * answers from the map while the identity holds; a moved index rebuilds the map, and the superseded blob is left to the
 * collector.
 *
 * <p><b>Streaming on both sides.</b> The index is parsed with StAX a {@code <package>} at a time and the map generated
 * into the store as it reads ({@link LineStream}).
 *
 * <p><b>Bounded.</b> The decompressed index is capped by the shared archive-walk ceiling scaled by
 * {@link #INFLATION_RATIO}, on the decompressed size (ProxyFormat clause 7). Reaching it is an {@link UnreadableIndex}
 * that aborts the map and the fill, since a truncated index would claim a package "is not in the index".
 */
final class RpmPackageDigests {

    private static final String REPODATA = "repodata/";

    /** How far past its transferred size the {@code primary} index may decompress, applied through
     *  {@link ArchiveWalk#largestWalk(long, long)}. XML gzips at about ten to one, so twenty leaves an honest mirror
     *  room and stops a hundred-to-one body. */
    private static final long INFLATION_RATIO = 20L;

    /** The map's pointer, one per proxied repository, beside the generated {@code repodata}, so the pool walks (which
     *  skip that subtree) never see it as a package. */
    private static String mapKey(String repo) {
        return "rpm/" + repo + "/" + REPODATA + "proxy-digests";
    }

    private RpmPackageDigests() {
        throw new UnsupportedOperationException("RpmPackageDigests is a static utility");
    }

    /** An index that could not be read through - malformed, unanswering, or past the decompression bound. Its own type
     *  so it is never confused with {@link ProxyRelay.Declared#NONE}, which caches unverified: {@link #declared} turns
     *  it into an {@linkplain ProxyRelay.Declared#unreadable unreadable} verdict, declining the fill rather than
     *  downgrading it or answering a {@code 500}. */
    static sealed class UnreadableIndex extends IOException permits RefusedTarget {

        UnreadableIndex(String message) {
            super(message);
        }

        UnreadableIndex(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The outbound screen refused an upstream-advertised index URL; its message names the dial that would permit
     *  it. */
    static final class RefusedTarget extends UnreadableIndex {

        RefusedTarget(String message) {
            super(message);
        }
    }

    /**
     * The checksum the repository's {@code repodata} declares for {@code location}.
     *
     * <p>{@link ProxyRelay.Declared#NONE}, cached unverified, when the upstream answers that it declares nothing: a
     * {@code repomd.xml} answering {@code 404}/{@code 410}, no {@code primary} index, no {@code <package>} at the
     * location, or a {@code <checksum>} absent, malformed or of an algorithm this JVM cannot compute.
     *
     * <p>{@linkplain ProxyRelay.Declared#unreadable Unreadable}, declining the fill, when the index could not be read:
     * unreached, a {@code 429}/{@code 5xx}/challenge, malformed, unanswering, past the decompression bound, or refused
     * by the outbound screen.
     *
     * @throws IOException when the store fails reading or rebuilding the map, a failure of this repository rather than
     *     a statement about the upstream
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
            // A malformed digest declares nothing.
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
                    // The index's checksum identifies its revision; without one the href does, which createrepo's
                    // --unique-md-filenames makes revision-specific.
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

    /** Scan a stored map for {@code location}: its line, {@code ""} when the map is current but lists no such package,
     *  or {@code null} when it was built from another revision. Read a line at a time. */
    private static String read(Blobs blobs, String hash, String identity, String location) throws IOException {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(blobs.open(hash), StandardCharsets.UTF_8))) {
            String head = lines.readLine();
            if (head == null || !head.equals(identity)) {
                return null;
            }
            String suffix = " " + location;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                // The suffix test first, then the exact one: a package must never be checked against another's digest.
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
        // The href is untrusted upstream content and an absolute value replaces the host on resolve, so it is screened
        // by the shared outbound call; an href naming no host is refused.
        String refusal = OutboundTargets.advertisedRefusal(index, repository, allowInternal);
        if (refusal != null) {
            // A distinct exception rather than NONE, which would cache unverified: RpmFormat.cache declines the fill
            // and warns, naming the dial.
            throw new RefusedTarget("Refusing the primary index at " + index + ": " + refusal
                    + " (set proxy-allow-internal to permit an internal or http target)");
        }
        try (ProxyFormat.Download download = fetcher.download(index, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                throw new UnreadableIndex("The repodata names a primary index at " + index + " that does not answer");
            }
            // The ratio scales the transferred size into a budget counted after the decompressor, since the upstream
            // chooses the ratio (ProxyFormat clause 7).
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
     *  compute, which declares no check. */
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

    /** Pulls the map's identity line, then a {@code <type> <hex> <location>} line per package, as fast as the store
     *  consumes them. */
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
                            // A newline or leading space would break the line encoding, so such an href stays out of
                            // the map and its package unverified.
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

    /** An {@link InputStream} over an iterator of lines, so the store reads the map into existence. */
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

    /** Counts the bytes out of the decompressor and fails, by name, at the ceiling (ProxyFormat clause 7). */
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
                        + "-byte read bound (jenrepo.archive.largest-walk); refusing to build a package-digest map "
                        + "from a truncated index");
            }
        }
    }
}
