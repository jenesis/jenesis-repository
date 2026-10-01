package build.jenesis.repository.compliance.maven.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.maven.ClosureObservability;
import build.jenesis.repository.compliance.maven.MavenQualityInspector;
import build.jenesis.repository.observation.Metric;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a published POM's dependency closure is resolved from, and how far: from the one repository an operator
 * named, within a bound on the documents read and the time taken, and from nowhere when none is named - each closure
 * not resolved counted rather than passed off as the artifact's whole screening.
 *
 * <p>The repository is a directory of POMs reached by a {@code file:} URI, so the walk is the resolver's own over a
 * repository this test wrote, and nothing leaves the machine. The application depends on {@code com.example:dep},
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

    @BeforeEach
    void publish() throws IOException {
        pom("dep", "GPL-3.0-only", """
                <dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                </dependencies>""");
        pom("leaf", "MIT", "");
    }

    @Test
    void with_no_repository_named_nothing_is_fetched_and_the_closure_is_counted_as_unresolved() throws Exception {
        // The build tool's own repository properties name a repository holding the whole closure: a walk that read
        // them, or fell back to the tool's default of Maven Central, would find it. Nothing named means no walk.
        String uri = System.getProperty("jenesis.maven.uri");
        System.setProperty("jenesis.maven.uri", repository.toUri().toString());
        long before = counter("jenrepo.compliance.closure.unresolved");
        try (AutoCloseable _ = ComplianceSettings.wire(() -> Map.<String, String>of()::get)) {
            assertThat(inspect()).extracting(ComplianceGate.Subject::coordinate)
                    .as("the artifact alone").containsExactly("com.app:lib");
        } finally {
            restore("jenesis.maven.uri", uri);
        }
        assertThat(counter("jenrepo.compliance.closure.unresolved")).isEqualTo(before + 1);
    }

    @Test
    void the_named_repository_resolves_the_closure_with_licences_and_places_on_the_graph() throws Exception {
        long incomplete = counter("jenrepo.compliance.closure.incomplete");
        long unresolved = counter("jenrepo.compliance.closure.unresolved");
        List<ComplianceGate.Subject> subjects;
        try (AutoCloseable _ = named(Map.of())) {
            subjects = inspect();
        }

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
    void a_closure_reading_more_documents_than_allowed_is_not_resolved_and_is_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        try (AutoCloseable _ = named(Map.of("maven-closure-documents", "1"))) {
            assertThat(inspect()).extracting(ComplianceGate.Subject::coordinate)
                    .as("no part of a closure is passed off as the whole of it").containsExactly("com.app:lib");
        }
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void a_closure_past_its_time_is_not_resolved_and_is_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        try (AutoCloseable _ = named(Map.of("maven-closure-timeout", "PT0S"))) {
            assertThat(inspect()).extracting(ComplianceGate.Subject::coordinate).containsExactly("com.app:lib");
        }
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void an_unreachable_repository_leaves_the_artifact_screened_and_is_counted() throws Exception {
        long before = counter("jenrepo.compliance.closure.incomplete");
        try (AutoCloseable _ = ComplianceSettings.wire(() -> Map.of("maven-closure-repository",
                "http://127.0.0.1:1/", "maven-closure-timeout", "PT5S")::get)) {
            assertThat(inspect()).extracting(ComplianceGate.Subject::coordinate).containsExactly("com.app:lib");
        }
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    @Test
    void a_dependency_whose_pom_the_repository_lacks_is_screened_as_itself_and_counted() throws Exception {
        // The resolver, as Maven does, takes a missing POM as a dependency that declares nothing, so the walk
        // completes - without knowing what that dependency pulls in, which is what the count says.
        Files.delete(repository.resolve("com/example/dep/1.0/dep-1.0.pom"));
        long before = counter("jenrepo.compliance.closure.incomplete");
        try (AutoCloseable _ = named(Map.of())) {
            assertThat(inspect()).extracting(ComplianceGate.Subject::coordinate)
                    .containsExactly("com.app:lib", "com.example:dep");
        }
        assertThat(counter("jenrepo.compliance.closure.incomplete")).isEqualTo(before + 1);
    }

    private List<ComplianceGate.Subject> inspect() throws IOException {
        return new MavenQualityInspector().inspect(PATH, APPLICATION, QualityInspector.Lookup.none());
    }

    /** The settings naming the test's repository, with {@code more} beside it. */
    private AutoCloseable named(Map<String, String> more) {
        Map<String, String> settings = new HashMap<>(more);
        settings.put("maven-closure-repository", repository.toUri().toString());
        return ComplianceSettings.wire(() -> settings::get);
    }

    private void pom(String artifact, String licence, String dependencies) throws IOException {
        Path pom = repository.resolve("com/example/" + artifact + "/1.0/" + artifact + "-1.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>%s</artifactId><version>1.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                  %s
                </project>
                """.formatted(artifact, licence, dependencies));
    }

    private static long counter(String name) {
        return new ClosureObservability().metrics().stream().filter(metric -> metric.name().equals(name))
                .mapToLong(metric -> (long) metric.value()).findFirst().orElseThrow();
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
