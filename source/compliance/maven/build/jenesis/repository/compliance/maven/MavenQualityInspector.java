package build.jenesis.repository.compliance.maven;

import module java.base;
import module org.slf4j;
import module java.xml;
import module tools.jackson.databind;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.IncompleteScreenException;
import build.jenesis.repository.compliance.Maintainer;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.CycloneDxParser;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.DependencyLicense;
import build.jenesis.repository.xml.Xml;

/**
 * The JVM quality inspector for the two JVM layouts: {@code .pom} and {@code .jar} under {@code /maven/...} and jars
 * under the module layout's {@code /module/...}. It reads the coordinate from the path and the declared licences from
 * the artifact's declaration sources, and for a POM yields a subject per dependency in the resolved closure as well, so
 * the shared {@link ComplianceGate} catches a disallowed licence or a known vulnerability anywhere in the tree. Each
 * subject is placed on the dependency graph ({@link BuildGraphReachability}), the artifact as root.
 *
 * <h2>The declaration sources, in order</h2>
 * A Maven artifact can declare its licences six ways, and they do not always agree, so {@link #ownLicenses} takes the
 * <em>first source that declares anything</em> rather than a union, which would let a stale document add a licence the
 * publisher never claimed:
 * <ol>
 *   <li>the artifact's own {@code <licenses>}, for a {@code .pom};</li>
 *   <li>for a jar, its <b>sibling POM</b>'s {@code <licenses>}, the coordinate's canonical declaration;</li>
 *   <li>for a jar, the <b>POM embedded in it</b> at {@code META-INF/maven/<groupId>/<artifactId>/pom.xml}, the same
 *       document carried inside;</li>
 *   <li>the <b>sibling CycloneDX attachment</b> {@code <artifact>-<version>-cyclonedx.json} (or {@code .xml}), read
 *       through {@code lookup.fetch};</li>
 *   <li>the <b>embedded</b> CycloneDX copy the {@code Sbom-Location} header names (or {@code META-INF/sbom/}), located
 *       and bounded by {@link ArtifactSbom};</li>
 *   <li>the jar's OSGi {@code Bundle-License} header.</li>
 * </ol>
 *
 * <p>A CycloneDX document renders the POM's facts and can lag a POM edited at release, so it ranks below; it records
 * licences per component with SPDX identifiers, so it ranks above the single free-text {@code Bundle-License}. Every
 * SBOM read is optional: absent, over the bound, past a prefix, or unparsable, it declares nothing and the next source
 * answers. None fails a publish.
 *
 * <h2>The closure: declared first, resolved second</h2>
 * For a POM the closure is read hermetically from the sibling CycloneDX attachment when one is stored, which lists
 * every component with its purl, version and licences. Only otherwise is it resolved over the network
 * ({@link ClosureResolution}), the SPI's one read-purity exception, through the repository an operator named and within
 * a bound. An unresolved closure yields no transitive subjects rather than blocking the publish, and is counted.
 *
 * <h2>Gradle Module Metadata ({@code .module})</h2>
 * Gradle publishes a JSON descriptor beside the POM, which Gradle consumers prefer. It is claimed so it is screened
 * under the same coordinate as its POM and jar, but it feeds nothing into licence or dependency derivation:
 * <ol>
 *   <li>its schema has no licence field, so its licences come from the sibling POM, as a jar's do;</li>
 *   <li>{@code variants[].dependencies} are variant-scoped, chosen by the consumer's attributes, and a publish-time
 *       screen has no consumer: a union would hold publishes over dependencies no consumer resolves, one variant would
 *       hide real vulnerabilities, and the consumer-independent closure sources stay the answer;</li>
 *   <li>capabilities express conflicts for resolution, which nothing in the gate keys on.</li>
 * </ol>
 *
 * <p>An unreadable descriptor - unparsable, an unread format version, or a {@code component} disagreeing with its path
 * - costs only the derived declaration and is no {@code MalformedArtifactException}, since the coordinate comes from
 * the path. Withholding it would be worse: Gradle would silently resolve the POM's variant instead, while a corrupt one
 * served makes Gradle fail the build loudly. The failure is logged and left visible to the client.
 *
 * <h2>The Jenesis module layout</h2>
 * A modular jar published under the Maven layout is cross-published into {@code /module/} over the same blob and
 * screened once, here, under its Maven coordinate. A jar published directly to {@code /module/} has no coordinate or
 * sibling POM, so its embedded CycloneDX document is its only declaration, which a Jenesis build always emits. Module
 * subjects carry the {@code Jenesis} ecosystem the module layout reports.
 */
