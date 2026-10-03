package build.jenesis.repository.compliance.maven.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.maven.MavenQualityInspector;
import build.jenesis.repository.compliance.oci.OciQualityInspector;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a package's own document says it is for, read by the inspector that already parses it, so the publish records
 * it for a full-text index without the artifact being opened again.
 */
class ManifestAboutTest {

    @Test
    void a_pom_gives_the_projects_own_description_and_never_a_nested_one() throws IOException {
        String pom = """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>demo</artifactId><version>1.0</version>
                  <name>Demo</name><description>Demonstrates the demo.</description>
                  <organization><name>Acme</name></organization>
                  <licenses><license><name>MIT</name><comments>not the description</comments></license></licenses>
                </project>
                """;
        assertThat(new MavenQualityInspector().inspectArtifact("/maven/com/acme/demo/1.0/demo-1.0.pom",
                pom.getBytes(StandardCharsets.UTF_8), QualityInspector.Lookup.none()))
                .singleElement().satisfies(subject -> assertThat(subject.about())
                        .isEqualTo(new ComplianceGate.About("Demonstrates the demo.", List.of(), List.of())));
    }

    @Test
    void a_pom_without_a_description_is_found_by_its_name() throws IOException {
        String pom = """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>demo</artifactId><version>1.0</version>
                  <name>The Demo Library</name>
                </project>
                """;
        assertThat(new MavenQualityInspector().inspectArtifact("/maven/com/acme/demo/1.0/demo-1.0.pom",
                pom.getBytes(StandardCharsets.UTF_8), QualityInspector.Lookup.none()))
                .singleElement().satisfies(subject -> assertThat(subject.about().description())
                        .isEqualTo("The Demo Library"));
    }

    @Test
    void an_image_manifest_gives_its_description_and_authors_annotations() throws IOException {
        String manifest = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
                 "annotations":{"org.opencontainers.image.description":"A tiny web server.",
                                "org.opencontainers.image.authors":"Ada Lovelace <ada@example.com>"}}
                """;
        assertThat(new OciQualityInspector().inspectArtifact("/v2/acme/web/manifests/1.0",
                manifest.getBytes(StandardCharsets.UTF_8), QualityInspector.Lookup.none()))
                .singleElement().satisfies(subject -> {
                    assertThat(subject.about()).isEqualTo(new ComplianceGate.About("A tiny web server.", List.of(),
                            List.of("Ada Lovelace")));
                    assertThat(subject.maintainers()).as("an author credited by name binds no key to anybody")
                            .isEmpty();
                });
    }
}
