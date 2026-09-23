package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared {@link ComplianceGate.Subject} shapes the quality inspectors emit: the coordinate-only subject a
 * path-derived leg returns, the same subject carrying whatever licences a manifest declared, the content-scan
 * subject the secret and attestation inspectors stamp their findings onto, and the coordinate-segment guard four
 * inspectors had copied. Each inspector used to spell these out itself, so a "declares nothing" that quietly became
 * a licence named {@code ""}, or a guard that let a traversal segment through, could differ per format.
 */
class ManifestSubjectBuilderTest {

    @Test
    void a_coordinate_only_subject_declares_no_licence_and_is_not_a_content_scan() {
        List<ComplianceGate.Subject> subjects = ManifestSubjectBuilder.of("Debian").subject("hello", "2.10");
        assertThat(subjects).singleElement().satisfies(subject -> {
            assertThat(subject.ecosystem()).isEqualTo("Debian");
            assertThat(subject.coordinate()).isEqualTo("hello");
            assertThat(subject.version()).isEqualTo("2.10");
            assertThat(subject.licenses()).isEmpty();
            assertThat(subject.secrets()).isEmpty();
            assertThat(subject.attestation()).isNull();
            assertThat(subject.reachability()).isEqualTo(ComplianceGate.Reachability.UNKNOWN);
            assertThat(subject.contentScan())
                    .as("a real package that merely declares no licence still reaches the unknown-licence branch")
                    .isFalse();
        });
    }

    @Test
    void a_declared_identifier_is_trimmed_and_an_absent_one_declares_nothing() {
        assertThat(ManifestSubjectBuilder.of("conda").license("  BSD-3-Clause \t").declared())
                .singleElement().satisfies(license -> {
                    assertThat(license.name()).isEqualTo("BSD-3-Clause");
                    assertThat(license.url()).isNull();
                });
        assertThat(ManifestSubjectBuilder.of("conda").license(null).declared()).isEmpty();
        assertThat(ManifestSubjectBuilder.of("conda").license("   ").declared())
                .as("a blank value declares nothing - it is never a licence named \"\"").isEmpty();
    }

    @Test
    void a_name_and_url_licence_keeps_either_half_but_needs_one_of_them() {
        assertThat(ManifestSubjectBuilder.of("NuGet").license("MIT", "https://example/mit").declared())
                .singleElement().satisfies(license -> {
                    assertThat(license.name()).isEqualTo("MIT");
                    assertThat(license.url()).isEqualTo("https://example/mit");
                });
        assertThat(ManifestSubjectBuilder.of("NuGet").license(null, "https://example/licence").declared())
                .as("the legacy url-only shape still declares a licence").hasSize(1);
        assertThat(ManifestSubjectBuilder.of("NuGet").license(null, null).declared()).isEmpty();
        assertThat(ManifestSubjectBuilder.of("NuGet").license("  ", "  ").declared()).isEmpty();
    }

    @Test
    void a_manifest_declaring_several_keeps_them_in_order_and_drops_the_blank_ones() {
        List<String> declared = Arrays.asList("LGPL-2.1-only", null, "  ", " GPL-3.0-or-later ");
        assertThat(ManifestSubjectBuilder.of("Packagist").licenses(declared)
                .subject("acme/dual", "2.0.0"))
                .singleElement()
                .satisfies(subject -> assertThat(subject.licenses())
                        .extracting(ComplianceGate.DeclaredLicense::name)
                        .containsExactly("LGPL-2.1-only", "GPL-3.0-or-later"));
        assertThat(ManifestSubjectBuilder.of("Packagist").licenses(null).declared()).isEmpty();
    }

    @Test
    void the_builder_is_immutable_so_one_shared_inspector_can_build_on_many_threads() {
        ManifestSubjectBuilder base = ManifestSubjectBuilder.of("crates.io");
        ManifestSubjectBuilder mit = base.license("MIT");
        ManifestSubjectBuilder apache = base.license("Apache-2.0");
        assertThat(base.declared()).as("the base never accumulated either branch's licence").isEmpty();
        assertThat(mit.declared()).extracting(ComplianceGate.DeclaredLicense::name).containsExactly("MIT");
        assertThat(apache.declared()).extracting(ComplianceGate.DeclaredLicense::name).containsExactly("Apache-2.0");
    }

    @Test
    void a_content_scan_subject_carries_a_location_rather_than_a_licensable_coordinate() {
        ComplianceGate.Subject subject = ManifestSubjectBuilder
                .contentScan("attestation", "/maven/org/acme/widget/1.0/widget-1.0.jar")
                .withAttestation(new ComplianceGate.Attestation("envelope", "abc", true, "/x"));
        assertThat(subject.ecosystem()).isEqualTo("attestation");
        assertThat(subject.coordinate()).isEqualTo("/maven/org/acme/widget/1.0/widget-1.0.jar");
        assertThat(subject.version()).isEmpty();
        assertThat(subject.licenses()).isEmpty();
        assertThat(subject.contentScan())
                .as("the licence dimension skips it, so a content finding is not doubled with a bogus unknown licence")
                .isTrue();
    }

    @Test
    void a_content_scan_subject_takes_a_secret_detection_the_same_way() {
        ComplianceGate.Subject subject = ManifestSubjectBuilder.contentScan("secret", "/npm/x/-/x-1.0.0.tgz")
                .withSecrets(List.of(new ComplianceGate.DetectedSecret("aws", "an AWS key", "AKIA****", "index.js")));
        assertThat(subject.contentScan()).isTrue();
        assertThat(subject.secrets()).hasSize(1);
    }

    @Test
    void an_unservable_path_segment_is_not_a_coordinate() {
        assertThat(ManifestSubjectBuilder.unsafeSegment("widgets")).isFalse();
        assertThat(ManifestSubjectBuilder.unsafeSegment("1.2.3-beta.1+build")).isFalse();
        for (String unsafe : List.of("", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb")) {
            assertThat(ManifestSubjectBuilder.unsafeSegment(unsafe))
                    .as("a segment the format would never have stored or served: <%s>", unsafe).isTrue();
        }
        assertThat(ManifestSubjectBuilder.unsafeSegment(null)).isTrue();
    }
}
