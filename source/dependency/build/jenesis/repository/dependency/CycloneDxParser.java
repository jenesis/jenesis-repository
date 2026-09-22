package build.jenesis.repository.dependency;

import module java.base;
import module java.xml;
import module tools.jackson.databind;

/**
 * Parses a CycloneDX 1.x BOM - the SBOM standard a Jenesis build embeds in every jar - into the format-neutral
 * {@link DependencyGraph}. Both serialisations are read: JSON with the Jackson databind already on the server path
 * and XML with the JDK's {@code java.xml} (a library each, not a hand-rolled reader), the same two the free
 * {@code CycloneDx} emitter writes. The {@code metadata.component} becomes the graph root, each {@code components}
 * entry a node keyed by its {@code bom-ref} (falling back to its {@code purl}), and each {@code dependencies}
 * relationship an edge.
 *
 * <p>A BOM is a small metadata document, so it is materialised whole (the streaming principle expressly allows an
 * index/metadata parse) - but only up to {@link #MAX_DOCUMENT}, the XML reader is hardened against DOCTYPE and
 * external-entity (XXE) attacks, and the recursive XML dependency walk is bounded at {@link #MAX_NESTING} so a
 * pathologically deep nesting cannot overflow the stack - all because the document rides inside an untrusted
 * uploaded or proxied artifact. Any document that is neither JSON nor XML, is truncated, is malformed, or nests
 * beyond the cap yields {@link DependencyGraph#EMPTY} rather than throwing, so a broken SBOM never fails the read
 * path that scans it.
 */
public final class CycloneDxParser {

    private CycloneDxParser() {
    }

    /** A CycloneDX document is small; a larger blob is not a BOM we will hold whole in heap. */
    public static final int MAX_DOCUMENT = 32 * 1024 * 1024;

    /** The deepest {@code <dependency>} nesting the XML dependency walk descends before a document is refused as
     *  pathological. A real resolved dependency tree nests tens of levels; a document nesting a few thousand
     *  {@code <dependency>} deep is not a BOM but a crafted stack-overflow vector - the recursion would otherwise
     *  throw a {@link StackOverflowError} that, unlike a parse error, escapes the caller's {@code IOException}
     *  handling and wedges the whole reverse-dependency sweep on that one blob forever. Bounding the walk here (the
     *  sibling depth-cap idiom - {@code Lifecycle.walk}, {@code RepositoryRouter.route} - rather than an unbounded
     *  recursion) turns that overflow into the ordinary {@link DependencyGraph#EMPTY} any malformed BOM yields. */
    public static final int MAX_NESTING = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SHA_256 = "SHA-256";

    /** Parse a BOM read from {@code in} (up to {@link #MAX_DOCUMENT} bytes), auto-detecting JSON vs XML. */
    public static DependencyGraph parse(InputStream in) throws IOException {
        byte[] document = in.readNBytes(MAX_DOCUMENT);
        if (in.read() != -1) {
            return DependencyGraph.EMPTY;      // larger than any real BOM - refuse rather than buffer it
        }
        return parse(document);
    }

    /** Parse a BOM held in {@code document}, auto-detecting JSON (<code>&#123;</code>) vs XML ({@code <}) from its first token.
     *  Fail-soft: a document that is truncated, malformed, or nests beyond the cap yields {@link DependencyGraph#EMPTY}
     *  rather than throwing, so the reverse-dependency sweep that scans every blob is never derailed by one bad BOM. */
    public static DependencyGraph parse(byte[] document) {
        try {
            return parse(document, false);
        } catch (MalformedSbomException _) {
            return DependencyGraph.EMPTY;      // unreachable in lenient mode, but keeps the signature exception-free
        }
    }

    /** Like {@link #parse(byte[])} but reports a present-but-unparseable BOM distinctly: it throws {@link
     *  MalformedSbomException} when the document announces JSON or XML (its first token is <code>&#123;</code>, {@code [} or
     *  {@code <}) but does not decode, or its XML dependency graph nests past {@link #MAX_NESTING} - so a served view
     *  can render a scoped "could not derive this SBOM" error rather than the empty graph a genuinely absent SBOM
     *  yields. A document that is not a BOM at all (neither JSON nor XML, or valid JSON/XML that carries no components)
     *  still returns a (possibly empty) graph: that is a genuine negative, not a failure. The fail-soft {@link
     *  #parse(byte[])} the sweep relies on is unchanged. */
    public static DependencyGraph parseStrict(byte[] document) throws MalformedSbomException {
        return parse(document, true);
    }

