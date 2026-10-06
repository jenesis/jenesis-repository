package build.jenesis.repository.closure.lock.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.closure.lock.CargoLock;
import build.jenesis.repository.closure.lock.NpmShrinkwrap;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A release's lock file taken as its closure as written: what npm installs from an {@code npm-shrinkwrap.json}, what a
 * {@code Cargo.lock} pins, each package the holding the store keeps of it at its distance from the release, and every
 * package it does not hold a cut.
 */
class CarriedLockTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreRepositoryInventory inventory;
    private Blobs blobs;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default")
                .scope("releases");
        inventory = new StoreRepositoryInventory(store);
        blobs = new Blobs(store);
    }

    @Test
    void a_carried_lock_file_is_asked_before_a_carried_bill_of_the_same_ecosystem() {
        List<String> npm = ClosureSource.serving("npm").stream().map(ClosureSource::name).toList();

        assertThat(npm).as("a lock file is exact and the package manager's own, so it answers first")
                .containsSubsequence("carried-lock-npm", "carried-bill");
        assertThat(ClosureSource.serving("crates.io").stream().map(ClosureSource::name).toList())
                .containsSubsequence("carried-lock-cargo", "carried-bill");
    }

    @Test
    void an_npm_release_is_what_its_shrinkwrap_installs_for_a_consumer() throws IOException {
        // b needs a@2 where the root needs a@1, so npm installs a@2 under b's own node_modules; dev-only and bundled
        // packages are not installed from the repository for a consumer, and a link names nothing it holds.
        npm("acme-app", "1.0.0", Map.of("package/package.json", "{\"name\":\"acme-app\",\"version\":\"1.0.0\"}",
                "package/npm-shrinkwrap.json", """
                {"name":"acme-app","version":"1.0.0","lockfileVersion":3,"packages":{
                  "":{"name":"acme-app","version":"1.0.0","dependencies":{"a":"^1.0.0","b":"^1.0.0","local":"*"},
                      "devDependencies":{"jest":"^29.0.0"}},
                  "node_modules/a":{"version":"1.0.0"},
                  "node_modules/b":{"version":"1.2.0","dependencies":{"a":"^2.0.0","c":"^3.0.0","vendored":"*"}},
                  "node_modules/b/node_modules/a":{"version":"2.0.0"},
                  "node_modules/c":{"version":"3.0.0"},
                  "node_modules/vendored":{"version":"0.1.0","inBundle":true},
                  "node_modules/jest":{"version":"29.7.0","dev":true},
                  "node_modules/local":{"resolved":"packages/local","link":true}}}"""));
        inventory.cache("npm", "a", "1.0.0", "https://registry.npmjs.org/", NOW);
        inventory.cache("npm", "a", "2.0.0", "https://registry.npmjs.org/", NOW);
        inventory.cache("npm", "b", "1.2.0", "https://registry.npmjs.org/", NOW);

        ClosureSection.Closure closure = new NpmShrinkwrap().resolve(ClosureWalk.of(store), "npm", "acme-app",
                "1.0.0", NOW).orElseThrow();

        assertThat(closure.kind()).isEqualTo(ClosureSource.Kind.LOCK);
        assertThat(closure.source()).isEqualTo(NpmShrinkwrap.NAME);
        assertThat(closure.components()).containsExactly(
                new ClosureSection.Component("a", "1.0.0", true, 1, ""),
                new ClosureSection.Component("b", "1.2.0", true, 1, ""),
                new ClosureSection.Component("a", "2.0.0", true, 2, "", "b", "1.2.0"));
        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate, ClosureSection.Cut::reason)
                .containsExactlyInAnyOrder(
                        tuple("local", "linked by the version's npm-shrinkwrap.json to a folder, which names nothing "
                                + "a repository holds"),
                        tuple("c", "named by the version's npm-shrinkwrap.json, not held by this repository"));
    }

    @Test
    void a_package_finds_a_name_in_the_nearest_node_modules_up_its_path() throws IOException {
        // d, installed under b, needs e: npm finds b's own e@5 before the root's e@4, which only the root needs.
        npm("acme-app", "1.0.0", Map.of("package/npm-shrinkwrap.json", """
                {"name":"acme-app","version":"1.0.0","lockfileVersion":3,"packages":{
                  "":{"name":"acme-app","version":"1.0.0","dependencies":{"b":"^1.0.0","e":"^4.0.0"}},
                  "node_modules/b":{"version":"1.0.0","dependencies":{"d":"^1.0.0"}},
                  "node_modules/b/node_modules/d":{"version":"1.0.0","dependencies":{"e":"^5.0.0"}},
                  "node_modules/b/node_modules/e":{"version":"5.0.0"},
                  "node_modules/e":{"version":"4.0.0"}}}"""));
        for (String held : List.of("b@1.0.0", "d@1.0.0", "e@4.0.0", "e@5.0.0")) {
            inventory.cache("npm", held.substring(0, 1), held.substring(2), "https://registry.npmjs.org/", NOW);
        }

        assertThat(new NpmShrinkwrap().resolve(ClosureWalk.of(store), "npm", "acme-app", "1.0.0", NOW)
                .orElseThrow().components()).extracting(ClosureSection.Component::coordinate,
                        ClosureSection.Component::version, ClosureSection.Component::depth,
                        ClosureSection.Component::viaCoordinate, ClosureSection.Component::viaVersion)
                .as("each reached through the package whose dependencies named it")
                .containsExactly(tuple("b", "1.0.0", 1, "", ""), tuple("e", "4.0.0", 1, "", ""),
                        tuple("d", "1.0.0", 2, "b", "1.0.0"), tuple("e", "5.0.0", 3, "d", "1.0.0"));
    }

    @Test
    void an_npm_release_carrying_no_shrinkwrap_has_none_to_take() throws IOException {
        npm("acme-app", "1.0.0", Map.of("package/package.json", "{\"name\":\"acme-app\",\"version\":\"1.0.0\"}"));

        assertThat(new NpmShrinkwrap().resolve(ClosureWalk.of(store), "npm", "acme-app", "1.0.0", NOW))
                .as("the resolvers answer instead").isEmpty();
    }

    @Test
    void a_shrinkwrap_of_the_first_lock_form_is_read_as_well() throws IOException {
        npm("acme-app", "1.0.0", Map.of("package/npm-shrinkwrap.json", """
                {"name":"acme-app","version":"1.0.0","lockfileVersion":1,"dependencies":{
                  "a":{"version":"1.0.0","requires":{"c":"^3.0.0"}},
                  "c":{"version":"3.0.0"},
                  "jest":{"version":"29.7.0","dev":true}}}"""));
        inventory.cache("npm", "a", "1.0.0", "https://registry.npmjs.org/", NOW);
        inventory.cache("npm", "c", "3.0.0", "https://registry.npmjs.org/", NOW);

        assertThat(new NpmShrinkwrap().resolve(ClosureWalk.of(store), "npm", "acme-app", "1.0.0", NOW)
                .orElseThrow().components()).extracting(ClosureSection.Component::coordinate,
                        ClosureSection.Component::depth)
                .containsExactlyInAnyOrder(tuple("a", 1), tuple("c", 1));
    }

    @Test
    void a_crate_is_what_its_cargo_lock_pins() throws IOException {
        crate("acme-cli", "0.3.0", """
                version = 4

                [[package]]
                name = "acme-cli"
                version = "0.3.0"
                dependencies = ["acme-core", "serde", "syn 2.0.79"]

                [[package]]
                name = "acme-core"
                version = "0.3.0"
                dependencies = ["syn 1.0.109"]

                [[package]]
                name = "serde"
                version = "1.0.210"
                source = "registry+https://github.com/rust-lang/crates.io-index"
                checksum = "c8e3592472072e6e22e0a54d5904d9febf8508f65fb8552499a1abc7d1078c3a"
                dependencies = ["serde_derive"]

                [[package]]
                name = "serde_derive"
                version = "1.0.210"
                source = "registry+https://github.com/rust-lang/crates.io-index"

                [[package]]
                name = "syn"
                version = "1.0.109"
                source = "registry+https://github.com/rust-lang/crates.io-index"

                [[package]]
                name = "syn"
                version = "2.0.79"
                source = "registry+https://github.com/rust-lang/crates.io-index"

                [[package]]
                name = "unused"
                version = "9.9.9"
                source = "registry+https://github.com/rust-lang/crates.io-index"
                """);
        inventory.cache("crates.io", "serde", "1.0.210", "https://index.crates.io/", NOW);
        inventory.cache("crates.io", "serde_derive", "1.0.210", "https://index.crates.io/", NOW);
        inventory.cache("crates.io", "syn", "1.0.109", "https://index.crates.io/", NOW);
        inventory.cache("crates.io", "syn", "2.0.79", "https://index.crates.io/", NOW);

        ClosureSection.Closure closure = new CargoLock().resolve(ClosureWalk.of(store), "crates.io", "acme-cli",
                "0.3.0", NOW).orElseThrow();

        assertThat(closure.source()).isEqualTo(CargoLock.NAME);
        assertThat(closure.components()).extracting(ClosureSection.Component::coordinate,
                        ClosureSection.Component::version, ClosureSection.Component::depth)
                .containsExactly(tuple("serde", "1.0.210", 1), tuple("syn", "2.0.79", 1),
                        tuple("syn", "1.0.109", 2), tuple("serde_derive", "1.0.210", 2));
        assertThat(closure.cuts()).extracting(ClosureSection.Cut::coordinate).containsExactly("acme-core");
        assertThat(ClosureSection.path(closure, "serde_derive", "1.0.210"))
                .containsExactly(new ClosureSection.Hop("serde", "1.0.210"),
                        new ClosureSection.Hop("serde_derive", "1.0.210"));
        assertThat(ClosureSection.path(closure, "syn", "1.0.109")).as("a path through a cut ends at it, named")
                .containsExactly(new ClosureSection.Hop("acme-core", "0.3.0"), new ClosureSection.Hop("syn", "1.0.109"));
        assertThat(closure.status()).isEqualTo(ClosureSection.Status.PARTIAL);
    }

    @Test
    void a_cargo_lock_with_no_entry_for_the_crate_is_no_closure() throws IOException {
        crate("acme-cli", "0.3.0", """
                version = 4

                [[package]]
                name = "serde"
                version = "1.0.210"
                source = "registry+https://github.com/rust-lang/crates.io-index"
                """);

        assertThat(new CargoLock().resolve(ClosureWalk.of(store), "crates.io", "acme-cli", "0.3.0", NOW)).isEmpty();
    }

    /** An npm release whose tarball carries {@code members}, stored where the npm format keeps its tarballs. */
    private void npm(String name, String version, Map<String, String> members) throws IOException {
        blobs.link("npm/" + name + "/tarballs/" + name + "-" + version + ".tgz",
                blobs.store(new ByteArrayInputStream(tarGz(members))));
        inventory.record("npm", name, version, NOW);
    }

    /** A crate carrying {@code lock} as its {@code Cargo.lock}, stored where the Cargo format keeps its crates. */
    private void crate(String name, String version, String lock) throws IOException {
        String top = name + "-" + version + "/";
        blobs.link("cargo/main/crates/" + name + "/" + name + "-" + version + ".crate",
                blobs.store(new ByteArrayInputStream(tarGz(Map.of(top + "Cargo.toml",
                        "[package]\nname = \"" + name + "\"\nversion = \"" + version + "\"\n",
                        top + "Cargo.lock", lock)))));
        inventory.record("crates.io", name, version, NOW);
    }

    private static byte[] tarGz(Map<String, String> members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes), "UTF-8")) {
            for (Map.Entry<String, String> member : new TreeMap<>(members).entrySet()) {
                byte[] content = member.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(member.getKey());
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
