package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.format.jvm.MavenMetadataSettingsContributor;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Computes a coordinate's artifact-level {@code maven-metadata.xml} from its version folders, under the opt-in
 * {@link #COMPUTE_SETTING}. The version list is derived from per-version pointers, distinct append-only keys, so two
 * nodes publishing different versions never write the same object. {@code <release>} is the highest non-snapshot
 * version and {@code <latest>} the highest overall in Maven version order; {@code <lastUpdated>} is omitted so the
 * bytes are a pure function of the version set and a cached checksum still matches. The {@code .sha1} and {@code .md5}
 * are computed from the same bytes.
 *
 * <p>A publisher's own document is kept: {@link #computed} reconciles its {@code <versions>} against the folders,
 * leaving every other field verbatim, and derives a whole document ({@link #serve}) only for a coordinate no client
 * uploaded one for. The served document is the stored listing {@link MavenMetadataListing} the uploads maintain.
 */
public final class MavenMetadata {

    // Shared across renders: newInstance() runs the JAXP provider lookup, and a configured factory is safe to share.
    private static final XMLOutputFactory XML_OUTPUT = XMLOutputFactory.newInstance();

    /** The setting key (under {@code jenrepo.}) that opts a deployment into computing {@code maven-metadata.xml};
     *  default off, read off the exchange. Defined where the settings catalogue describes it. */
    public static final String COMPUTE_SETTING = MavenMetadataSettingsContributor.COMPUTE_SETTING;

    /** How many children of one coordinate a render may examine: far above any real release history (a few thousand
     *  versions) and far below what an attacker-shaped coordinate could force from one GET. Reaching it fails the
     *  render ({@link #versions}). */
    private static final int VERSION_SCAN = 50_000;

    private final ArtifactStore store;

    public MavenMetadata(ArtifactStore store) {
        this.store = store;
    }

    /** The checksums a Maven client may ask for beside a {@code maven-metadata.xml}, by suffix, each with the
     *  algorithm it names - the two every client asks for and the two a resolver can be configured to. */
    static final Map<String, String> CHECKSUMS = Map.of(".sha1", "SHA-1", ".md5", "MD5", ".sha256", "SHA-256",
            ".sha512", "SHA-512");

    /** Whether this request path is an artifact-level {@code maven-metadata.xml} or one of its checksums. */
    public static boolean isMetadataRequest(String requestPath) {
        return requestPath.startsWith("/maven/")
                && (requestPath.endsWith("/maven-metadata.xml") || algorithm(requestPath).isPresent());
    }

    /** The algorithm a metadata checksum path names, or empty for the document itself or any other path. */
    static Optional<String> algorithm(String requestPath) {
        int dot = requestPath.lastIndexOf('.');
        return dot > 0 && requestPath.substring(0, dot).endsWith("/maven-metadata.xml")
                ? Optional.ofNullable(CHECKSUMS.get(requestPath.substring(dot)))
                : Optional.empty();
    }

    /** The bytes for a metadata request under the opt-in {@link #COMPUTE_SETTING}, from the coordinate's stored listing
     *  ({@link MavenMetadataListing}), materialised from {@link #computed} on first read. A checksum is served from its
     *  derived twin when the listing authored the document, else empty so the caller serves the publisher's own. */
    public Optional<byte[]> served(String requestPath) throws IOException {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        Optional<String> algorithm = algorithm(requestPath);
        boolean checksum = algorithm.isPresent();
        String documentPath = checksum ? requestPath.substring(0, requestPath.lastIndexOf('.')) : requestPath;
        String coordinatePath = coordinatePath(documentPath);
        MavenMetadataListing listing = new MavenMetadataListing(store);
        Optional<StoredListing.Document> document = StoredListing.read(store, listing.spec(coordinatePath));
        if (document.isEmpty() || document.get().body().length == 0) {
            return Optional.empty();
        }
        if (!checksum) {
            return Optional.of(document.get().body());
        }
        Optional<byte[]> stored = storedBytes(documentPath);
        if (stored.isPresent() && Arrays.equals(stored.get(), document.get().body())) {
            return Optional.empty();   // the publisher's own document, byte for byte: its own checksum stands
        }
        return Optional.of(Checksums.hex(algorithm.get(), document.get().body()).getBytes(StandardCharsets.UTF_8));
    }

    /** A Maven path was uploaded under the computation flag: a metadata document resets its coordinate's listing, a
     *  version's artifact adds its version. */
    public void uploaded(String requestPath) throws IOException {
        if (!requestPath.startsWith("/maven/")) {
            return;
        }
        MavenMetadataListing listing = new MavenMetadataListing(store);
        if (requestPath.endsWith("/maven-metadata.xml")) {
            listing.uploaded(coordinatePath(requestPath));
            return;
        }
        String body = requestPath.substring("/maven/".length());
        int file = body.lastIndexOf('/');
        int version = file < 0 ? -1 : body.lastIndexOf('/', file - 1);
        if (version > 0 && !isMetadataRequest(requestPath) && !ServableNames.sidecar(body)) {
            listing.refresh(body.substring(0, version), body.substring(version + 1, file));
        }
    }

    /**
     * The bytes for a metadata request under the computation, reconciled from the stored document: only its
     * {@code <versions>} is reconciled against the folders and every other field is kept, a document without one
     * passes through, and a coordinate with no stored document falls back to the full {@link #serve derivation}. A
     * checksum is computed only for a document authored here; for an unchanged one this is empty, so the publisher's
     * own checksum serves.
     */
    public Optional<byte[]> computed(String requestPath) throws IOException {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        Optional<String> algorithm = algorithm(requestPath);
        if (algorithm.isPresent()) {
            String documentPath = requestPath.substring(0, requestPath.lastIndexOf('.'));
            Optional<byte[]> document = computedDocument(documentPath);
            if (document.isEmpty()) {
                return Optional.empty();
            }
            Optional<byte[]> stored = storedBytes(documentPath);
            if (stored.isPresent() && Arrays.equals(stored.get(), document.get())) {
                // The document is served verbatim, so its stored checksum is authoritative - never re-derived.
                return Optional.empty();
            }
            return Optional.of(Checksums.hex(algorithm.get(), document.get()).getBytes(StandardCharsets.UTF_8));
        }
        return computedDocument(requestPath);
    }

    /** The artifact-level document under the computation: a stored document with its versions reconciled, else the full
     *  derivation; empty when neither a document nor a version exists. */
    private Optional<byte[]> computedDocument(String documentPath) throws IOException {
        Optional<byte[]> stored = storedBytes(documentPath);
        if (stored.isPresent()) {
            return Optional.of(reconcileVersions(documentPath, stored.get()));
        }
        return serve(documentPath);
    }

    /** The stored bytes a metadata (or checksum) path currently points at, or empty when nothing is published there. */
    private Optional<byte[]> storedBytes(String requestPath) throws IOException {
        Optional<String> key = new Publication(store).located(requestPath);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        store.read(key.get(), buffer);
        return Optional.of(buffer.toByteArray());
    }

    /** Reconcile a stored document's {@code <versions>} against the coordinate's version folders, leaving every byte
     *  outside the element as written. A document with no {@code <versions>} (a version-level SNAPSHOT document) or one
     *  already listing every folder is returned untouched. */
    private byte[] reconcileVersions(String documentPath, byte[] storedXml) throws IOException {
        String xml = new String(storedXml, StandardCharsets.UTF_8);
        int open = xml.indexOf("<versions>");
        int contentStart;
        int contentEnd;
        boolean selfClosed;
        if (open >= 0) {
            contentStart = open + "<versions>".length();
            contentEnd = xml.indexOf("</versions>", contentStart);
            if (contentEnd < 0) {
                return storedXml;
            }
            selfClosed = false;
        } else {
            open = xml.indexOf("<versions/>");
            if (open < 0) {
                return storedXml;
            }
            contentStart = open + "<versions/>".length();
            contentEnd = contentStart;
            selfClosed = true;
        }
        String coordinatePath = coordinatePath(documentPath);
        List<String> folders = versions(coordinatePath);
        List<String> listed = listedVersions(xml.substring(contentStart, contentEnd));
        // The versions the stored document lists are screened too, so a version held after the document was written
        // drops out of it. The screen stats no blob, so only a held version is dropped.
        ServableNames servableNames = new ServableNames(store);
        // A YANKED mark drops a version as well: this document is what ranges, LATEST and update scans resolve against,
        // while a pinned build still fetches the jar by path. Only YANKED, since Maven has no deprecation signal.
        SortedMap<String, Lifecycle.Flag> marks = Lifecycle.versions(store, mavenCoordinate(coordinatePath));
        SequencedSet<String> union = new LinkedHashSet<>();
        boolean withheldAny = false;
        for (String version : listed) {
            if (yanked(marks, version)) {
                withheldAny = true;
            } else if (servableNames.disclosableVersionFolder("/maven/" + coordinatePath + "/" + version)) {
                union.add(version);
            } else {
                // A withheld listed version: <latest>/<release> are re-derived below over the screened set.
                withheldAny = true;
            }
        }
        for (String folder : folders) {
            if (!yanked(marks, folder)) {
                union.add(folder);
            }
        }
        // The named <latest>/<release> are screened directly, since a version named there may be absent from
        // <versions>.
        String latestNamed = element(xml, "latest");
        String releaseNamed = element(xml, "release");
        boolean latestWithheld = latestNamed != null && !latestNamed.isEmpty()
                && (yanked(marks, latestNamed)
                        || !servableNames.disclosableVersionFolder("/maven/" + coordinatePath + "/" + latestNamed));
        boolean releaseWithheld = releaseNamed != null && !releaseNamed.isEmpty()
                && (yanked(marks, releaseNamed)
                        || !servableNames.disclosableVersionFolder("/maven/" + coordinatePath + "/" + releaseNamed));
        boolean namedWithheld = latestWithheld || releaseWithheld;
        boolean versionsChanged = !union.equals(new LinkedHashSet<>(listed));
        if (!versionsChanged && !namedWithheld) {
            // Nothing added or withheld: the publisher's document byte-for-byte.
            return storedXml;
        }
        List<String> reconciled = new ArrayList<>(union);
        reconciled.sort(MavenMetadata::compareVersions);
        String reconciledXml;
        if (versionsChanged) {
            String baseIndent = indentBefore(xml, open);
            StringBuilder rebuilt = new StringBuilder(xml.length() + reconciled.size() * 32);
            rebuilt.append(xml, 0, open).append("<versions>");
            for (String version : reconciled) {
                // A version is a folder name and may carry & or <, so it is escaped as the StAX derivation escapes it.
                rebuilt.append('\n').append(baseIndent).append("  <version>").append(xmlText(version))
                        .append("</version>");
            }
            rebuilt.append('\n').append(baseIndent).append("</versions>");
            rebuilt.append(xml, selfClosed ? contentStart : contentEnd + "</versions>".length(), xml.length());
            reconciledXml = rebuilt.toString();
        } else {
            // Only a named value is withheld: <versions> stays byte-for-byte.
            reconciledXml = xml;
        }
        if (withheldAny || namedWithheld) {
            // Re-derive <latest>/<release> from the screened set as metadata() does, only when something was withheld.
            // An element the publisher did not write stays absent, and an empty re-derivation removes the element
            // rather than name a held version.
            String latest = reconciled.isEmpty() ? null : reconciled.getLast();
            String release = null;
            for (String version : reconciled) {
                if (!version.endsWith("-SNAPSHOT")) {
                    release = version;
                }
            }
            reconciledXml = rederiveElement(reconciledXml, "latest", latest);
            reconciledXml = rederiveElement(reconciledXml, "release", release);
        }
        return reconciledXml.getBytes(StandardCharsets.UTF_8);
    }

    /** Replace a single {@code <name>...</name>} element's text with {@code value} (escaped as the derivation escapes
     *  it), or remove the element when {@code value} is null. A document without the element is unchanged; only the
     *  first occurrence is rewritten, these elements being single-valued. */
    static String rederiveElement(String xml, String name, String value) {
        String openTag = "<" + name + ">";
        String closeTag = "</" + name + ">";
        int open = xml.indexOf(openTag);
        if (open < 0) {
            return xml; // element absent (or self-closed) - nothing the publisher wrote can name a held version here
        }
        int close = xml.indexOf(closeTag, open + openTag.length());
        if (close < 0) {
            return xml;
        }
        int end = close + closeTag.length();
        if (value != null) {
            return xml.substring(0, open) + openTag + xmlText(value) + closeTag + xml.substring(end);
        }
        // The element goes with its line's leading whitespace, leaving no blank line.
        int lineStart = xml.lastIndexOf('\n', open);
        if (lineStart >= 0 && xml.substring(lineStart + 1, open).isBlank()) {
            return xml.substring(0, lineStart) + xml.substring(end);
        }
        return xml.substring(0, open) + xml.substring(end);
    }

    /** The text of a single-valued {@code <name>...</name>} element, unescaped to the raw form of a folder name, or
     *  null when absent or self-closed. Only the first occurrence is read. */
    static String element(String xml, String name) {
        String openTag = "<" + name + ">";
        int open = xml.indexOf(openTag);
        if (open < 0) {
            return null;
        }
        int close = xml.indexOf("</" + name + ">", open + openTag.length());
        if (close < 0) {
            return null;
        }
        return xmlUnescape(xml.substring(open + openTag.length(), close).trim());
    }

    /** Escape a text node for the hand-built reconcile document as {@code XMLStreamWriter.writeCharacters} does. */
    static String xmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Reverse of {@link #xmlText}, so a parsed version compares and re-emits as the raw folder name. {@code &amp;} is
     *  decoded last so an entity never decodes twice. */
    private static String xmlUnescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Whether an operator has yanked {@code version} - a retraction from resolution, not a hold on the bytes. */
    private static boolean yanked(SortedMap<String, Lifecycle.Flag> marks, String version) {
        Lifecycle.Flag flag = marks.get(version);
        return flag != null && flag.state() == LifecycleMark.YANKED;
    }

    /** The {@code group:artifact} coordinate a {@code group/path/artifact} folder names: what {@code describe} reports,
     *  and so what an operator marks. */
    static String mavenCoordinate(String coordinatePath) {
        int slash = coordinatePath.lastIndexOf('/');
        return slash < 0
                ? coordinatePath
                : coordinatePath.substring(0, slash).replace('/', '.') + ":" + coordinatePath.substring(slash + 1);
    }

    /** The {@code <version>} texts a {@code <versions>} block lists, in document order, unescaped to folder-name
     *  form. */
    static List<String> listedVersions(String inner) {
        List<String> listed = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = inner.indexOf("<version>", cursor);
            if (start < 0) {
                return listed;
            }
            int end = inner.indexOf("</version>", start);
            if (end < 0) {
                return listed;
            }
            listed.add(xmlUnescape(inner.substring(start + "<version>".length(), end).trim()));
            cursor = end + "</version>".length();
        }
    }

    /** The indentation of the line the element at {@code index} starts, or empty when it does not start a line. */
    static String indentBefore(String xml, int index) {
        int lineStart = xml.lastIndexOf('\n', index) + 1;
        String indent = xml.substring(lineStart, index);
        return indent.isBlank() ? indent : "";
    }

    private static String coordinatePath(String requestPath) {
        String body = requestPath.substring("/maven/".length());
        return body.substring(0, body.lastIndexOf("/maven-metadata.xml"));
    }

    /**
     * The answer to a metadata request in a repository that proxies, under the computation option: one document listing
     * the versions the upstream's {@code maven-metadata.xml} lists and the ones published here, in Maven version order,
     * or one of its checksums. {@code upstream} is the upstream's document, empty when it answered that it has none.
     * Empty when neither lists a version.
     */
    public Optional<byte[]> merged(String requestPath, Optional<byte[]> upstream) throws IOException {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        String coordinatePath = coordinatePath(requestPath);
        int slash = coordinatePath.lastIndexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        Set<String> versions = new LinkedHashSet<>(versions(coordinatePath));
        if (upstream.isPresent()) {
            // The raw block: listedVersions unescapes each version itself.
            String document = new String(upstream.get(), StandardCharsets.UTF_8);
            int open = document.indexOf("<versions>");
            int close = open < 0 ? -1 : document.indexOf("</versions>", open);
            if (close > open) {
                versions.addAll(listedVersions(document.substring(open + "<versions>".length(), close)));
            }
        }
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        List<String> ordered = new ArrayList<>(versions);
        ordered.sort(MavenMetadata::compareVersions);
        byte[] xml = metadata(coordinatePath.substring(0, slash).replace('/', '.'), coordinatePath.substring(slash + 1),
                ordered);
        Optional<String> algorithm = algorithm(requestPath);
        return Optional.of(algorithm.isPresent()
                ? Checksums.hex(algorithm.get(), xml).getBytes(StandardCharsets.UTF_8)
                : xml);
    }

    /** The bytes for a metadata request derived from the coordinate's version folders, or its SHA-1 / MD5, or empty
     *  when the path is no metadata request or the coordinate has no versions. */
    public Optional<byte[]> serve(String requestPath) throws IOException {
        if (!isMetadataRequest(requestPath)) {
            return Optional.empty();
        }
        String body = requestPath.substring("/maven/".length());
        String coordinatePath = body.substring(0, body.lastIndexOf("/maven-metadata.xml"));
        int slash = coordinatePath.lastIndexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String artifactId = coordinatePath.substring(slash + 1);
        String groupId = coordinatePath.substring(0, slash).replace('/', '.');
        List<String> versions = versions(coordinatePath);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        byte[] xml = metadata(groupId, artifactId, versions);
        Optional<String> algorithm = algorithm(requestPath);
        return Optional.of(algorithm.isPresent()
                ? Checksums.hex(algorithm.get(), xml).getBytes(StandardCharsets.UTF_8)
                : xml);
    }

    /**
     * The coordinate's disclosable version folders, in Maven version order, through
     * {@link ScreenedNames#versionFolders}: the folder is the unit of disclosure, so a withheld version's name never
     * appears in the document. No blob is statted, so only a held version is dropped, and a hostile folder name is
     * contained in the seam.
     *
     * <p>Bounded at {@value #VERSION_SCAN} folders. A wider coordinate is refused rather than rendered from a truncated
     * list, which a resolver would read as versions that do not exist.
     */
    private List<String> versions(String coordinatePath) throws IOException {
        List<String> versions = new ArrayList<>();
        String prefix = ServableNames.PUBLISHED + "/maven/" + coordinatePath;
        Traversal.Result scanned = ScreenedNames.versionFolders(new ServableNames(store))
                .scanning(BoundedChildren.bounded().entries(VERSION_SCAN).page(BoundedChildren.DRAIN_PAGE))
                .scan(store, prefix, (child, _) -> {
                    // Skip the document and every sidecar the directory can hold, .sha256 and .sha512 included, or a
                    // checksum would read as a version.
                    if (child.equals("maven-metadata.xml") || child.startsWith("maven-metadata.xml.")
                            || child.endsWith(".sha1") || child.endsWith(".md5") || child.endsWith(".sha256")
                            || child.endsWith(".sha512") || child.endsWith(".asc")
                            || child.endsWith(".sigstore.json")) {
                        return;
                    }
                    versions.add(child);
                });
        if (scanned.truncated()) {
            throw new IOException("the coordinate '" + coordinatePath + "' holds more than " + VERSION_SCAN
                    + " version folders; refusing to generate a maven-metadata document from a partial version list");
        }
        versions.sort(MavenMetadata::compareVersions);
        return versions;
    }

    static byte[] metadata(String groupId, String artifactId, List<String> versions) {
        String release = null;
        for (String version : versions) {
            if (!version.endsWith("-SNAPSHOT")) {
                release = version;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XML_OUTPUT.createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("metadata");
            element(writer, "groupId", groupId);
            element(writer, "artifactId", artifactId);
            writer.writeStartElement("versioning");
            element(writer, "latest", versions.getLast());
            if (release != null) {
                element(writer, "release", release);
            }
            writer.writeStartElement("versions");
            for (String version : versions) {
                element(writer, "version", version);
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void element(XMLStreamWriter writer, String name, String text) throws XMLStreamException {
        writer.writeStartElement(name);
        writer.writeCharacters(text);
        writer.writeEndElement();
    }

    /** A Maven-style version order: numeric runs compared as numbers, qualifiers ranked (alpha &lt; ... &lt; snapshot
     *  &lt; release &lt; sp). */
    static int compareVersions(String left, String right) {
        List<String> a = tokenize(left);
        List<String> b = tokenize(right);
        int length = Math.max(a.size(), b.size());
        for (int index = 0; index < length; index++) {
            int comparison = compareToken(
                    index < a.size() ? a.get(index) : null,
                    index < b.size() ? b.get(index) : null);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareToken(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -signum(b);
        }
        if (b == null) {
            return signum(a);
        }
        boolean numericA = isNumeric(a), numericB = isNumeric(b);
        if (numericA && numericB) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (numericA) {
            return 1;
        }
        if (numericB) {
            return -1;
        }
        int rankA = qualifierRank(a), rankB = qualifierRank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        return rankA == UNKNOWN_QUALIFIER ? a.compareTo(b) : 0;
    }

    /** The sign of a token against its empty baseline: a number against zero, a qualifier against release. */
    private static int signum(String token) {
        return isNumeric(token)
                ? new BigInteger(token).signum()
                : Integer.compare(qualifierRank(token), RELEASE_QUALIFIER);
    }

    private static final int RELEASE_QUALIFIER = 6;
    private static final int UNKNOWN_QUALIFIER = 8;

    private static int qualifierRank(String qualifier) {
        return switch (qualifier) {
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "milestone", "m" -> 3;
            case "rc", "cr" -> 4;
            case "snapshot" -> 5;
            case "", "ga", "final", "release" -> RELEASE_QUALIFIER;
            case "sp" -> 7;
            default -> UNKNOWN_QUALIFIER;
        };
    }

    private static boolean isNumeric(String token) {
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            // ASCII digits only: new BigInteger rejects the non-ASCII digits Character.isDigit accepts.
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private static List<String> tokenize(String version) {
        String lower = version.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Boolean digit = null;
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (character == '.' || character == '-' || character == '_') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                digit = null;
                continue;
            }
            boolean isDigit = Character.isDigit(character);
            if (digit != null && isDigit != digit && current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            current.append(character);
            digit = isDigit;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
