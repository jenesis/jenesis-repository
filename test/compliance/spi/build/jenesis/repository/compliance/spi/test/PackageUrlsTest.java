package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.PackageUrls;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package URL a purl-keyed feed looks a coordinate up by: one per ecosystem the purl specification types and a
 * coordinate can name faithfully, and none - so the feed is not asked - where the purl would name something the
 * coordinate is not. Read back, a distribution package's purl names the package its format publishes, and its
 * qualifiers keep what the coordinate leaves out, however the scanner that wrote it spells the release.
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
        assertThat(PackageUrls.parse("pkg:bitbucket/acme/lib@1.0")).as("a type no ecosystem here is named by")
                .isEmpty();
        assertThat(PackageUrls.parse("pkg:npm/left-pad")).as("no version").isEmpty();
        assertThat(PackageUrls.parse("left-pad@1.0")).as("not a package URL").isEmpty();
    }

    @Test
    void a_distribution_package_url_names_the_package_its_format_publishes() {
        assertThat(PackageUrls.parse("pkg:apk/alpine/musl@1.1.22-r2?arch=x86_64&distro=3.10.0"))
                .hasValue(new PackageUrls.Named(Ecosystems.ALPINE, "musl", "1.1.22-r2"));
        assertThat(PackageUrls.parse("pkg:deb/debian/libc6@2.36-9%2Bdeb12u4?arch=amd64&upstream=glibc&distro=debian-12"))
                .hasValue(new PackageUrls.Named(Ecosystems.DEBIAN, "libc6", "2.36-9+deb12u4"));
        assertThat(PackageUrls.parse("pkg:rpm/redhat/openssl@3.0.7-27.el9?arch=x86_64&epoch=1"))
                .as("an RPM's version is its build, as its file names it")
                .hasValue(new PackageUrls.Named(Ecosystems.RPM, "openssl", "3.0.7-27.el9.x86_64"));
        assertThat(PackageUrls.parse("pkg:rpm/redhat/tzdata@2024a-1.el9?arch=noarch"))
                .hasValue(new PackageUrls.Named(Ecosystems.RPM, "tzdata", "2024a-1.el9.noarch"));
        assertThat(PackageUrls.covered()).as("no purl-keyed feed is asked about a distribution package by name")
                .doesNotContain(Ecosystems.ALPINE, Ecosystems.DEBIAN, Ecosystems.RPM);
    }

    @Test
    void the_qualifiers_keep_what_the_coordinate_leaves_out_however_the_release_is_spelled() {
        // Trivy writes the release bare, Syft prefixes it with the distribution's namespace.
        assertThat(PackageUrls.qualifiers("pkg:apk/alpine/musl@1.1.22-r2?arch=x86_64&distro=3.10.0"))
                .containsExactly(Map.entry("arch", "x86_64"), Map.entry("distro", "3.10.0"));
        assertThat(PackageUrls.qualifiers(
                "pkg:apk/alpine/musl@1.1.22-r2?arch=x86_64&upstream=musl&distro=alpine-3.10.0"))
                .containsExactly(Map.entry("arch", "x86_64"), Map.entry("upstream", "musl"),
                        Map.entry("distro", "3.10.0"));
        assertThat(PackageUrls.qualifiers("pkg:rpm/redhat/openssl@3.0.7-27.el9?Arch=x86_64&epoch=1#sub"))
                .as("keys lower-cased, a subpath no qualifier")
                .containsExactly(Map.entry("arch", "x86_64"), Map.entry("epoch", "1"));
        assertThat(PackageUrls.qualifiers("pkg:deb/debian/curl@7.88.1?distro=bookworm%2Dbackports"))
                .as("a value percent-decoded, and kept where it names no namespace prefix")
                .containsExactly(Map.entry("distro", "bookworm-backports"));
        assertThat(PackageUrls.qualifiers("pkg:npm/left-pad@1.3.0")).isEmpty();
        assertThat(PackageUrls.qualifiers("not a purl")).isEmpty();
    }
}
