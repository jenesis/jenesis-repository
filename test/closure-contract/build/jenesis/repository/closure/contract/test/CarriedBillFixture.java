package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/** The bill a Maven release's jar embeds, a flat CycloneDX component list naming the resolved closure. */
final class CarriedBillFixture implements ClosureFixture {

    @Override
    public String source() {
        return "carried-bill";
    }

    @Override
    public String ecosystem() {
        return "Maven";
    }

    @Override
    public Named release() {
        return MavenReleases.APP;
    }

    @Override
    public Named dependency(int index) {
        return MavenReleases.dependency(index);
    }

    @Override
    public void publish(ArtifactStore store, List<Named> dependencies, Instant now) throws IOException {
        StringJoiner components = new StringJoiner(",");
        for (Named dependency : dependencies) {
            String[] parts = dependency.coordinate().split(":");
            components.add("{\"bom-ref\":\"" + dependency.coordinate() + "\",\"group\":\"" + parts[0]
                    + "\",\"name\":\"" + parts[1] + "\",\"version\":\"" + dependency.version()
                    + "\",\"purl\":\"pkg:maven/" + parts[0] + "/" + parts[1] + "@" + dependency.version() + "\"}");
        }
        jar(store, """
                {"bomFormat":"CycloneDX","specVersion":"1.5",
                 "metadata":{"component":{"bom-ref":"root","group":"org.acme","name":"app","version":"1.0"}},
                 "components":[%s]}""".formatted(components), now);
    }

    @Override
    public void bare(ArtifactStore store, Instant now) throws IOException {
        jar(store, null, now);
    }

    @Override
    public void hold(ArtifactStore store, Named dependency, Instant now) throws IOException {
        MavenReleases.release(store, dependency, List.of(), now);
    }

    @Override
    public String path(Named dependency) {
        return MavenReleases.pom(dependency);
    }

    @Override
    public Map<ClosureContract.Property, String> unsupported() {
        return Map.of();
    }

    /** The release as a jar embedding {@code bill}, or none where it is {@code null}. */
    private void jar(ArtifactStore store, String bill, Instant now) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry(bill == null ? "app/Main.class" : "META-INF/sbom/app.cdx.json"));
            out.write((bill == null ? "" : bill).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Publication publication = new Publication(store);
        String path = MavenReleases.jar(release());
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(bytes.toByteArray())));
        new StoreRepositoryInventory(store).record(path, now);
    }
}
