package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.cargo.CargoImporter;
import build.jenesis.repository.format.cocoapods.CocoaPodsImporter;
import build.jenesis.repository.format.composer.ComposerImporter;
import build.jenesis.repository.format.conda.CondaImporter;
import build.jenesis.repository.format.helm.HelmImporter;
import build.jenesis.repository.format.ivy.IvyImporter;
import build.jenesis.repository.format.rpm.RpmImporter;
import build.jenesis.repository.store.ArtifactDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A format whose served paths name a repository of their own - an RPM repository, a conda channel, a Composer, Cargo,
 * Helm or CocoaPods registry - imports a path of that shape into the repository it names, so another deployment's
 * listing, which names each file where it is served, is laid out where the source served it rather than collapsed
 * into the importer's one default. Ivy, whose paths name no repository, lays a file out where it was served.
 */
class ServedShapeImportTest {

    static Stream<Arguments> served() {
        return Stream.of(
                Arguments.of(new RpmImporter(), "rpm", "realrepo/hello-1.0-1.x86_64.rpm"),
                Arguments.of(new CondaImporter(), "conda", "realchan/noarch/acme-1.0-0.tar.bz2"),
                Arguments.of(new ComposerImporter(), "composer", "packages/dists/acme/widget/1.0.0.zip"),
                Arguments.of(new CargoImporter(), "cargo", "crates/api/v1/crates/acme-demo/1.0.0/download"),
                Arguments.of(new HelmImporter(), "helm", "charts/charts/demo-1.0.0.tgz"),
                Arguments.of(new CocoaPodsImporter(), "cocoapods", "pods/pods/Alamofire/5.6.4/Alamofire.zip"),
                Arguments.of(new IvyImporter(), "ivy", "com.acme.ivy/widget/1.0/widget-1.0.jar"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("served")
    void a_path_of_the_served_shape_imports_where_it_was_served(RepositoryImporter importer, String mount,
                                                               String relative) {
        assertThat(importer.importTarget(relative)).map(ArtifactDescriptor::path)
                .hasValue("/" + mount + "/" + relative);
    }
}
