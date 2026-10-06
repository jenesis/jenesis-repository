package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;

/** The {@code Cargo.lock} a crate carries, each package pinned from the registry. */
final class CargoLockFixture implements ClosureFixture {

    private static final String REGISTRY = "registry+https://github.com/rust-lang/crates.io-index";

    @Override
    public String source() {
        return "carried-lock-cargo";
    }

    @Override
    public String ecosystem() {
        return "crates.io";
    }

    @Override
    public Named release() {
        return new Named("acme-cli", "0.3.0");
    }

    @Override
    public Named dependency(int index) {
        return new Named("dep-" + index, "1.0.0");
    }

    @Override
    public void publish(ArtifactStore store, List<Named> dependencies, Instant now) throws IOException {
        StringBuilder lock = new StringBuilder("version = 4\n\n[[package]]\nname = \"acme-cli\"\nversion = \"0.3.0\"\n"
                + "dependencies = [");
        lock.append(String.join(", ", dependencies.stream().map(dependency -> "\"" + dependency.coordinate()
                + "\"").toList())).append("]\n");
        for (Named dependency : dependencies) {
            lock.append("\n[[package]]\nname = \"").append(dependency.coordinate()).append("\"\nversion = \"")
                    .append(dependency.version()).append("\"\nsource = \"").append(REGISTRY).append("\"\n");
        }
        crate(store, lock.toString(), now);
    }

    @Override
    public void bare(ArtifactStore store, Instant now) throws IOException {
        crate(store, null, now);
    }

    @Override
    public void hold(ArtifactStore store, Named dependency, Instant now) throws IOException {
        new StoreRepositoryInventory(store).cache("crates.io", dependency.coordinate(), dependency.version(),
                "https://index.crates.io/", now);
    }

    @Override
    public String path(Named dependency) {
        return "/cargo/api/v1/crates/" + dependency.coordinate() + "/" + dependency.version() + "/download";
    }

    @Override
    public Map<ClosureContract.Property, String> unsupported() {
        return Map.of();
    }

    /** The crate, carrying {@code lock} as its {@code Cargo.lock}, or none where it is {@code null}. */
    private void crate(ArtifactStore store, String lock, Instant now) throws IOException {
        String top = "acme-cli-0.3.0/";
        Map<String, String> members = new TreeMap<>(Map.of(top + "Cargo.toml",
                "[package]\nname = \"acme-cli\"\nversion = \"0.3.0\"\n"));
        if (lock != null) {
            members.put(top + "Cargo.lock", lock);
        }
        Blobs blobs = new Blobs(store);
        blobs.link("cargo/main/crates/acme-cli/acme-cli-0.3.0.crate",
                blobs.store(new ByteArrayInputStream(Tarballs.of(members))));
        new StoreRepositoryInventory(store).record("crates.io", "acme-cli", "0.3.0", now);
    }
}