public final class MavenQualityInspector implements QualityInspector {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenQualityInspector.class);

    /** The advisory namespace Maven coordinates report. */
    private static final String ECOSYSTEM = "Maven";

    /** The ecosystem the module layout reports, read from the shared Java-layout grammar rather than a format
     *  implementation. */
    private static final String MODULE_ECOSYSTEM = JavaLayout.MODULE_ECOSYSTEM;

    private static final String MAVEN_ROUTE = JavaLayout.MAVEN_ROUTE;

    private static final String MODULE_ROUTE = JavaLayout.MODULE_ROUTE;

    /** Gradle Module Metadata's extension: the descriptor published beside the POM at
     *  {@code <artifact>-<version>.module}, under the Maven coordinate its path yields. */
    private static final String GRADLE_MODULE_METADATA = ".module";

    /** The Gradle Module Metadata major this inspector reads: 1.0 and 1.1 are what Gradle publishes and the schema is
     *  additive within a major. A version outside it costs only the derived declaration. */
    private static final String GRADLE_MODULE_MAJOR = "1.";

    /** The parser for the publisher-authored {@code .module} JSON. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The attachment a Jenesis build publishes beside the pom and jar, in both serialisations; the first stored and
     *  parsable wins. */
    private static final List<String> SBOM_ATTACHMENTS = List.of("-cyclonedx.json", "-cyclonedx.xml");

    /** The Maven descriptor suffix, for {@link JavaLayout#attachment}. */
    private static final String POM = ".pom";

    /** The ceiling on a sibling declaration read into heap: the shared manifest tier,
     *  {@link ArchiveInflation#largestEntry()}. Read through {@link QualityInspector.Lookup#fetchBounded}, since
     *  {@code fetch} throws past the gateway's sibling cap and would fail a publish over an optional document; past
     *  this the source declares nothing. */
    private static int sbomLimit() {
        return ArchiveInflation.largestEntry();
    }

    /** One ranked declaration source, so {@link #ownLicenses}'s precedence is a literal list. */
    @FunctionalInterface
    private interface Declaration {
        ManifestSubjectBuilder read() throws IOException;
    }

    /** A truncated SBOM attachment leaves this inspection incomplete: what it would have declared is unknown, not
     *  absent. */
    @Override
    public boolean incompleteOnTruncatedSibling() {
        return true;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(MAVEN_ROUTE)
                && (path.endsWith(".pom") || path.endsWith(".jar") || path.endsWith(GRADLE_MODULE_METADATA))
                || path.startsWith(MODULE_ROUTE) && path.endsWith(".jar");
    }

    /** A published POM completes its own version directory. Maven deploys the jar first, so a jar declaring no licence
     *  of its own, as a Gradle-built jar does, is screened while the coordinate's licence document is in flight, and
     *  the POM arriving is the evidence that was missing. Only a {@code .pom} answers: licences flow from the POM to
     *  its siblings. */
    @Override
    public Optional<String> completes(String path) {
        if (!path.startsWith(MAVEN_ROUTE) || !path.endsWith(".pom")) {
            return Optional.empty();
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? Optional.empty() : Optional.of(path.substring(0, slash + 1));
    }

    @Override
    public List<ComplianceGate.Subject> inspect(String path, byte[] content, QualityInspector.Lookup lookup)
            throws IOException {
        List<ComplianceGate.Subject> subjects = new ArrayList<>(inspectArtifact(path, content, lookup));
        String[] coordinate = coordinate(path);
        if (!subjects.isEmpty() && coordinate != null && path.endsWith(".pom")) {
            Optional<DependencyGraph> declared = siblingSbom(path, coordinate, lookup);
            List<ComplianceGate.Subject> sbom = declared.map(MavenQualityInspector::declaredClosure).orElse(List.of());
            if (!sbom.isEmpty()) {
                subjects.addAll(sbom);
                return subjects;
            }
            ClosureResolution.Resolved resolved = ClosureResolution.graph(path, content, coordinate, lookup);
            resolved.graph().map(MavenQualityInspector::declaredClosure).ifPresent(subjects::addAll);
            if (resolved.incomplete().isPresent() && resolved.verdict() != Verdict.ALLOW) {
                throw new IncompleteScreenException(resolved.incomplete().get(), resolved.verdict(), subjects);
            }
        }
        return subjects;
    }

    /** A jar is read as a stream wherever a screen offers one: its descriptor sits wherever the packager wrote it, so
     *  unlike every other container a bounded prefix is a guess rather than a convention. */
    @Override
    public boolean streams() {
        return true;
    }

    /** The streamed leg, for a jar. A {@code .pom} or {@code .module} is small by nature, so it takes the SPI's bridge
     *  and reports that bridge's read; the module-view route is bridged since its coordinate comes from the path. */
    @Override
    public QualityInspector.Inspection inspectArtifact(String path, QualityInspector.Content body,
                                                       QualityInspector.Lookup lookup) throws IOException {
        if (path.startsWith(MODULE_ROUTE) || path.endsWith(".pom") || path.endsWith(GRADLE_MODULE_METADATA)) {
            return QualityInspector.super.inspectArtifact(path, body, lookup);
        }
        String[] coordinate = coordinate(path);
        if (coordinate == null) {
            return QualityInspector.Inspection.complete(List.of());
        }
        Archive archive = Archive.streamed(body);
        String canonical = coordinate[0] + ":" + coordinate[1];
        List<ComplianceGate.Subject> subjects = jarLicenses(path, archive, coordinate, lookup)
                .maintainers(maintainers(path, archive, null, coordinate, lookup))
                .subject(canonical, coordinate[2], ComplianceGate.Reachability.root(canonical + ":" + coordinate[2]));
        // A walk the full-body tier stopped may have passed the descriptor, so an empty licence list is "we stopped
        // looking", which the screen must hear.
        return new QualityInspector.Inspection(subjects, !archive.cut());
    }

    @Override
    public List<ComplianceGate.Subject> inspectArtifact(String path, byte[] content, QualityInspector.Lookup lookup)
            throws IOException {
        Archive archive = Archive.bounded(content);
        if (path.startsWith(MODULE_ROUTE)) {
            String[] module = JavaLayout.moduleCoordinate(path);
            return module == null
                    ? List.of()      // a claimed path that is not a module-view publish route declares nothing
                    : embeddedSbom(archive, MODULE_ECOSYSTEM)
                            .orElseGet(() -> bundle(archive, MODULE_ECOSYSTEM))
                            .subject(module[0], module[1],
                                    ComplianceGate.Reachability.root(module[0] + ":" + module[1]));
        }
        String[] coordinate = coordinate(path);
        if (coordinate == null) {
            return List.of();
        }
        String canonical = coordinate[0] + ":" + coordinate[1];
        if (path.endsWith(GRADLE_MODULE_METADATA)) {
            gradleModuleMetadata(path, content)
                    .filter(declared -> !declared[0].equals(canonical) || !declared[1].equals(coordinate[2]))
                    .ifPresent(declared -> LOGGER.warn("The Gradle Module Metadata descriptor {} declares component "
                            + "{}:{} while its path names {}:{}. Gradle refuses a descriptor whose component "
                            + "disagrees with the coordinate it was resolved under, so this publish will fail every "
                            + "Gradle consumer; it is screened, stored and served under its PATH coordinate, which "
                            + "is the one an eviction, a hold and a deny-list all key on",
                            path, declared[0], declared[1], canonical, coordinate[2]));
        }
        ManifestSubjectBuilder declared = ownLicenses(path, archive, content, coordinate, lookup)
                .maintainers(maintainers(path, archive, content, coordinate, lookup));
        if (path.endsWith(".pom")) {
            declared = aboutFromPom(dependenciesFromPom(declared, content), content);
        }
        return declared.subject(canonical, coordinate[2],
                ComplianceGate.Reachability.root(canonical + ":" + coordinate[2]));
    }

    /** What a POM declares the artifact depends on: its own {@code <dependencies>}, never
     *  {@code <dependencyManagement>}, as {@code group:artifact} and the stated version, possibly a range or property.
     *  Test, provided, system and optional dependencies are not brought in by depending on it. Only the POM's own
     *  publish records them. */
    private static ManifestSubjectBuilder dependenciesFromPom(ManifestSubjectBuilder declared, byte[] pom) {
        try {
            Element project = Xml.parse(pom).getDocumentElement();
            ManifestSubjectBuilder read = declared.readsDependencies();
            for (Element dependencies : children(project, "dependencies")) {
                for (Element dependency : children(dependencies, "dependency")) {
                    String scope = text(dependency, "scope");
                    if (scope != null && !scope.equals("compile") && !scope.equals("runtime")
                            || "true".equals(text(dependency, "optional"))) {
                        continue;
                    }
                    String group = text(dependency, "groupId");
                    String artifact = text(dependency, "artifactId");
                    if (group != null && artifact != null) {
                        read = read.dependency(group + ":" + artifact, text(dependency, "version"));
                    }
                }
            }
            return read;
        } catch (SAXException | IOException | RuntimeException unreadable) {
            return declared;         // a POM that does not parse declares nothing this can read
        }
    }

    /** What a POM says the artifact is for: its {@code <description>}, else its {@code <name>}; Maven has no
     *  keywords. */
    private static ManifestSubjectBuilder aboutFromPom(ManifestSubjectBuilder declared, byte[] pom) {
        try {
            Element project = Xml.parse(pom).getDocumentElement();
            String description = children(project, "description").stream().map(Node::getTextContent)
                    .filter(text -> !text.isBlank()).findFirst()
                    .orElseGet(() -> children(project, "name").stream().map(Node::getTextContent).findFirst()
                            .orElse(null));
            return declared.about(description, List.of());
        } catch (SAXException | IOException | RuntimeException unreadable) {
            return declared;
        }
    }

    /** The direct child elements of {@code parent} named {@code name}. */
    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                children.add(element);
            }
        }
        return children;
    }

    /** Whom the POM names: its {@code developers} and {@code contributors} - name, e-mail, a GitHub login from a
     *  profile url - and the owner of its {@code scm} repository on GitHub, in the two spellings a key is looked up by
     *  ({@link Maintainer}). The POM is taken in the licences' order, and one absent or unparsable names nobody rather
     *  than failing the publish. */
    private static List<Maintainer> maintainers(String path, Archive archive, byte[] own, String[] coordinate,
                                                QualityInspector.Lookup lookup) throws IOException {
        Optional<byte[]> pom = path.endsWith(".pom")
                ? Optional.ofNullable(own)      // the artifact IS the POM, and only a leg holding its bytes has it
                : lookup.fetch(JavaLayout.attachment(path, POM))
                        .or(() -> path.endsWith(GRADLE_MODULE_METADATA)
                                ? Optional.empty()
                                : embeddedPom(archive, coordinate));
        return pom.map(MavenQualityInspector::maintainersFromPom).orElse(List.of());
    }

    private static List<Maintainer> maintainersFromPom(byte[] pom) {
        try {
            Document document = Xml.parse(pom);
            List<Maintainer> named = new ArrayList<>();
            for (String tag : List.of("developer", "contributor")) {
                NodeList people = document.getElementsByTagName(tag);
                for (int index = 0; index < people.getLength(); index++) {
                    Element person = (Element) people.item(index);
                    named.add(new Maintainer(text(person, "name"), text(person, "email"),
                            Maintainer.githubLogin(text(person, "url")).orElse(null)));
                }
            }
            NodeList scms = document.getElementsByTagName("scm");
            for (int index = 0; index < scms.getLength(); index++) {
                Element scm = (Element) scms.item(index);
                for (String tag : List.of("url", "connection", "developerConnection")) {
                    Optional<Maintainer> owner = Maintainer.ofRepository(text(scm, tag));
                    if (owner.isPresent()) {
                        named.add(owner.get());
                        break;
                    }
                }
            }
            return named;
        } catch (Exception _) {
            return List.of();   // as the licence: the coordinate is the path's, and an unreadable POM names nobody
        }
    }

    /** The licences the artifact declares, from the first source in the documented order that declares anything; every
     *  source is optional, so an absent, unparsable or over-bound one declares nothing. */
    private static ManifestSubjectBuilder ownLicenses(String path, Archive archive, byte[] own,
                                                      String[] coordinate,
                                                      QualityInspector.Lookup lookup) throws IOException {
        if (path.endsWith(GRADLE_MODULE_METADATA)) {
            // The descriptor has no licence field, so the sibling POM is the first source, and the archive rungs do not
            // apply to JSON.
            return firstDeclaring(List.of(
                    () -> lookup.fetch(JavaLayout.attachment(path, POM))
                            .map(MavenQualityInspector::licensesFromPom)
                            .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM)),
                    () -> siblingSbom(path, coordinate, lookup)
                            .flatMap(graph -> rootLicenses(graph, ECOSYSTEM))
                            .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM))));
        }
        if (path.endsWith(".pom")) {
            return firstDeclaring(List.of(
                    () -> licensesFromPom(own),
                    () -> siblingSbom(path, coordinate, lookup)
                            .flatMap(graph -> rootLicenses(graph, ECOSYSTEM))
                            .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM))));
        }
        return jarLicenses(path, archive, coordinate, lookup);
    }

    /** The order a jar's licence is looked for in, shared by both legs: the sibling POM, the embedded descriptor, the
     *  sibling SBOM, the embedded SBOM, then {@code Bundle-License}. The legs differ only in how far they read. */
    private static ManifestSubjectBuilder jarLicenses(String path, Archive archive, String[] coordinate,
                                                      QualityInspector.Lookup lookup) throws IOException {
        return firstDeclaring(List.of(
                () -> lookup.fetch(JavaLayout.attachment(path, POM))
                        .map(MavenQualityInspector::licensesFromPom)
                        .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM)),
                () -> embeddedPom(archive, coordinate)
                        .map(MavenQualityInspector::licensesFromPom)
                        .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM)),
                () -> siblingSbom(path, coordinate, lookup)
                        .flatMap(graph -> rootLicenses(graph, ECOSYSTEM))
                        .orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM)),
                () -> embeddedSbom(archive, ECOSYSTEM).orElseGet(() -> ManifestSubjectBuilder.of(ECOSYSTEM)),
                () -> bundle(archive, ECOSYSTEM)));
    }

    /** The first source in the list that declares anything, else "declares nothing". */
    private static ManifestSubjectBuilder firstDeclaring(List<Declaration> sources) throws IOException {
        for (Declaration source : sources) {
            ManifestSubjectBuilder declared = source.read();
            if (!declared.declared().isEmpty()) {
                return declared;
            }
        }
        return ManifestSubjectBuilder.of(ECOSYSTEM);
    }

    /**
     * Read a {@code .module} far enough to know it is Gradle Module Metadata for the coordinate its path names, logging
     * and never throwing when it is not; the class comment says why its variants are not folded in and why an
     * unreadable one is no hold.
     *
     * @return the {@code group:artifact} and version it declares, or empty when it is no readable Gradle Module
     *     Metadata
     */
    private static Optional<String[]> gradleModuleMetadata(String path, byte[] content) {
        try {
            JsonNode root = JSON.readTree(content);
            String format = root.path("formatVersion").asString(null);
            if (format == null || !format.startsWith(GRADLE_MODULE_MAJOR)) {
                LOGGER.warn("The Gradle Module Metadata descriptor {} declares formatVersion '{}', which this "
                        + "repository does not read; it is stored and served byte-for-byte all the same, so Gradle "
                        + "decides what to make of it", path, format);
                return Optional.empty();
            }
            JsonNode component = root.path("component");
            String group = component.path("group").asString(null);
            String module = component.path("module").asString(null);
            String version = component.path("version").asString(null);
            if (group == null || module == null || version == null) {
                LOGGER.warn("The Gradle Module Metadata descriptor {} names no complete component coordinate; it is "
                        + "stored and served byte-for-byte all the same", path);
                return Optional.empty();
            }
            return Optional.of(new String[]{group + ":" + module, version});
        } catch (RuntimeException unreadable) {
            LOGGER.warn("The Gradle Module Metadata descriptor {} is not readable JSON. It is stored and served "
                    + "byte-for-byte all the same: Gradle refuses a descriptor it cannot parse and fails the build "
                    + "loudly, where withholding it would leave the POM serving and silently change which variant "
                    + "resolves", path, unreadable);
            return Optional.empty();
        }
    }

    /** Every dependency the SBOM resolved, as gate subjects: its Maven coordinate and version, its own licences, and
     *  its shortest path on the declared graph. */
    private static List<ComplianceGate.Subject> declaredClosure(DependencyGraph graph) {
        Map<String, ComplianceGate.Reachability> reachability = BuildGraphReachability.of(graph);
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        for (DependencyComponent component : graph.dependencies()) {
            String[] coordinate = mavenCoordinate(component);
            if (coordinate == null) {
                continue;      // a non-Maven component in a JVM artifact's BOM is not this gate's ecosystem
            }
            subjects.addAll(licenses(ManifestSubjectBuilder.of(ECOSYSTEM), component)
                    .subject(coordinate[0], coordinate[1],
                            reachability.getOrDefault(component.ref(), ComplianceGate.Reachability.UNKNOWN)));
        }
        return subjects;
    }

    /** The CycloneDX attachment beside this artifact, parsed: read through {@link QualityInspector.Lookup#fetchBounded}
     *  so an oversized one declares nothing rather than throwing, and parsed fail-soft. */
    private static Optional<DependencyGraph> siblingSbom(String path, String[] coordinate,
                                                         QualityInspector.Lookup lookup) throws IOException {
        for (String suffix : SBOM_ATTACHMENTS) {
            Optional<QualityInspector.Lookup.Bounded> attachment =
                    lookup.fetchBounded(JavaLayout.attachment(path, suffix), sbomLimit());
            if (attachment.isEmpty() || attachment.get().truncated()) {
                // Absent and over the bound differ: the second leaves the inspection incomplete, reported through the
                // bridge below.
                continue;
            }
            DependencyGraph graph = CycloneDxParser.parse(attachment.get().content());
            if (!graph.isEmpty()) {
                return Optional.of(graph);
            }
        }
        return Optional.empty();
    }

    /** The licences the jar's embedded CycloneDX document declares, located through {@code Sbom-Location} (or
     *  {@code META-INF/sbom/}) and bounded by {@link ArtifactSbom}. Only on the complete artifact, since a prefix short
     *  of the entry would read as "carries none". A failure declares nothing. */
    private static Optional<ManifestSubjectBuilder> embeddedSbom(Archive archive, String ecosystem) {
        if (!archive.whole()) {
            return Optional.empty();
        }
        try (InputStream artifact = archive.open()) {
            return ArtifactSbom.graph(artifact).flatMap(graph -> rootLicenses(graph, ecosystem));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * The POM a jar carries at {@code META-INF/maven/<groupId>/<artifactId>/pom.xml}, or empty.
     *
     * <p>A Maven deploy sends the jar before the POM, so when the jar is screened its sibling POM is not stored yet;
     * the embedded descriptor is the same declaration already in hand, so the common case screens correctly with no
     * ordering. The entry is matched against the request path's coordinate, so a jar cannot claim another coordinate's
     * descriptor. Walked under {@link ArchiveWalk#largestWalk()} and inflated at the shared entry tier, and only on the
     * complete artifact, as {@link #embeddedSbom} is; a failure declares nothing.
     */
    private static Optional<byte[]> embeddedPom(Archive archive, String[] coordinate) {
        if (!archive.whole()) {
            return Optional.empty();
        }
        String descriptor = "META-INF/maven/" + coordinate[0] + "/" + coordinate[1] + "/pom.xml";
        try (InputStream jar = archive.open()) {
            ArchiveWalk.Found<byte[]> found = ArchiveWalk.walk(jar, archive.ceiling(),
                    screened -> descriptorEntry(screened, descriptor));
            archive.note(found);
            return Optional.ofNullable(found.orNull());
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /** The named descriptor entry of a bounded jar stream, or null when absent. */
    private static byte[] descriptorEntry(InputStream jar, String descriptor) throws IOException {
        try (ZipInputStream zip = ArchiveWalk.zip(jar)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().equals(descriptor)) {
                    // Optional: a descriptor the ceiling stopped declares nothing, never a prefix.
                    return ArchiveInflation.entry(zip).orNull();
                }
            }
            return null;
        }
    }

    /** The licences the BOM's {@code metadata.component}, the artifact itself, declares. */
    private static Optional<ManifestSubjectBuilder> rootLicenses(DependencyGraph graph, String ecosystem) {
        return graph.root()
                .map(root -> licenses(ManifestSubjectBuilder.of(ecosystem), root))
                .filter(declared -> !declared.declared().isEmpty());
    }

    /** A component's CycloneDX licences on a subject builder: an SPDX {@code id} or expression as an identifier, else
     *  the free-text {@code name}/{@code url} pair. */
    private static ManifestSubjectBuilder licenses(ManifestSubjectBuilder builder, DependencyComponent component) {
        ManifestSubjectBuilder declared = builder;
        for (DependencyLicense license : component.licenses()) {
            declared = license.id() == null || license.id().isBlank()
                    ? declared.license(license.name(), license.url())
                    : declared.license(license.id());
        }
        return declared;
    }

    /** Without the whole-artifact guard: the manifest is a jar's first member, so a prefix reaching it read the real
     *  header. */
    private static ManifestSubjectBuilder bundle(Archive archive, String ecosystem) {
        ComplianceGate.DeclaredLicense declared;
        try (InputStream jar = archive.open()) {
            declared = ComplianceGate.bundleLicense(jar, archive.ceiling());
        } catch (IOException _) {
            declared = null;
        }
        return declared == null
                ? ManifestSubjectBuilder.of(ecosystem)
                : ManifestSubjectBuilder.of(ecosystem).license(declared.name(), declared.url());
    }

    /** What a rung may read and how far: the body, whether it is the whole artifact, and the walk's ceiling. A bounded
     *  leg holds a prefix walked at the flat tier, a streamed leg the artifact at the full-body tier. A rung that must
     *  not conclude from a prefix asks {@link #whole()}; one that walks asks {@link #ceiling()} and reports through
     *  {@link #note}, since a walk the ceiling stopped means "we stopped looking". */
    private static final class Archive {

        private final BoundedBodyReader.Source body;

        private final boolean whole;

        private final long ceiling;

        private boolean cut;

        private Archive(BoundedBodyReader.Source body, boolean whole, long ceiling) {
            this.body = body;
            this.whole = whole;
            this.ceiling = ceiling;
        }

        static Archive bounded(byte[] content) {
            return new Archive(BoundedBodyReader.Source.of(content),
                    BoundedBodyReader.completeArtifact(content), ArchiveWalk.largestWalk());
        }

        static Archive streamed(QualityInspector.Content body) {
            return new Archive(BoundedBodyReader.Source.of(body), true,
                    QualityInspector.fullBodyInspectionLimit());
        }

        InputStream open() throws IOException {
            return body.open();
        }

        boolean whole() {
            return whole;
        }

        long ceiling() {
            return ceiling;
        }

        void note(ArchiveWalk.Found<?> found) {
            cut |= found.truncated();
        }

        boolean cut() {
            return cut;
        }
    }

    /** The {@code [groupId, artifactId, version]} of a Maven path, by the layout's own split. */
    private static String[] coordinate(String path) {
        return JavaLayout.mavenCoordinate(path);
    }


    /** A component's Maven {@code group:artifact} and version: the purl preferred, the split fields as fallback. A
     *  component that is neither yields {@code null} and is skipped, rather than querying feeds for a package that does
     *  not exist. */
    private static String[] mavenCoordinate(DependencyComponent component) {
        String purl = component.purl();
        if (purl != null && purl.startsWith("pkg:maven/")) {
            String body = purl.substring("pkg:maven/".length());
            int qualifier = body.indexOf('?'), fragment = body.indexOf('#');
            int end = qualifier < 0 ? fragment : fragment < 0 ? qualifier : Math.min(qualifier, fragment);
            if (end >= 0) {
                body = body.substring(0, end);
            }
            int at = body.lastIndexOf('@'), slash = body.indexOf('/');
            if (at > 0 && slash > 0 && slash < at && body.indexOf('/', slash + 1) < 0) {
                return new String[]{
                        decode(body.substring(0, slash)) + ":" + decode(body.substring(slash + 1, at)),
                        decode(body.substring(at + 1))};
            }
            return null;
        }
        if (purl != null) {
            return null;       // an explicitly non-Maven component
        }
        String group = component.group(), name = component.name(), version = component.version();
        if (group == null || group.isBlank() || name == null || name.isBlank()
                || version == null || version.isBlank()) {
            return null;
        }
        return new String[]{group.trim() + ":" + name.trim(), version.trim()};
    }

    private static String decode(String segment) {
        return segment.indexOf('%') < 0 ? segment : URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static ManifestSubjectBuilder licensesFromPom(byte[] pom) {
        try {
            Document document = Xml.parse(pom);
            NodeList nodes = document.getElementsByTagName("license");
            ManifestSubjectBuilder licenses = ManifestSubjectBuilder.of(ECOSYSTEM);
            for (int index = 0; index < nodes.getLength(); index++) {
                Element license = (Element) nodes.item(index);
                licenses = licenses.license(text(license, "name"), text(license, "url"));
            }
            return licenses;
        } catch (Exception _) {
            // The coordinate comes from the path and the POM is read for licences only, so an unparsable POM costs the
            // licences, not the coordinate, and is no MalformedArtifactException.
            return ManifestSubjectBuilder.of(ECOSYSTEM);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
