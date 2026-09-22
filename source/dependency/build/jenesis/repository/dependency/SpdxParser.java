package build.jenesis.repository.dependency;

import module java.base;
import module tools.jackson.databind;

/**
 * Parses an SPDX 2.x SBOM into the format-neutral {@link DependencyGraph}, the SPDX counterpart to
 * {@link CycloneDxParser}: a jar produced by an SPDX-emitting tool contributes reverse-dependency edges too, instead
 * of silently contributing none. Both serialisations a third-party tool embeds are read - the JSON serialisation
 * with the Jackson databind already on the server path (a library, not a hand-rolled reader) and the classic
 * tag-value serialisation with a small bounded line reader, the two an ecosystem scanner actually ships. The SPDX
 * package the document {@code DESCRIBES} becomes the graph root, each {@code packages} entry a node keyed by its
 * {@code SPDXID} (its {@code purl} external reference carried through as the coordinate), and each
 * {@code DEPENDS_ON} / {@code DEPENDENCY_OF} relationship an edge.
 *
 * <p>The same bounded-parse discipline the CycloneDX walk holds to: a BOM is a small metadata document, so it is
 * materialised whole (the streaming principle expressly allows an index/metadata parse) - but only up to
 * {@link #MAX_DOCUMENT}, the identical 32&nbsp;MiB cap {@link CycloneDxParser#MAX_DOCUMENT} sets, because the
 * document rides inside an untrusted uploaded or proxied artifact. Neither serialisation recurses (the JSON
 * {@code relationships} and tag-value {@code Relationship:} forms are flat), so there is no nesting to overflow the
 * stack the way the CycloneDX XML dependency tree could; a {@code <text>} block that spans lines in the tag-value
 * form is skipped whole, so a crafted multi-line field cannot inject a fake tag. Any document that is neither JSON
 * nor a recognisable tag-value stream, is truncated, or is malformed yields {@link DependencyGraph#EMPTY} rather
 * than throwing, so a broken SBOM never fails the read path that scans it.
 */
public final class SpdxParser {

    private SpdxParser() {
    }

    /** An SPDX document is small; a larger blob is not a BOM we will hold whole in heap. Kept identical to
     *  {@link CycloneDxParser#MAX_DOCUMENT} so the two SBOM parsers share one document ceiling. */
    public static final int MAX_DOCUMENT = CycloneDxParser.MAX_DOCUMENT;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPDX_DOCUMENT = "SPDXRef-DOCUMENT";
    private static final String SHA_256 = "SHA256";        // SPDX spells it without the dash CycloneDX uses
    private static final String PURL = "purl";
    private static final String MAVEN_CENTRAL = "maven-central";   // the ref type the SPDX writer emits for a purl-less GAV
    private static final String DESCRIBES = "DESCRIBES";
    private static final String DEPENDS_ON = "DEPENDS_ON";
    private static final String DEPENDENCY_OF = "DEPENDENCY_OF";

    /** Parse a BOM read from {@code in} (up to {@link #MAX_DOCUMENT} bytes), auto-detecting JSON vs tag-value. */
    public static DependencyGraph parse(InputStream in) throws IOException {
        byte[] document = in.readNBytes(MAX_DOCUMENT);
        if (in.read() != -1) {
            return DependencyGraph.EMPTY;      // larger than any real BOM - refuse rather than buffer it
        }
        return parse(document);
    }

