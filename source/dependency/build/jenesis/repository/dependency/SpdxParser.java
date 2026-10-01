package build.jenesis.repository.dependency;

import module java.base;
import module tools.jackson.databind;

/**
 * Parses an SPDX 2.x SBOM into the format-neutral {@link DependencyGraph}, the counterpart to {@link CycloneDxParser}.
 * The JSON serialisation is read with Jackson and the tag-value one with a small bounded line reader. The package the
 * document {@code DESCRIBES} becomes the root, each {@code packages} entry a node keyed by its {@code SPDXID} (its
 * {@code purl} external reference as the coordinate), and each {@code DEPENDS_ON} / {@code DEPENDENCY_OF} relationship
 * an edge.
 *
 * <p>Materialised whole up to {@link #MAX_DOCUMENT}. Neither serialisation nests, so there is no stack to overflow; a
 * multi-line {@code <text>} block in tag-value is skipped whole, so a crafted field cannot inject a tag. A document
 * that is neither JSON nor tag-value, is truncated or malformed yields {@link DependencyGraph#EMPTY}.
 */
public final class SpdxParser {

    private SpdxParser() {
    }

    /** The largest SPDX document held whole in heap - the CycloneDX ceiling, so both parsers share one. */
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

    /** Parse a BOM held in {@code document}, detecting the JSON (<code>&#123;</code>) or tag-value serialisation. */
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

    /** The Maven group SPDX carries only inside a {@code maven-central} external ref's locator
     *  ({@code group:name:version}), so the component keys the same {@link DependencyComponent#coordinate()}
     *  {@link CycloneDxParser} gives it and the reverse-dependency shard key does not diverge. The group prefix when
     *  the locator is exactly {@code <group>:<name>:<version>} for the parsed name and version, else {@code null}. */
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

    /** Parse the tag-value serialisation: {@code Tag: value} lines grouped into package blocks from
     *  {@code PackageName:}, with edges on {@code Relationship:} lines. A {@code <text>} value left open on its line is
     *  skipped through to its {@code </text>}, so a multi-line field neither spills into the next tag nor smuggles
     *  one. */
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

    /** Record a completed package block as a component keyed by its {@code SPDXID} (else its purl). Returns
     *  {@code null} so the caller resets its accumulator. */
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

    /** Fold one relationship into the graph, for both serialisations: {@code DESCRIBES} from the document names the
     *  root, {@code DEPENDS_ON} is an edge as written, {@code DEPENDENCY_OF} the same edge reversed, anything else
     *  ignored. Returns the possibly updated root ref. */
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
