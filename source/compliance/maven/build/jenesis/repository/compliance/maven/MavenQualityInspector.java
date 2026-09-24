package build.jenesis.repository.compliance.maven;

import module java.base;
import module org.slf4j;
import module java.xml;
import module tools.jackson.databind;
import build.jenesis.Environment;
import build.jenesis.Make;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenRepository;
import build.jenesis.maven.MavenDependencyKey;
import build.jenesis.maven.MavenDependencyScope;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenResolver;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.compliance.BoundedBodyReader;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Maintainer;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.CycloneDxParser;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.DependencyLicense;

/**
 * The JVM quality inspector: the publishing quality gate for the two JVM layouts, as a plugin of its own. It claims
 * {@code .pom} and {@code .jar} uploads under {@code /maven/...} and jars under the Jenesis module layout's
 * {@code /module/...}, reads the coordinate from the path and the declared licenses from the artifact's declaration
 * sources, and - for a POM - yields a compliance subject for each dependency in the resolved closure as well as for
 * the artifact itself. The shared {@link ComplianceGate} then assesses every subject, so a disallowed license or a
 * known vulnerability anywhere in the tree is caught at ingestion, not just in the artifact itself. Each subject is
 * marked with where it sits on the dependency graph ({@link BuildGraphReachability}): the artifact is the root, and
 * every dependency carries the shortest dependency path from the artifact to it, so a finding can say <em>how</em> -
 * and how directly - a vulnerable component is reachable.
 *
 * <h2>The declaration sources, in order</h2>
 * A Maven artifact can declare its licences six ways and they do not always agree, so the order is explicit, is a
 * literal list in {@link #ownLicenses}, and takes the <em>first source that declares anything</em> rather than
 * merging them - a union would let a stale or generated document add a licence the publisher never claimed, turning
 * a permitted artifact into a held one:
 *
 * <ol>
 *   <li>the artifact's own {@code <licenses>}, for a {@code .pom};</li>
 *   <li>for a jar, its <b>sibling POM</b>'s {@code <licenses>} - the ecosystem's canonical declaration for the
 *       coordinate, written by the publisher and the document every other Maven consumer reads;</li>
 *   <li>for a jar, the <b>POM embedded in the jar itself</b> at {@code META-INF/maven/<groupId>/<artifactId>/pom.xml},
 *       which {@code maven-archiver} writes by default - the same document as the rung above, carried inside the
 *       artifact, so it ranks directly below it and above every derived one;</li>
 *   <li>the <b>sibling CycloneDX attachment</b> {@code <artifact>-<version>-cyclonedx.json} (or {@code .xml}) that a
 *       Jenesis build publishes beside the pom and jar, read through the very same {@code lookup.fetch} seam and so
 *       needing no archive read at all;</li>
 *   <li>the <b>embedded</b> CycloneDX copy at the path the jar's {@code Sbom-Location} manifest header names
 *       (falling back to the {@code META-INF/sbom/} convention), located and bounded by {@link ArtifactSbom};</li>
 *   <li>the jar's single-string OSGi {@code Bundle-License} header.</li>
 * </ol>
 *
 * <p><b>Why the SBOM ranks below the POM and above {@code Bundle-License}.</b> The POM is the coordinate's canonical
 * metadata document: it is what the publisher wrote, what Maven and Gradle read, and what Central indexes - a
 * CycloneDX document is a build tool's <em>rendering</em> of that same fact and can lag a POM edited at release time,
 * so it must not overrule it. It outranks {@code Bundle-License} decisively in the other direction: CycloneDX records
 * licences <em>per component</em> and names them with SPDX identifiers where the emitter matched one, where
 * {@code Bundle-License} is one free-text OSGi header describing the bundle, frequently absent and never
 * policy-comparable.
 *
 * <p><b>Every SBOM read is optional-degrading</b>, exactly as settled for the POM: for Maven the archive is not
 * the manifest source (the coordinate comes from the request path), so an attachment that is not there, one past the
 * bounded-read ceiling, a jar whose prefix does not reach its SBOM entry, and a document that will not parse all
 * degrade to "this source declares nothing" and hand over to the next one. None of them fails a publish.
 *
 * <h2>The closure: declared first, resolved second</h2>
 * For a POM the dependency closure is taken from the sibling CycloneDX attachment when one is published - the
 * document already lists every resolved component with its purl, its version and its own licences, so the closure is
 * read <b>hermetically</b>, out of the store, with no network at all. Only when no such document is stored does the
 * inspector fall back to resolving the closure over the network through the Jenesis Maven resolver
 * ({@link MavenDefaultRepository}), the SPI's single declared read-purity exception. That fallback is unchanged, and
 * remains best-effort: a network or unresolvable-dependency failure yields no transitive subjects rather than
 * blocking the publish.
 *
 * <h2>Gradle Module Metadata ({@code .module})</h2>
 * Gradle publishes a JSON descriptor beside the POM from version 6 onward, and every Gradle consumer of that
 * coordinate reads it in preference to the POM. It is claimed here rather than left un-inspected, so the descriptor is
 * screened under the same Maven coordinate as its POM and its jar - the operator deny-list, the immaturity hold and
 * the advisory dimensions all bite on it - instead of streaming through as unclaimed content the way it used to.
 *
 * <h3>What the descriptor feeds, and what it does not</h3>
 * It feeds <b>nothing</b> into licence or dependency derivation. That is a decision, not an omission, and it rests on
 * three facts:
 * <ol>
 *   <li><b>The licence axis has nothing to gain.</b> Gradle Module Metadata declares no licence: the schema has no
 *       field for one. The POM remains the coordinate's canonical declaration, and the ranked list above is
 *       unchanged; a {@code .module} simply takes its licences from its sibling POM, which is the very rung a jar
 *       already uses.</li>
 *   <li><b>Its dependency data is not a property of the artifact.</b> {@code variants[].dependencies} are
 *       <em>variant-scoped</em>, and which variant applies is decided by the <em>consumer's</em> requested attributes
 *       and capabilities. A publish-time screen has no consumer. Folding them in would therefore mean either unioning
 *       every variant - holding a publish over a dependency no consumer of that artifact will ever resolve, a false
 *       hold that breaks publishers - or picking one arbitrarily, which is an under-screen that hides a real
 *       vulnerability. Both are the §9 wrong-answer shape, in opposite directions. The two closure sources this
 *       inspector already has (the sibling CycloneDX attachment, then the resolver) are consumer-independent and stay
 *       the answer.</li>
 *   <li><b>Capabilities are a resolution concept, not a compliance one.</b> They express which components conflict
 *       with which; nothing in the gate keys on them.</li>
 * </ol>
 * Revisit this only if a <em>resolve-time</em> gate ever exists - there, and only there, the consumer's attributes are
 * known and a variant can be named.
 *
 * <h3>Why an unreadable descriptor is not a hold</h3>
 * A {@code .module} that will not parse, that declares a format version this repository does not read, or whose
 * {@code component} disagrees with its path costs only the derived declaration: the path coordinate still screens, and
 * the bytes are still stored and served. It is therefore <b>not</b> a {@code MalformedArtifactException}, for the same
 * reason an unparseable POM is not (the coordinate comes from the path, not the body) - and for one further reason
 * specific to this file. Withholding a {@code .module} is not a safe default: its POM keeps serving, and Gradle then
 * <em>silently</em> resolves the POM's variant instead of the descriptor's, which is a wrong answer with no error
 * anywhere. Serving a broken descriptor, by contrast, makes Gradle refuse it and fail the build loudly. Measured with
 * the real client: a missing {@code .module} changed the resolved artifact and the resolved dependency set with a
 * {@code BUILD SUCCESSFUL}, while a corrupt one failed the build outright. So a repository must never turn "this
 * descriptor is broken" into "this descriptor is absent"; the failure is logged here and left visible to the client.
 *
 * <h2>The Jenesis module layout</h2>
 * A modular jar published under the Maven layout is cross-published into the {@code /module/} view over the same
 * content-addressed blob, so it is screened once, here, under its Maven coordinate. A jar <em>published directly</em>
 * to {@code /module/} has no Maven coordinate and no sibling POM at all, and its embedded CycloneDX document is
 * therefore its <b>only</b> declaration source - which is exactly the artifact class that always carries one, since
 * the Jenesis build emits it by default. Both JVM layouts are owned by this one inspector for the same reason the
 * {@code build.jenesis.repository.format.jvm} module describes both: they are one ecosystem's two addressing
 * conventions, not two ecosystems. Module subjects carry the {@code Jenesis} ecosystem the module layout's own
 * {@code ArtifactLayout} reports, so a screened coordinate and a served one are looked up under the same name.
 */
