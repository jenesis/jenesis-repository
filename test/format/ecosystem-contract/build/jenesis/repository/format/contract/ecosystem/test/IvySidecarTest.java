package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/** A checksum or a signature beside an Ivy revision's file is no file of the revision: it is described with no
 *  coordinate, so nothing is recorded against the revision for it and it is not held with it. */
class IvySidecarTest {

    private static ArtifactLayout ivy() {
        return (ArtifactLayout) ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("ivy")).findFirst().orElseThrow();
    }

    @Test
    void a_revision_s_file_names_its_coordinate_and_its_sidecars_do_not() {
        ArtifactDescriptor jar = ivy().describe("/ivy/com.acme/widget/1.0/widget-1.0.jar").orElseThrow();
        assertThat(jar.coordinate()).isEqualTo("com.acme:widget");
        assertThat(jar.version()).isEqualTo("1.0");
        for (String sidecar : List.of("widget-1.0.jar.sha1", "widget-1.0.jar.sha512", "ivy-1.0.xml.md5",
                "widget-1.0.jar.asc")) {
            assertThat(ivy().describe("/ivy/com.acme/widget/1.0/" + sidecar)).get()
                    .extracting(ArtifactDescriptor::coordinate).as(sidecar).isNull();
        }
    }
}
