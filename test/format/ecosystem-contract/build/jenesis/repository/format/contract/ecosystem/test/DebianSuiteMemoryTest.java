package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.UpstreamMemory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied Debian suite is a family remembered together: its {@code InRelease}, {@code Release} and
 * {@code Release.gpg} are fetched in one go and remembered for {@code jenrepo.cache.upstream-ttl}, and an index asked
 * for by name is fetched by the digest the remembered {@code Release} names, so what a client is given agrees with the
 * release it was given even after the upstream moved. An upstream without by-hash paths is relayed fresh.
 */
class DebianSuiteMemoryTest {

    private static final URI ROOT = URI.create("https://deb.invalid/debian/");
    private static final String PACKAGES = "main/binary-amd64/Packages";

    @TempDir
    Path root;

    @BeforeEach
    void remember() {
        System.setProperty("jenrepo.cache.upstream-ttl", "PT6H");
        UpstreamMemory.reset();
    }

    @AfterEach
    void forget() {
        System.clearProperty("jenrepo.cache.upstream-ttl");
        UpstreamMemory.reset();
    }

    /** An upstream mirror whose suite can move, counting what it was asked for. */
    private static final class Mirror implements ProxyFormat.Fetcher.Buffered {

        final Map<String, Integer> asked = new ConcurrentHashMap<>();
        final boolean byHash;
        volatile byte[] packages;

        Mirror(boolean byHash, String packages) {
            this.byHash = byHash;
            this.packages = packages.getBytes(StandardCharsets.UTF_8);
        }

        String release() {
            return "Suite: stable\n" + (byHash ? "Acquire-By-Hash: yes\n" : "") + "SHA256:\n " + digest(packages) + " "
                    + packages.length + " " + PACKAGES + "\n";
        }

        Map<String, byte[]> served() {
            Map<String, byte[]> served = new HashMap<>();
            served.put("dists/stable/Release", release().getBytes(StandardCharsets.UTF_8));
            served.put("dists/stable/InRelease", ("-----BEGIN PGP SIGNED MESSAGE-----\n\n" + release()
                    + "-----BEGIN PGP SIGNATURE-----\n-----END PGP SIGNATURE-----\n").getBytes(StandardCharsets.UTF_8));
            served.put("dists/stable/Release.gpg", "signature".getBytes(StandardCharsets.UTF_8));
            served.put("dists/stable/" + PACKAGES, packages);
            if (byHash) {
                served.put("dists/stable/main/binary-amd64/by-hash/SHA256/" + digest(packages), packages);
            }
            return served;
        }

        void move(String to) {
            Map<String, byte[]> old = served();
            packages = to.getBytes(StandardCharsets.UTF_8);
            retained.putAll(old);
        }

        /** What the mirror still serves of a suite it has moved past: its by-hash files, as a real mirror keeps. */
        final Map<String, byte[]> retained = new ConcurrentHashMap<>();

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
            String path = ROOT.relativize(url).toString();
            asked.merge(path, 1, Integer::sum);
            byte[] body = served().get(path);
            if (body == null && path.contains("/by-hash/")) {
                body = retained.get(path);
            }
            return Optional.of(body == null ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RepositoryFormat debian() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("debian")).findFirst().orElseThrow();
    }

    private static ContractExchange read(ArtifactStore store, Mirror mirror, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", "/debian/" + path);
        ((ProxyFormat) debian()).proxy(exchange, store, ROOT, mirror);
        return exchange;
    }

    private ArtifactStore store() throws IOException {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_suite_is_remembered_together_and_an_index_follows_the_release_it_was_given() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror(true, "Package: widget\nVersion: 1.0\n");
        String first = new String(read(store, mirror, "dists/stable/InRelease").responseBytes(),
                StandardCharsets.UTF_8);
        assertThat(read(store, mirror, "dists/stable/Release.gpg").status()).isEqualTo(200);
        assertThat(mirror.asked).as("the three release documents were fetched once, together")
                .containsEntry("dists/stable/InRelease", 1).containsEntry("dists/stable/Release", 1)
                .containsEntry("dists/stable/Release.gpg", 1);

        mirror.move("Package: widget\nVersion: 2.0\n");
        assertThat(new String(read(store, mirror, "dists/stable/InRelease").responseBytes(), StandardCharsets.UTF_8))
                .as("within the ttl the suite is the release the node remembers").isEqualTo(first);
        ContractExchange packages = read(store, mirror, "dists/stable/" + PACKAGES);
        assertThat(new String(packages.responseBytes(), StandardCharsets.UTF_8))
                .as("the index asked for by name is the one the remembered release names, fetched by its digest")
                .contains("Version: 1.0");
        assertThat(mirror.asked).as("never by its name, which the mirror has moved")
                .doesNotContainKey("dists/stable/" + PACKAGES);

        UpstreamMemory.node().clear();
        read(store, mirror, "dists/stable/InRelease");
        assertThat(new String(read(store, mirror, "dists/stable/" + PACKAGES).responseBytes(), StandardCharsets.UTF_8))
                .as("once forgotten, the suite and its index move together").contains("Version: 2.0");
    }

    @Test
    void a_suite_without_by_hash_paths_is_relayed_fresh() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror(false, "Package: widget\nVersion: 1.0\n");
        read(store, mirror, "dists/stable/InRelease");
        read(store, mirror, "dists/stable/InRelease");
        assertThat(read(store, mirror, "dists/stable/" + PACKAGES).status()).isEqualTo(200);

        assertThat(mirror.asked).as("nothing of a suite that cannot be pinned is remembered, and each read costs the "
                + "one fetch a fresh relay makes").containsEntry("dists/stable/InRelease", 2)
                .containsEntry("dists/stable/" + PACKAGES, 1)
                .doesNotContainKeys("dists/stable/Release", "dists/stable/Release.gpg");
    }
}
