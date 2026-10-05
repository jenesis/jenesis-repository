package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.PackageUrls;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package URL a purl-keyed feed looks a coordinate up by: one per ecosystem the purl specification types and a
 * coordinate can name faithfully, and none - so the feed is not asked - where the purl would name something the
 * coordinate is not.
 */
class PackageUrlsTest {

    @Test
    void a_maven_coordinate_splits_into_namespace_and_name() {
        assertThat(PackageUrls.of(Ecosystems.MAVEN, "org.apache.commons:commons-lang3", "3.14.0"))
                .isEqualTo("pkg:maven/org.apache.commons/commons-lang3@3.14.0");
    }

    @Test
    void a_conda_package_and_a_hugging_face_model_have_their_purl_types() {
        // Both were left unmapped, so a licensed feed was never asked about either.
        assertThat(PackageUrls.of(Ecosystems.CONDA, "numpy", "1.26.4")).isEqualTo("pkg:conda/numpy@1.26.4");
        assertThat(PackageUrls.of(Ecosystems.HUGGING_FACE, "acme/demo-model", "main"))
                .isEqualTo("pkg:huggingface/acme/demo-model@main");
    }

    @Test
    void an_ecosystem_whose_purl_names_something_the_coordinate_is_not_has_none() {
        // An OCI purl is keyed by digest where the coordinate is a tag; a Swift purl by the source URL where the
        // coordinate is the registry's scope.name; a distribution's by a distro namespace the coordinate lacks.
        assertThat(PackageUrls.of("OCI", "acme/app", "1.0")).isNull();
        assertThat(PackageUrls.of("Swift", "acme.widget", "1.0.0")).isNull();
        assertThat(PackageUrls.of(Ecosystems.DEBIAN, "curl", "7.88.1")).isNull();
    }

    @Test
    void a_package_url_parses_back_to_the_coordinate_it_was_made_from() {
        for (String[] coordinate : List.of(
                new String[]{Ecosystems.MAVEN, "org.apache.logging.log4j:log4j-core", "2.17.1"},
                new String[]{Ecosystems.NPM, "@babel/core", "7.24.0"},
                new String[]{Ecosystems.PYPI, "requests", "2.31.0"},
                new String[]{Ecosystems.GO, "github.com/acme/thing", "v1.2.3"},
                new String[]{Ecosystems.PACKAGIST, "symfony/console", "6.4.0"})) {
            assertThat(PackageUrls.parse(PackageUrls.of(coordinate[0], coordinate[1], coordinate[2])))
                    .as(String.join(" ", coordinate))
                    .hasValue(new PackageUrls.Named(coordinate[0], coordinate[1], coordinate[2]));
        }
        assertThat(PackageUrls.parse("pkg:maven/org.acme/lib@1.0?type=jar#sub"))
                .as("qualifiers and a subpath are not the coordinate")
                .hasValue(new PackageUrls.Named(Ecosystems.MAVEN, "org.acme:lib", "1.0"));
        assertThat(PackageUrls.parse("pkg:deb/debian/openssl@3.0")).as("a type no ecosystem here is named by").isEmpty();
        assertThat(PackageUrls.parse("pkg:npm/left-pad")).as("no version").isEmpty();
        assertThat(PackageUrls.parse("left-pad@1.0")).as("not a package URL").isEmpty();
    }
}
