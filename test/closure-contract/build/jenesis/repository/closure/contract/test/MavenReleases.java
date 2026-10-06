package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/** Maven releases as the Maven format publishes them - a POM at its layout path, recorded, with the declarations the
 *  publish screen reads off it - shared by the three fixtures over Maven. */
final class MavenReleases {

    static final ClosureFixture.Named APP = new ClosureFixture.Named("org.acme:app", "1.0");

    private MavenReleases() {
    }

    static ClosureFixture.Named dependency(int index) {
        return new ClosureFixture.Named("org.dep:d" + index, "1.0");
    }

    /** Where the Maven layout keeps {@code named}'s POM. */
    static String pom(ClosureFixture.Named named) {
        return folder(named) + artifact(named) + "-" + named.version() + ".pom";
    }

    /** Where the Maven layout keeps {@code named}'s jar. */
    static String jar(ClosureFixture.Named named) {
        return folder(named) + artifact(named) + "-" + named.version() + ".jar";
    }

    /** Publishes {@code named} as a release whose POM declares each of {@code dependencies} at its version. */
    static void release(ArtifactStore store, ClosureFixture.Named named, List<ClosureFixture.Named> dependencies,
                        Instant now) throws IOException {
        StringBuilder declared = new StringBuilder();
        for (ClosureFixture.Named dependency : dependencies) {
            String[] parts = dependency.coordinate().split(":");
            declared.append("<dependency><groupId>").append(parts[0]).append("</groupId><artifactId>")
                    .append(parts[1]).append("</artifactId><version>").append(dependency.version())
                    .append("</version></dependency>");
        }
        String[] parts = named.coordinate().split(":");
        byte[] pom = ("<project><modelVersion>4.0.0</modelVersion><groupId>" + parts[0] + "</groupId><artifactId>"
                + parts[1] + "</artifactId><version>" + named.version() + "</version><dependencies>" + declared
                + "</dependencies></project>").getBytes(StandardCharsets.UTF_8);
        Publication publication = new Publication(store);
        String path = pom(named);
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(pom)));
        new StoreRepositoryInventory(store).record(path, now);
        MetadataProvider.installed().over(store).mutate("Maven", named.coordinate(), named.version(),
                DependencySection.TAG, DependencySection.record(path, dependencies.stream()
                        .map(dependency -> new DependencySection.Declared(dependency.coordinate(),
                                dependency.version())).toList(), now));
    }

    private static String folder(ClosureFixture.Named named) {
        String[] parts = named.coordinate().split(":");
        return "/maven/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + named.version() + "/";
    }

    private static String artifact(ClosureFixture.Named named) {
        return named.coordinate().split(":")[1];
    }
}
