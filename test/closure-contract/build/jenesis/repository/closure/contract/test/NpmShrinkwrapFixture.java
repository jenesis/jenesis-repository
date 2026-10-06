package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;

/** The {@code npm-shrinkwrap.json} an npm release's tarball carries, each package installed at the top. */
final class NpmShrinkwrapFixture implements ClosureFixture {

    private static final String UPSTREAM = "https://registry.npmjs.org/";

    @Override
    public String source() {
        return "carried-lock-npm";
    }

    @Override
    public String ecosystem() {
        return "npm";
    }

    @Override
    public Named release() {
        return new Named("acme-app", "1.0.0");
    }

    @Override
    public Named dependency(int index) {
        return new Named("dep-" + index, "1.0.0");
    }

    @Override
    public void publish(ArtifactStore store, List<Named> dependencies, Instant now) throws IOException {
        StringJoiner requires = new StringJoiner(",");
        StringJoiner installed = new StringJoiner(",");
        for (Named dependency : dependencies) {
            requires.add("\"" + dependency.coordinate() + "\":\"" + dependency.version() + "\"");
            installed.add("\"node_modules/" + dependency.coordinate() + "\":{\"version\":\"" + dependency.version()
                    + "\"}");
        }
        tarball(store, Map.of("package/package.json", "{\"name\":\"acme-app\",\"version\":\"1.0.0\"}",
                "package/npm-shrinkwrap.json", """
                {"name":"acme-app","version":"1.0.0","lockfileVersion":3,"packages":{
                  "":{"name":"acme-app","version":"1.0.0","dependencies":{%s}}%s}}"""
                        .formatted(requires, dependencies.isEmpty() ? "" : "," + installed)), now);
    }

    @Override
    public void bare(ArtifactStore store, Instant now) throws IOException {
        tarball(store, Map.of("package/package.json", "{\"name\":\"acme-app\",\"version\":\"1.0.0\"}"), now);
    }

    @Override
    public void hold(ArtifactStore store, Named dependency, Instant now) throws IOException {
        new StoreRepositoryInventory(store).cache("npm", dependency.coordinate(), dependency.version(), UPSTREAM, now);
    }

    @Override
    public String path(Named dependency) {
        return "/npm/" + dependency.coordinate() + "/-/" + dependency.coordinate() + "-" + dependency.version()
                + ".tgz";
    }

    @Override
    public Map<ClosureContract.Property, String> unsupported() {
        return Map.of();
    }

    private void tarball(ArtifactStore store, Map<String, String> members, Instant now) throws IOException {
        Blobs blobs = new Blobs(store);
        blobs.link("npm/acme-app/tarballs/acme-app-1.0.0.tgz",
                blobs.store(new ByteArrayInputStream(Tarballs.of(members))));
        new StoreRepositoryInventory(store).record("npm", "acme-app", "1.0.0", now);
    }
}
