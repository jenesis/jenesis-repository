package build.jenesis.repository.compliance.maven.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.IncompleteScreenException;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.maven.ClosureObservability;
import build.jenesis.repository.compliance.maven.MavenQualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.observation.Metric;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Where a published POM's dependency closure is resolved from, and how far: from the repository it is published into,
 * then the upstreams the deployment proxies and the one repository an operator named, within a bound on the documents
 * read and the time taken - a repository a POM declares only where an operator allowed it - and what a publish whose
 * closure could not be resolved in full is given.
 *
 * <p>The repositories are directories of POMs reached by {@code file:} URIs, so the walk is the resolver's own over
 * repositories this test wrote, and nothing leaves the machine. The application depends on {@code com.example:dep},
 * which depends on {@code com.example:leaf}.
 */
class MavenClosureTest {

    private static final String PATH = "/maven/com/app/lib/1.0/lib-1.0.pom";

    private static final byte[] APPLICATION = """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.app</groupId><artifactId>lib</artifactId><version>1.0</version>
              <licenses><license><name>Apache-2.0</name></license></licenses>
              <dependencies>
                <dependency><groupId>com.example</groupId><artifactId>dep</artifactId><version>1.0</version></dependency>
              </dependencies>
            </project>
            """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path repository;

    @TempDir
    Path elsewhere;

    @BeforeEach
    void publish() throws IOException {
        pom(repository, "dep", "GPL-3.0-only", """
                <dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                </dependencies>""");
        pom(repository, "leaf", "MIT", "");
    }

    @Test
    void a_proxy_fill_with_no_repository_named_walks_nothing() throws Exception {
        long unresolved = counter("jenrepo.compliance.closure.unresolved");
        assertThat(new MavenQualityInspector().inspect(PATH, APPLICATION, QualityInspector.Lookup.none(
                Map.<String, String>of()::get))).extracting(ComplianceGate.Subject::coordinate)
                .as("the artifact alone").containsExactly("com.app:lib");
        assertThat(counter("jenrepo.compliance.closure.unresolved")).isEqualTo(unresolved);
    }

    @Test
    void a_publish_with_nowhere_else_to_read_screens_an_outside_dependency_on_its_coordinate_and_counts_it()
            throws Exception {
        long unresolved = counter("jenrepo.compliance.closure.unresolved");
        long incomplete = counter("jenrepo.compliance.closure.incomplete");
        assertThat(inspect(publishing(elsewhere, List.of(), Map.of()))).extracting(ComplianceGate.Subject::coordinate)
                .as("the artifact and what it declares, read from nowhere else")
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep");
        assertThat(counter("jenrepo.compliance.closure.unresolved")).isEqualTo(unresolved + 1);
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(incomplete);
    }

    @Test
    void a_dependency_published_here_resolves_with_what_it_depends_on() throws Exception {
        long unresolved = counter("jenrepo.compliance.closure.unresolved");
        List<ComplianceGate.Subject> subjects = inspect(publishing(repository, List.of(), Map.of()));

        assertThat(subjects).extracting(ComplianceGate.Subject::coordinate)
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
        assertThat(subjects).filteredOn(subject -> subject.coordinate().equals("com.example:leaf")).singleElement()
                .satisfies(leaf -> {
                    assertThat(leaf.licenses()).extracting(ComplianceGate.DeclaredLicense::name).containsExactly("MIT");
                    assertThat(leaf.reachability().kind()).isEqualTo(ComplianceGate.Reachability.Kind.TRANSITIVE);
                });
        assertThat(counter("jenrepo.compliance.closure.unresolved")).as("nothing left this repository")
                .isEqualTo(unresolved);
    }

    @Test
    void a_publish_reads_the_upstreams_the_deployment_proxies() throws Exception {
        assertThat(inspect(publishing(elsewhere, List.of(repository.toUri()), Map.of())))
                .extracting(ComplianceGate.Subject::coordinate)
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
    }