public final class MavenQualityInspector implements QualityInspector {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenQualityInspector.class);

    private static final String PREFIX = "dep";

    /** The advisory-feed namespace Maven coordinates report - what the feeds and the gate key on for JVM artifacts. */
    private static final String ECOSYSTEM = "Maven";

    /** The ecosystem the Jenesis module layout reports for a module-view coordinate.
     *
     *  <p>Read from the shared Java-layout module rather than spelled here: the edge is to the layout
     *  <em>grammar</em> ({@code format.java}, which requires only the store and format SPIs) rather than to a format
     *  implementation, so a describing module does not take a compile-time edge to the layout it describes. */
    private static final String MODULE_ECOSYSTEM = JavaLayout.MODULE_ECOSYSTEM;

    private static final String MAVEN_ROUTE = JavaLayout.MAVEN_ROUTE;

    private static final String MODULE_ROUTE = JavaLayout.MODULE_ROUTE;

    /** Gradle Module Metadata's file extension - the JSON descriptor Gradle publishes <em>beside</em> the POM, at
     *  {@code <artifact>-<version>.module}. Not a layout of its own: it lives in the Maven layout, under the Maven
     *  coordinate the request path already yields. */
    private static final String GRADLE_MODULE_METADATA = ".module";

    /** The Gradle Module Metadata format versions this inspector reads. The document declares its own
     *  {@code formatVersion}; 1.0 and 1.1 are the versions Gradle publishes, and the schema is additive within a
     *  major, so the major is what is checked. Reading it is not a gate: a version outside the range costs only the
     *  derived declaration, and the bytes are served either way. */
    private static final String GRADLE_MODULE_MAJOR = "1.";

    /** The parser for the {@code .module} descriptor. A maintained JSON library rather than a hand-rolled reader
     *  (§8) - the document is publisher-authored JSON reaching a screening path. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The Maven attachment a Jenesis build publishes beside the pom and jar, in both serialisations the emitter
     *  writes. Tried in this order; the first that is stored and parses wins. */
    private static final List<String> SBOM_ATTACHMENTS = List.of("-cyclonedx.json", "-cyclonedx.xml");

    /** The Maven descriptor suffix, for {@link JavaLayout#attachment}. */
    private static final String POM = ".pom";

    /** The ceiling on a sibling declaration document read into heap. A CycloneDX BOM for a real closure is tens to a
     *  few hundred kilobytes; the shared manifest tier is already the product's answer to "a small metadata document
     *  beside the artifact", so this reads it live off {@link ArchiveInflation#largestEntry()} - the same number, and
     *  the same operator key, as the members a format inflates - rather than restating it. Reading it through
     *  {@link QualityInspector.Lookup#fetchBounded} rather than {@code fetch} matters: {@code fetch} <em>throws</em>
     *  past the gateway's sibling cap, which would turn an oversized optional declaration into a failed publish. Past
     *  this bound the source declares nothing. */
    private static int sbomLimit() {
        return ArchiveInflation.largestEntry();
    }

    /** One ranked declaration source. Ordered attempts are expressed as a list of these so {@link #ownLicenses}'s
     *  precedence is a literal, readable sequence rather than a nest of conditionals. */
    @FunctionalInterface
    private interface Declaration {
        ManifestSubjectBuilder read() throws IOException;
    }

    /** A truncated SBOM attachment leaves this inspection incomplete: the dependency and licence facts that
     *  declaration would have carried are unknown, not absent, and the attachment read is bounded separately from
     *  the artifact body the bridge's own test covers. */
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

    /**
     * A published POM completes its own version directory. Maven's deploy is several requests and the jar goes first,
     * so a jar with no licence of its own - no {@code <licenses>} reachable, no embedded descriptor, no SBOM, no
     * {@code Bundle-License}, which is what a Gradle-built jar looks like - is screened while the coordinate's only
     * licence document is still in flight. The POM arriving is the evidence that was missing, and the version
     * directory is where its neighbours are.
     *
     * <p>Only a {@code .pom} answers. A jar completes nothing: the licence flows from the POM to its siblings, never
     * the other way, and having the jar re-assess the POM would be a re-decision of unchanged evidence.
     */
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
            subjects.addAll(closure(path, content, coordinate, lookup));
        }
        return subjects;
    }

    /**
     * It reads a jar as a stream wherever a screen offers one, and the reason is where a jar keeps its descriptor.
     *
     * <p>Every other format this product screens puts its declaration at a place the container fixes - a
     * {@code .nuspec} at the zip root, a gem's metadata as the first tar member, a wheel's {@code METADATA}. A jar
     * has no such rule: {@code META-INF/maven/<group>/<artifact>/pom.xml} is written wherever the packager wrote
     * it, and on a large artifact that can be anywhere at all. Twenty-one formats read a licence out of an
     * archive, and this is the one where a bounded prefix is a guess rather than a convention.
     */
    @Override
    public boolean streams() {
        return true;
    }

    /**
     * The streamed leg, for the artifacts it can reach further into: a jar.
     *
     * <p>A descriptor - a {@code .pom}, a {@code .module} - is a small document by its own nature, and one past the
     * inspection prefix is pathological rather than large; there is nothing in it an archive walk could reach that a
     * bounded read cannot, so it takes the SPI's bridge and reports the read that bridge made. The module-view route
     * is bridged for the same reason its coordinate comes from the path.
     */
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
        // A walk the full-body tier stopped may have passed the descriptor without reading it, so an empty licence
        // list over this artifact is "we stopped looking" rather than "it declares nothing", and the screen behind
        // this leg must hear the difference.
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
        return ownLicenses(path, archive, content, coordinate, lookup)
                .maintainers(maintainers(path, archive, content, coordinate, lookup))
                .subject(canonical, coordinate[2], ComplianceGate.Reachability.root(canonical + ":" + coordinate[2]));
    }

    /**
     * Whom the POM names: its {@code developers} and {@code contributors} - name, e-mail, and a profile url that
     * is a GitHub login - and the owner of the repository its {@code scm} points at when that is GitHub, in the two
     * spellings a key can be looked up by ({@link Maintainer}). The POM is the artifact's own for a {@code .pom},
     * the deployed sibling for a jar or a Gradle descriptor, else the descriptor the jar carries - the same order
     * the licence takes - and, being optional beside a coordinate the path yields, a POM that is not there or will
     * not parse names nobody rather than failing the publish.
     */
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
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(pom));
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

    /**
     * The licences the artifact itself declares, taken from the first source in the documented order that declares
     * anything. Each is an OPTIONAL declaration beside a coordinate the request path already yields, so a sibling
     * that is not there, a document that will not parse, one past the bounded read and a {@code Bundle-License} that
     * is not present all degrade to "declares nothing" rather than failing the publish.
     */
    private static ManifestSubjectBuilder ownLicenses(String path, Archive archive, byte[] own,
                                                      String[] coordinate,
                                                      QualityInspector.Lookup lookup) throws IOException {
        if (path.endsWith(GRADLE_MODULE_METADATA)) {
            // The descriptor itself declares no licence - the Gradle Module Metadata schema has no field for one - so the
            // sibling POM is the FIRST source, not the second, and the two archive rungs below (an embedded CycloneDX
            // document, an OSGi Bundle-License header) have nothing to read in a JSON document and are left out
            // rather than run against bytes that can never be an archive.
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

    /** The order a JAR's licence is looked for in, which both legs take: the deployed sibling POM, the descriptor the
     *  jar carries, the sibling SBOM attachment, the SBOM the jar carries, and the OSGi {@code Bundle-License}
     *  header. Stated once because the two legs differ in how far they may read into the archive and in nothing
     *  else - a second copy of this list is how they would come to disagree about where a licence comes from. */
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

    /** The first source in the list that declares anything, else "declares nothing" - the shared walk of a ranked
     *  declaration list, so the {@code .module}, {@code .pom} and jar orders stay three literal lists rather than
     *  three copies of this loop. */
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
     * Read a {@code .module} descriptor far enough to know it really is Gradle Module Metadata for the coordinate its
     * path names, logging - never throwing - when it is not. This is the whole of what parsing the descriptor is
     * <em>for</em>: see the class documentation's "What the descriptor feeds, and what it does not" for why its
     * variants and capabilities are deliberately not folded into licence or dependency derivation, and
     * "Why an unreadable descriptor is not a hold" for why this returns quietly instead of raising
     * {@code MalformedArtifactException} the way an inspector whose coordinate comes from the body does.
     *
     * @return the {@code group:artifact} and version the document declares for itself, or empty when it is not a
     *         readable Gradle Module Metadata document
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

    /**
     * The dependency closure, declared-first: the sibling CycloneDX attachment when one is stored - a hermetic read
     * through the store, no network - and only otherwise the resolver's network walk. Landing the SBOM leg behind the
     * document's presence is what keeps the network path the fallback rather than removing it: the overwhelming
     * majority of Maven artifacts carry no attachment at all, and for them nothing about this inspector changes.
     */
    private static List<ComplianceGate.Subject> closure(String path, byte[] pom, String[] coordinate,
                                                        QualityInspector.Lookup lookup) throws IOException {
        Optional<DependencyGraph> declared = siblingSbom(path, coordinate, lookup);
        if (declared.isPresent()) {
            List<ComplianceGate.Subject> subjects = declaredClosure(declared.get());
            if (!subjects.isEmpty()) {
                return subjects;
            }
        }
        return transitive(pom);
    }

    /** Every dependency the SBOM already resolved, as gate subjects: the component's Maven coordinate and version,
     *  the licences <em>it</em> declares, and its shortest path from the root on the declared dependency graph. */
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

    /** The build tool's default repository with this JVM's {@code jenesis.maven.*} properties laid over it; the tool
     *  reads settings from the environment it is handed, never from system properties. */
    private static MavenRepository repository() {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((name, value) -> properties.put(name.toString(), value.toString()));
        return MavenDefaultRepository.ofEnvironment(new Environment(Make.keys(properties)));
    }

    /** The identifier the root POM is resolved under, so the closure names it among its roots. */
    private static final String ROOT = "root";

    /**
     * The dependency closure resolved over the network - the SPI's single declared read-purity exception, reached
     * only when the artifact publishes no CycloneDX attachment. Best-effort: a walk that fails answers nothing and
     * the artifact screens on its own coordinate.
     *
     * <p>The root is not one of its own dependencies. The resolver's closure carries the root POM's coordinate in
     * {@code dependencies()} beside everything it reaches, so a walk that succeeds used to answer the artifact a
     * second time - with the same licences and no place on the graph, since it is the graph's origin - beside the
     * subject the POM itself yields. The declared closure never did, because a CycloneDX document's components
     * exclude its metadata component. The root POM is resolved under {@link #ROOT} so the closure names it, and it
     * is skipped here; found 2026-09-20 by a hermetic suite whose walk had reached a proxy.
     */
    private static List<ComplianceGate.Subject> transitive(byte[] pom) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MavenResolver.Closure closure = new MavenPomResolver().dependencies(
                    executor, repository(),
                    List.of(new MavenResolver.RootPom(new ByteArrayInputStream(pom), null, ROOT, false, null)),
                    Map.of(), MavenDependencyScope.COMPILE, PREFIX);
            Map<MavenDependencyKey, ComplianceGate.Reachability> reachability =
                    BuildGraphReachability.of(closure, PREFIX);
            Set<MavenDependencyKey> roots = new HashSet<>(closure.roots().values());
            List<ComplianceGate.Subject> subjects = new ArrayList<>();
            closure.dependencies().forEach((key, value) -> {
                if (roots.contains(key)) {
                    return;
                }
                ManifestSubjectBuilder declared = ManifestSubjectBuilder.of(ECOSYSTEM);
                for (var license : closure.licenses()
                        .getOrDefault(key.coordinate(PREFIX, value.version()), List.of())) {
                    declared = declared.license(license.name(), license.url());
                }
                subjects.addAll(declared.subject(key.groupId() + ":" + key.artifactId(), value.version(),
                        reachability.getOrDefault(key, ComplianceGate.Reachability.UNKNOWN)));
            });
            return subjects;
        } catch (Exception e) {
            LOGGER.warn("Could not resolve the transitive closure; the compliance gate is "
                    + "assessing the artifact coordinate only, not its dependency tree", e);
            return List.of();
        }
    }

    /**
     * The CycloneDX attachment published beside this artifact, parsed. Read through
     * {@link QualityInspector.Lookup#fetchBounded} so an oversized document answers "declares nothing" instead of
     * throwing out of the gateway's sibling cap, and parsed fail-soft, so a truncated or malformed BOM yields no
     * graph rather than an exception on the publish path.
     */
    private static Optional<DependencyGraph> siblingSbom(String path, String[] coordinate,
                                                         QualityInspector.Lookup lookup) throws IOException {
        for (String suffix : SBOM_ATTACHMENTS) {
            Optional<QualityInspector.Lookup.Bounded> attachment =
                    lookup.fetchBounded(JavaLayout.attachment(path, suffix), sbomLimit());
            if (attachment.isEmpty() || attachment.get().truncated()) {
                // Absent, or past the bound. Carrying on is right - the attachment is optional - but the two are
                // not the same fact, and the second makes this inspection incomplete rather than clean: the
                // dependency and licence facts that declaration would have carried are unknown, not absent. The
                // truncation is reported through the watching bridge below rather than swallowed here.
                continue;
            }
            DependencyGraph graph = CycloneDxParser.parse(attachment.get().content());
            if (!graph.isEmpty()) {
                return Optional.of(graph);
            }
        }
        return Optional.empty();
    }

    /**
     * The licences the jar's own embedded CycloneDX document declares for itself, located through the
     * {@code Sbom-Location} manifest header (or the {@code META-INF/sbom/} convention) and bounded by
     * {@link ArtifactSbom} - never an unbounded inflate. Only attempted when the inspector holds the COMPLETE
     * artifact: a bounded prefix that stops short of the SBOM entry would read as "carries none", and asserting a
     * whole-artifact fact off a prefix is exactly what the SPI's streaming clause forbids. Any read or parse failure
     * degrades to no declaration, so an artifact whose archive will not open still publishes on its path coordinate.
     */
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
     * The POM a jar carries inside itself, at the {@code META-INF/maven/<groupId>/<artifactId>/pom.xml} path
     * {@code maven-archiver} writes by default - or empty when the jar carries none for the coordinate its request
     * path names.
     *
     * <p><b>Why a jar needs a licence source that is not its sibling.</b> A Maven deploy is several requests, and the
     * client sends the jar <em>before</em> the POM: {@code mvn deploy} and {@code deploy:deploy-file} both PUT the
     * artifact first. So at the moment the jar is screened its sibling POM is not in the store yet and the rung
     * above finds nothing: on a deployment that has set {@code license-unknown=QUARANTINE} the main artifact of an
     * ordinary release is then held for a licence its publisher did declare - in the document arriving one request
     * later. That this race is so ordinary is exactly why the shipped default is {@code ALLOW}.
     * The embedded descriptor is that same declaration, present in the bytes already in hand, so the common case
     * needs no cross-request ordering to screen correctly.
     *
     * <p>The entry name is matched against the coordinate the <em>request path</em> yields, never against whatever
     * the archive happens to contain: a jar cannot declare a licence under another coordinate's descriptor and have
     * it counted for this one.
     *
     * <p>Walked under {@link ArchiveWalk#largestWalk()} and inflated at the shared entry tier, exactly as the
     * {@code Bundle-License} rung is, so a descriptor that is a deflate bomb is refused within the cap rather than
     * inflated on the publish thread. Only attempted on the COMPLETE artifact, for the same reason
     * {@link #embeddedSbom} is: a bounded prefix stopping short of the entry would read as "carries none", and
     * asserting a whole-artifact fact off a prefix is what the SPI's streaming clause forbids. Every failure degrades
     * to no declaration and hands on to the next rung.
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

    /** The named descriptor entry of an already-bounded jar stream, or null when the archive carries no such entry. */
    private static byte[] descriptorEntry(InputStream jar, String descriptor) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(jar)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().equals(descriptor)) {
                    // An OPTIONAL declaration: a descriptor the inflation ceiling stopped degrades to "declares
                    // nothing" rather than to the prefix that was read before the ceiling.
                    return ArchiveInflation.entry(zip).orNull();
                }
            }
            return null;
        }
    }

    /** The licences the BOM's {@code metadata.component} - the artifact the document is about - declares. */
    private static Optional<ManifestSubjectBuilder> rootLicenses(DependencyGraph graph, String ecosystem) {
        return graph.root()
                .map(root -> licenses(ManifestSubjectBuilder.of(ecosystem), root))
                .filter(declared -> !declared.declared().isEmpty());
    }

    /** A component's CycloneDX licences folded onto a subject builder: an SPDX {@code id} (or expression) as an
     *  identifier, otherwise the free-text {@code name}/{@code url} pair. */
    private static ManifestSubjectBuilder licenses(ManifestSubjectBuilder builder, DependencyComponent component) {
        ManifestSubjectBuilder declared = builder;
        for (DependencyLicense license : component.licenses()) {
            declared = license.id() == null || license.id().isBlank()
                    ? declared.license(license.name(), license.url())
                    : declared.license(license.id());
        }
        return declared;
    }

    /** Deliberately without the whole-artifact guard the two rungs above carry: the manifest is a jar's FIRST
     *  member, so a bounded prefix that reaches it has read the real header rather than a piece of one, and losing
     *  the rung on every truncated body would give up a declaration that was there to be read. */
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

    /**
     * What a rung may read, and how far: the body, whether it is the WHOLE artifact, and the ceiling a walk of it
     * stops at.
     *
     * <p>The two legs differ in exactly these three things and in nothing else, which is why they are one object
     * rather than two code paths. A bounded leg holds at most a prefix and walks it at the flat archive-walk tier;
     * a streamed leg holds the artifact and walks it at the shared full-body tier. A rung that must not conclude
     * from a prefix asks {@link #whole()}; one that walks asks {@link #ceiling()} and reports back through
     * {@link #note}, because a walk the ceiling stopped means the declaration may be past it and an empty answer is
     * then "we stopped looking" rather than "it declares nothing".
     */
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

    /** The {@code [groupId, artifactId, version]} of a Maven request path - the layout's own split, not a second
     *  copy of it. This inspector describes the Maven layout; it does not get to have its own opinion about what a
     *  Maven path means. */
    private static String[] coordinate(String path) {
        return JavaLayout.mavenCoordinate(path);
    }


    /**
     * A component's Maven {@code group:artifact} and version. The purl is preferred where the document carries one -
     * it is the canonical, cross-repository identity and is what the emitter writes - and the split
     * {@code group}/{@code name}/{@code version} fields are the fallback for a BOM that omits it. A component that is
     * neither a Maven purl nor a complete triple yields {@code null} and is skipped: the gate keys Maven coordinates
     * in the Maven ecosystem, so inventing one from a partial record would query the feeds for a package that does
     * not exist under that name.
     */
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
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(pom));
            NodeList nodes = document.getElementsByTagName("license");
            ManifestSubjectBuilder licenses = ManifestSubjectBuilder.of(ECOSYSTEM);
            for (int index = 0; index < nodes.getLength(); index++) {
                Element license = (Element) nodes.item(index);
                licenses = licenses.license(text(license, "name"), text(license, "url"));
            }
            return licenses;
        } catch (Exception _) {
            // Maven derives the artifact's coordinate from the request PATH, not the POM content - the POM is read only
            // for its declared licenses (like Conda/Composer/CocoaPods read a manifest for licenses while the coordinate
            // comes from the path). A POM that will not parse therefore costs only the license, not the coordinate: the
            // path coordinate still screens, so this is NOT a MalformedArtifactException (which is reserved for
            // inspectors whose coordinate itself comes from unparseable content). Returns no license, as before.
            return ManifestSubjectBuilder.of(ECOSYSTEM);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