    /** Parse a BOM held in {@code document}, auto-detecting the JSON (<code>&#123;</code>) vs tag-value serialisation. */
    public static DependencyGraph parse(byte[] document) {
        int start = document.length >= 3
                && (document[0] & 0xFF) == 0xEF && (document[1] & 0xFF) == 0xBB && (document[2] & 0xFF) == 0xBF
                ? 3 : 0;                       // skip a leading UTF-8 byte-order mark
        for (int index = start; index < document.length; index++) {
            byte b = document[index];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            }
            if (b == '{' || b == '[') {
                return parseJson(document);
            }
            return parseTagValue(document, start);
        }
        return DependencyGraph.EMPTY;          // empty / all-whitespace
    }

    private static DependencyGraph parseJson(byte[] document) {
        JsonNode spdx;
        try {
            spdx = MAPPER.readTree(document);
        } catch (RuntimeException _) {
            return DependencyGraph.EMPTY;
        }
        if (spdx == null || !spdx.isObject()) {
            return DependencyGraph.EMPTY;
        }
        SequencedMap<String, DependencyComponent> byRef = new LinkedHashMap<>();
        JsonNode packages = spdx.get("packages");
        if (packages != null && packages.isArray()) {
            for (JsonNode node : packages) {
                DependencyComponent component = jsonPackage(node);
                if (component != null) {
                    byRef.putIfAbsent(component.ref(), component);
                }
            }
        }
        String rootRef = null;
        List<DependencyEdge> edges = new ArrayList<>();
        JsonNode relationships = spdx.get("relationships");
        if (relationships != null && relationships.isArray()) {
            for (JsonNode relationship : relationships) {
                String from = jsonText(relationship, "spdxElementId");
                String type = jsonText(relationship, "relationshipType");
                String to = jsonText(relationship, "relatedSpdxElement");
                if (from == null || type == null || to == null) {
                    continue;
                }
                rootRef = fold(rootRef, edges, from, type, to);
            }
        }
        if (rootRef == null) {
            JsonNode describes = spdx.get("documentDescribes");   // the array form, when no DESCRIBES relationship
            if (describes != null && describes.isArray()) {
                for (JsonNode described : describes) {
                    String ref = described == null || described.isNull() ? null : described.asString();
                    if (ref != null && !ref.isBlank() && byRef.containsKey(ref)) {
                        rootRef = ref;
                        break;
                    }
                }
            }
        }
        return new DependencyGraph(rootRef, new ArrayList<>(byRef.values()), edges);
    }

    private static DependencyComponent jsonPackage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String ref = jsonText(node, "SPDXID");
        String purl = jsonRef(node, PURL);
        if (ref == null) {
            ref = purl;                        // a package without an SPDXID is identified by its purl external ref
        }
        if (ref == null) {
            return null;                       // no stable identity - it cannot be an edge endpoint
        }
        String name = jsonText(node, "name");
        String version = jsonText(node, "versionInfo");
        return new DependencyComponent(ref,
                mavenGroup(jsonRef(node, MAVEN_CENTRAL), name, version), name, version, purl, jsonSha256(node));
    }

    private static String jsonRef(JsonNode node, String type) {
        JsonNode references = node.get("externalRefs");
        if (references == null || !references.isArray()) {
            return null;
        }
        for (JsonNode reference : references) {
            if (type.equalsIgnoreCase(jsonText(reference, "referenceType"))) {
                return jsonText(reference, "referenceLocator");
            }
        }
        return null;
    }

    /** The Maven group SPDX carries only inside a {@code maven-central} external ref's locator - the colon-joined
     *  {@code group:name:version} the SPDX writer emits for a purl-less coordinate, so the parsed component keys the
     *  same {@link DependencyComponent#coordinate()} {@link CycloneDxParser} does (which reads a native {@code group}
     *  element) instead of dropping the group and diverging the reverse-dependency shard key. Returns the group prefix
     *  when the locator is exactly {@code <group>:<name>:<version>} for the already-parsed name and version, else
     *  {@code null} - a locator that only repeats {@code name:version} (the whole-repository form whose name already
     *  carries the group) leaves the component unchanged. */
    private static String mavenGroup(String locator, String name, String version) {
        if (locator == null || name == null || version == null) {
            return null;
        }
        String suffix = ':' + name + ':' + version;
        if (locator.endsWith(suffix) && locator.length() > suffix.length()) {
            return locator.substring(0, locator.length() - suffix.length());
        }
        return null;
    }

    private static String jsonSha256(JsonNode node) {
        JsonNode checksums = node.get("checksums");
        if (checksums == null || !checksums.isArray()) {
            return null;
        }
        for (JsonNode checksum : checksums) {
            if (SHA_256.equalsIgnoreCase(jsonText(checksum, "algorithm"))) {
                return jsonText(checksum, "checksumValue");
            }
        }
        return null;
    }

    private static String jsonText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isEmpty() ? null : text;
    }

    /** Parse the tag-value serialisation: a flat stream of {@code Tag: value} lines a package block ({@code
     *  PackageName:} onward) groups by, with the graph edges carried by {@code Relationship:} lines. A {@code <text>}
     *  value that opens without closing on its own line is skipped through to its {@code </text>}, so a multi-line
     *  copyright/comment field can neither spill into the next tag nor smuggle a forged one. */
    private static DependencyGraph parseTagValue(byte[] document, int start) {
        SequencedMap<String, DependencyComponent> byRef = new LinkedHashMap<>();
        List<DependencyEdge> edges = new ArrayList<>();
        String rootRef = null;
        Package current = null;                // the package block being accumulated, or null in the document header
        boolean inText = false;                // inside a multi-line <text> ... </text> value - tags here are skipped
        for (String line : new String(document, start, document.length - start, StandardCharsets.UTF_8).split("\n")) {
            if (inText) {
                if (line.contains("</text>")) {
                    inText = false;
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String tag = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("<text>") && !value.contains("</text>")) {
                inText = true;                 // a value that opens a multi-line block - swallow it whole
                continue;
            }
            switch (tag) {
                case "PackageName" -> {
                    current = flush(current, byRef);
                    current = new Package();
                    current.name = value.isEmpty() ? null : value;
                }
                case "SPDXID" -> {
                    if (current != null) {
                        current.ref = value;   // a package's own id; before the first PackageName it is the document's
                    }
                }
                case "PackageVersion" -> {
                    if (current != null) {
                        current.version = value.isEmpty() ? null : value;
                    }
                }
                case "PackageChecksum" -> {
                    if (current != null) {
                        int inner = value.indexOf(':');
                        if (inner > 0 && SHA_256.equalsIgnoreCase(value.substring(0, inner).trim())) {
                            String hex = value.substring(inner + 1).trim();
                            current.sha256 = hex.isEmpty() ? null : hex;
                        }
                    }
                }
                case "ExternalRef" -> {
                    if (current != null) {
                        String[] parts = value.split("\\s+", 3);
                        if (parts.length == 3) {
                            if (PURL.equalsIgnoreCase(parts[1])) {
                                current.purl = parts[2].isBlank() ? null : parts[2].trim();
                            } else if (MAVEN_CENTRAL.equalsIgnoreCase(parts[1])) {
                                current.mavenRef = parts[2].isBlank() ? null : parts[2].trim();
                            }
                        }
                    }
                }
                case "Relationship" -> {
                    String[] parts = value.split("\\s+", 3);
                    if (parts.length == 3) {
                        rootRef = fold(rootRef, edges, parts[0], parts[1], parts[2]);
                    }
                }
                default -> {
                }
            }
        }
        flush(current, byRef);
        return new DependencyGraph(rootRef, new ArrayList<>(byRef.values()), edges);
    }

    /** Record a completed package block as a component, keyed by its {@code SPDXID} (or its purl when it declared no
     *  id). Returns {@code null} - the caller resets its accumulator for the next {@code PackageName:}. */
    private static Package flush(Package current, SequencedMap<String, DependencyComponent> byRef) {
        if (current == null) {
            return null;
        }
        String ref = current.ref;
        if (ref == null) {
            ref = current.purl;                // a package with no SPDXID is identified by its purl external ref
        }
        if (ref != null && !ref.isBlank()) {
            byRef.putIfAbsent(ref, new DependencyComponent(ref,
                    mavenGroup(current.mavenRef, current.name, current.version),
                    current.name, current.version, current.purl, current.sha256));
        }
        return null;
    }

    /** Fold one relationship into the graph, shared by both serialisations: a {@code DESCRIBES} from the document
     *  names the graph root; a {@code DEPENDS_ON} is an edge as written; a {@code DEPENDENCY_OF} is the same edge
     *  reversed (SPDX's inverse form); anything else is not a dependency edge and is ignored. Returns the (possibly
     *  updated) root ref. */
    private static String fold(String rootRef, List<DependencyEdge> edges, String from, String type, String to) {
        switch (type) {
            case DESCRIBES -> {
                if (rootRef == null && SPDX_DOCUMENT.equals(from) && !to.isBlank()) {
                    return to;
                }
            }
            case DEPENDS_ON -> {
                if (!from.isBlank() && !to.isBlank()) {
                    edges.add(new DependencyEdge(from, to));
                }
            }
            case DEPENDENCY_OF -> {
                if (!from.isBlank() && !to.isBlank()) {
                    edges.add(new DependencyEdge(to, from));   // "A DEPENDENCY_OF B" means B depends on A
                }
            }
            default -> {
            }
        }
        return rootRef;
    }

    /** A tag-value package block accumulated across its lines before it is folded into a {@link DependencyComponent}. */
    private static final class Package {
        private String ref;
        private String name;
        private String version;
        private String purl;
        private String mavenRef;               // the maven-central external ref locator, when the block declared one
        private String sha256;
    }
}