    @Test
    void the_named_repository_resolves_the_closure_with_licences_and_places_on_the_graph() throws Exception {
        long incomplete = counter("jenrepo.compliance.closure.incomplete");
        long unresolved = counter("jenrepo.compliance.closure.unresolved");
        List<ComplianceGate.Subject> subjects = inspect(QualityInspector.Lookup.none(named(Map.of())));

        assertThat(subjects).extracting(ComplianceGate.Subject::coordinate)
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
        assertThat(subjects).filteredOn(subject -> subject.coordinate().equals("com.example:leaf")).singleElement()
                .satisfies(leaf -> {
                    assertThat(leaf.licenses()).extracting(ComplianceGate.DeclaredLicense::name).containsExactly("MIT");
                    assertThat(leaf.reachability().kind()).isEqualTo(ComplianceGate.Reachability.Kind.TRANSITIVE);
                });
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(incomplete);
        assertThat(counter("jenrepo.compliance.closure.unresolved")).isEqualTo(unresolved);
    }

    @Test
    void a_dependency_whose_pom_no_place_holds_is_held_by_default_on_what_was_read() throws Exception {
        // The resolver, as Maven does, takes a missing POM as a dependency that declares nothing, so the walk
        // completes - without knowing what that dependency pulls in, which is what the hold says.
        Files.delete(repository.resolve("com/example/dep/1.0/dep-1.0.pom"));
        long before = counter("jenrepo.compliance.closure.incomplete");
        IncompleteScreenException held = held(QualityInspector.Lookup.none(named(Map.of())));
        assertThat(held.verdict()).as("the shipped default").isEqualTo(Verdict.QUARANTINE);
        assertThat(held.subjects()).extracting(ComplianceGate.Subject::coordinate)
                .containsExactly("com.app:lib", "com.example:dep");
        assertThat(held).hasMessageContaining("com.example:dep:1.0");
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void an_incomplete_closure_is_admitted_when_the_deployment_says_so() throws Exception {
        Files.delete(repository.resolve("com/example/dep/1.0/dep-1.0.pom"));
        assertThat(inspect(QualityInspector.Lookup.none(named(Map.of("maven-closure-incomplete", "ALLOW")))))
                .extracting(ComplianceGate.Subject::coordinate).containsExactly("com.app:lib", "com.example:dep");
    }

    @Test
    void a_closure_reading_more_documents_than_allowed_is_held_on_the_artifact_and_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        assertThat(held(QualityInspector.Lookup.none(named(Map.of("maven-closure-documents", "1")))).subjects())
                .extracting(ComplianceGate.Subject::coordinate)
                .as("no part of a closure is passed off as the whole of it").containsExactly("com.app:lib");
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void a_closure_past_its_time_is_held_and_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        assertThat(held(QualityInspector.Lookup.none(named(Map.of("maven-closure-timeout", "PT0S")))).subjects())
                .extracting(ComplianceGate.Subject::coordinate).containsExactly("com.app:lib");
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void an_unreachable_repository_is_held_and_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        assertThat(held(QualityInspector.Lookup.none(Map.of("maven-closure-repository", "http://127.0.0.1:1/",
                "maven-closure-timeout", "PT5S")::get)).subjects())
                .extracting(ComplianceGate.Subject::coordinate).containsExactly("com.app:lib");
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void a_repository_a_pom_declares_is_read_only_where_an_operator_allowed_it() throws Exception {
        // The leaf is published only in a repository the dependency's POM names, which this deployment neither proxies
        // nor names.
        Files.delete(repository.resolve("com/example/leaf/1.0/leaf-1.0.pom"));
        pom(elsewhere, "leaf", "MIT", "");
        pom(repository, "dep", "GPL-3.0-only", """
                <repositories><repository><id>theirs</id><url>%s</url></repository></repositories>
                <dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                </dependencies>""".formatted(elsewhere.toUri()));

        assertThat(held(publishing(repository, List.of(repository.toUri()), Map.of())).getMessage())
                .as("not read, so the leaf's POM is found nowhere this deployment reads").contains("com.example:leaf");
        assertThat(inspect(publishing(repository, List.of(repository.toUri()),
                Map.of("maven-closure-allowed", elsewhere.toUri().toString()))))
                .extracting(ComplianceGate.Subject::coordinate).as("allowed, so read")
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
    }

    @Test
    void a_dependency_the_artifact_does_not_ship_is_never_read() throws Exception {
        // A test-scoped and an optional dependency no place holds: a consumer pulls in neither, so neither is asked for,
        // and the closure is whole.
        byte[] application = new String(APPLICATION, StandardCharsets.UTF_8).replace("</dependencies>", """
                    <dependency><groupId>com.example</groupId><artifactId>tested</artifactId><version>1.0</version>
                      <scope>test</scope></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>optional</artifactId><version>1.0</version>
                      <optional>true</optional></dependency>
                  </dependencies>""").getBytes(StandardCharsets.UTF_8);
        assertThat(new MavenQualityInspector().inspect(PATH, application,
                publishing(repository, List.of(repository.toUri()), Map.of())))
                .extracting(ComplianceGate.Subject::coordinate)
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
    }

    @Test
    void a_version_range_on_an_internal_dependency_resolves_against_what_this_repository_lists() throws Exception {
        Files.writeString(repository.resolve("com/example/dep/maven-metadata.xml"), """
                <metadata><groupId>com.example</groupId><artifactId>dep</artifactId>
                  <versioning><versions><version>1.0</version></versions></versioning>
                </metadata>
                """);
        byte[] application = new String(APPLICATION, StandardCharsets.UTF_8)
                .replace("<version>1.0</version></dependency>", "<version>[1.0,2.0)</version></dependency>")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(new MavenQualityInspector().inspect(PATH, application,
                publishing(repository, List.of(elsewhere.toUri()), Map.of())))
                .extracting(ComplianceGate.Subject::coordinate).as("the range read off this repository's metadata")
                .containsExactlyInAnyOrder("com.app:lib", "com.example:dep", "com.example:leaf");
    }

    /** The application's POM inspected through {@code lookup}. */
    private List<ComplianceGate.Subject> inspect(QualityInspector.Lookup lookup) throws IOException {
        return new MavenQualityInspector().inspect(PATH, APPLICATION, lookup);
    }

    /** What the inspection of the application's POM through {@code lookup} held it for. */
    private IncompleteScreenException held(QualityInspector.Lookup lookup) {
        IncompleteScreenException held = catchThrowableOfType(IncompleteScreenException.class, () -> inspect(lookup));
        assertThat(held).as("an incomplete closure is decided on, not passed off as complete").isNotNull();
        return held;
    }

    /** The settings naming the test's repository, with {@code more} beside it. */
    private UnaryOperator<String> named(Map<String, String> more) {
        Map<String, String> settings = new HashMap<>(more);
        settings.put("maven-closure-repository", repository.toUri().toString());
        return settings::get;
    }

    /** A publish into {@code published} - whose POMs it serves - for a deployment proxying {@code proxied} and set as
     *  {@code settings} says. */
    private static QualityInspector.Lookup publishing(Path published, List<URI> proxied, Map<String, String> settings) {
        return new QualityInspector.Lookup.Detached() {

            @Override
            public Optional<byte[]> fetch(String path) throws IOException {
                Path file = published.resolve(path.substring("/maven/".length()));
                return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
            }

            @Override
            public Optional<QualityInspector.Lookup.Bounded> fetchBounded(String path, int limit) throws IOException {
                return fetch(path).map(content -> new QualityInspector.Lookup.Bounded(content, false));
            }

            @Override
            public UnaryOperator<String> settings() {
                return settings::get;
            }

            @Override
            public Optional<List<URI>> resolvesFrom(String format) {
                return Optional.of(proxied);
            }
        };
    }

    private static void pom(Path repository, String artifact, String licence, String more) throws IOException {
        Path pom = repository.resolve("com/example/" + artifact + "/1.0/" + artifact + "-1.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                  %s
                </project>
                """.formatted(artifact, licence, more));
    }

    private static long counter(String name) {
        return new ClosureObservability().metrics().stream().filter(metric -> metric.name().equals(name))
                .mapToLong(metric -> (long) metric.value()).findFirst().orElseThrow();
    }
}