    private static DependencyGraph parse(byte[] document, boolean strict) throws MalformedSbomException {
        int start = document.length >= 3
                && (document[0] & 0xFF) == 0xEF && (document[1] & 0xFF) == 0xBB && (document[2] & 0xFF) == 0xBF
                ? 3 : 0;                       // skip a leading UTF-8 byte-order mark
        for (int index = start; index < document.length; index++) {
            byte b = document[index];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            }
            if (b == '{' || b == '[') {
                return parseJson(document, strict);
            }
            if (b == '<') {
                return parseXml(document, strict);
            }
            return DependencyGraph.EMPTY;      // not a BOM we recognise - a genuine negative, not a parse failure
        }
        return DependencyGraph.EMPTY;          // empty / all-whitespace
    }

    private static DependencyGraph parseJson(byte[] document, boolean strict) throws MalformedSbomException {
        JsonNode bom;
        try {
            bom = MAPPER.readTree(document);
        } catch (RuntimeException cause) {
            if (strict) {
                throw new MalformedSbomException("CycloneDX JSON BOM is not readable JSON", cause);
            }
            return DependencyGraph.EMPTY;
        }
        if (bom == null || !bom.isObject()) {
            return DependencyGraph.EMPTY;
        }
        SequencedMap<String, DependencyComponent> byRef = new LinkedHashMap<>();
        String rootRef = null;
        JsonNode metadata = bom.get("metadata");
        if (metadata != null) {
            DependencyComponent root = jsonComponent(metadata.get("component"));
            if (root != null) {
                rootRef = root.ref();
                byRef.put(root.ref(), root);
            }
        }
        JsonNode components = bom.get("components");
        if (components != null && components.isArray()) {
            for (JsonNode node : components) {
                DependencyComponent component = jsonComponent(node);
                if (component != null) {
                    byRef.putIfAbsent(component.ref(), component);
                }
            }
        }
        List<DependencyEdge> edges = new ArrayList<>();
        JsonNode dependencies = bom.get("dependencies");
        if (dependencies != null && dependencies.isArray()) {
            for (JsonNode dependency : dependencies) {
                String from = jsonText(dependency, "ref");
                JsonNode dependsOn = dependency == null ? null : dependency.get("dependsOn");
                if (from == null || dependsOn == null || !dependsOn.isArray()) {
                    continue;
                }
                for (JsonNode on : dependsOn) {
                    String to = on == null || on.isNull() ? null : on.asString();
                    if (to != null && !to.isBlank()) {
                        edges.add(new DependencyEdge(from, to));
                    }
                }
            }
        }
        return new DependencyGraph(rootRef, new ArrayList<>(byRef.values()), edges);
    }

    private static DependencyComponent jsonComponent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String purl = jsonText(node, "purl");
        String ref = jsonText(node, "bom-ref");
        if (ref == null) {
            ref = purl;                        // a BOM without an explicit bom-ref refers to a component by its purl
        }
        if (ref == null) {
            return null;                       // no stable identity - it cannot be an edge endpoint
        }
        return new DependencyComponent(ref,
                jsonText(node, "group"), jsonText(node, "name"), jsonText(node, "version"), purl, jsonSha256(node),
                jsonLicenses(node));
    }

    /**
     * The component's declared licences. CycloneDX wraps each entry in a {@code licenses} array as either
     * {@code {"license":{"id":...}}}, {@code {"license":{"name":...,"url":...}}} or {@code {"expression":"..."}}; all
     * three are read, an entry carrying none of them is dropped, and an absent or malformed {@code licenses} node is
     * simply no licences - a BOM never fails to parse over its licence block, since the graph is what the
     * reverse-dependency sweep needs and the licences ride along for the gate.
     */
    private static List<DependencyLicense> jsonLicenses(JsonNode node) {
        JsonNode licenses = node.get("licenses");
        if (licenses == null || !licenses.isArray()) {
            return List.of();
        }
        List<DependencyLicense> declared = new ArrayList<>();
        for (JsonNode entry : licenses) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            String expression = jsonText(entry, "expression");
            DependencyLicense license;
            if (expression != null) {
                license = DependencyLicense.of(expression);
            } else {
                JsonNode wrapped = entry.get("license");
                JsonNode source = wrapped != null && wrapped.isObject() ? wrapped : entry;
                String id = jsonText(source, "id");
                license = id != null
                        ? DependencyLicense.of(id)
                        : DependencyLicense.named(jsonText(source, "name"), jsonText(source, "url"));
            }
            if (!license.isEmpty()) {
                declared.add(license);
            }
        }
        return List.copyOf(declared);
    }

    private static String jsonSha256(JsonNode node) {
        JsonNode hashes = node.get("hashes");
        if (hashes == null || !hashes.isArray()) {
            return null;
        }
        for (JsonNode hash : hashes) {
            if (SHA_256.equalsIgnoreCase(jsonText(hash, "alg"))) {
                return jsonText(hash, "content");
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

    private static DependencyGraph parseXml(byte[] document, boolean strict) throws MalformedSbomException {
        Element bom;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            harden(factory);
            Document parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
            bom = parsed.getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException cause) {
            if (strict) {
                throw new MalformedSbomException("CycloneDX XML BOM is not readable XML", cause);
            }
            return DependencyGraph.EMPTY;
        }
        if (bom == null || !"bom".equals(bom.getLocalName())) {
            return DependencyGraph.EMPTY;
        }
        SequencedMap<String, DependencyComponent> byRef = new LinkedHashMap<>();
        String rootRef = null;
        Element metadata = firstChild(bom, "metadata");
        if (metadata != null) {
            DependencyComponent root = xmlComponent(firstChild(metadata, "component"));
            if (root != null) {
                rootRef = root.ref();
                byRef.put(root.ref(), root);
            }
        }
        Element components = firstChild(bom, "components");
        if (components != null) {
            for (Element node : children(components, "component")) {
                DependencyComponent component = xmlComponent(node);
                if (component != null) {
                    byRef.putIfAbsent(component.ref(), component);
                }
            }
        }
        List<DependencyEdge> edges = new ArrayList<>();
        Element dependencies = firstChild(bom, "dependencies");
        if (dependencies != null) {
            try {
                for (Element dependency : children(dependencies, "dependency")) {
                    collectXmlEdges(dependency, edges, 0);
                }
            } catch (IOException cause) {
                if (strict) {                  // nesting past MAX_NESTING - a crafted overflow vector, not a real BOM
                    throw new MalformedSbomException("CycloneDX XML BOM nests past " + MAX_NESTING + " levels", cause);
                }
                return DependencyGraph.EMPTY;
            }
        }
        return new DependencyGraph(rootRef, new ArrayList<>(byRef.values()), edges);
    }

    /** Collect the edges of one {@code <dependency>} subtree. CycloneDX's XML dependency graph nests recursively - a
     *  {@code <dependency ref="A">} may contain {@code <dependency ref="B">} which itself contains
     *  {@code <dependency ref="C">} - and each nested element is both an edge from its parent AND, in turn, a parent
     *  of its own children. Reading only one level (parent -> direct children) silently drops every grandchild edge a
     *  third-party emitter that nests the full resolved tree produces, so the whole nesting is walked: an edge is
     *  recorded from each node to each of its immediate {@code <dependency>} children, then each child is recursed
     *  into. (The flat form - a leaf {@code <dependency ref>} with no children, mirroring the JSON {@code dependsOn}
     *  shape - is just the base case with no grandchildren.)
     *
     *  <p>The recursion is bounded at {@link #MAX_NESTING}: the document rides inside an untrusted artifact, so a
     *  pathological nesting a few thousand deep would otherwise overflow the stack. Past the cap the walk throws a
     *  bounded {@code IOException} that {@link #parseXml} turns into {@link DependencyGraph#EMPTY} - the same
     *  outcome any malformed BOM yields - rather than a {@link StackOverflowError} that escapes the read path. */
    private static void collectXmlEdges(Element dependency, List<DependencyEdge> edges, int depth) throws IOException {
        if (depth > MAX_NESTING) {
            throw new IOException("CycloneDX dependency nesting exceeds " + MAX_NESTING + " levels");
        }
        String from = attribute(dependency, "ref");
        if (from == null) {
            return;
        }
        for (Element on : children(dependency, "dependency")) {
            String to = attribute(on, "ref");
            if (to != null) {
                edges.add(new DependencyEdge(from, to));
            }
            collectXmlEdges(on, edges, depth + 1);   // a nested <dependency> is itself a parent of its own children
        }
    }

    private static DependencyComponent xmlComponent(Element node) {
        if (node == null) {
            return null;
        }
        String purl = childText(node, "purl");
        String ref = attribute(node, "bom-ref");
        if (ref == null) {
            ref = purl;
        }
        if (ref == null) {
            return null;
        }
        return new DependencyComponent(ref,
                childText(node, "group"), childText(node, "name"), childText(node, "version"), purl, xmlSha256(node),
                xmlLicenses(node));
    }

    /** The XML mirror of {@link #jsonLicenses}: {@code <licenses>} holding {@code <license><id>|<name>|<url></license>}
     *  elements or a bare {@code <expression>}. */
    private static List<DependencyLicense> xmlLicenses(Element node) {
        Element licenses = firstChild(node, "licenses");
        if (licenses == null) {
            return List.of();
        }
        List<DependencyLicense> declared = new ArrayList<>();
        for (Element expression : children(licenses, "expression")) {
            DependencyLicense license = DependencyLicense.of(text(expression));
            if (!license.isEmpty()) {
                declared.add(license);
            }
        }
        for (Element license : children(licenses, "license")) {
            String id = childText(license, "id");
            DependencyLicense parsed = id != null
                    ? DependencyLicense.of(id)
                    : DependencyLicense.named(childText(license, "name"), childText(license, "url"));
            if (!parsed.isEmpty()) {
                declared.add(parsed);
            }
        }
        return List.copyOf(declared);
    }

    private static String text(Element element) {
        String content = element.getTextContent();
        return content == null || content.isBlank() ? null : content.trim();
    }

    private static String xmlSha256(Element node) {
        Element hashes = firstChild(node, "hashes");
        if (hashes == null) {
            return null;
        }
        for (Element hash : children(hashes, "hash")) {
            if (SHA_256.equalsIgnoreCase(attribute(hash, "alg"))) {
                String content = hash.getTextContent();
                return content == null || content.isBlank() ? null : content.trim();
            }
        }
        return null;
    }

    private static void harden(DocumentBuilderFactory factory) throws ParserConfigurationException {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
    }

    private static Element firstChild(Element parent, String localName) {
        for (Element child : children(parent, localName)) {
            return child;
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String childText(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }
}
