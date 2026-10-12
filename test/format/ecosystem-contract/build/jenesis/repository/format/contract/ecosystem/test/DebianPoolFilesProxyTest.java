package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.UpstreamMemory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every file a Debian mirror's pool holds is cached by the proxy, not only a binary {@code .deb}: an installer
 * {@code .udeb} and a source package's {@code .dsc}, {@code .debian.tar.*} and {@code .orig.tar.*}. Each is held to the
 * digest the index that names it declared - a {@code Packages} index for a binary, a {@code Sources} index for a
 * source file - served from the store once kept, and listed among its version's files, so a hold or an eviction of
 * the version reaches it.
 */
class DebianPoolFilesProxyTest {

    private static final URI ROOT = URI.create("https://deb.invalid/debian/");
    private static final String DIRECTORY = "pool/main/h/hello";
    private static final byte[] DEB = bytes("the hello binary package");
    private static final byte[] UDEB = bytes("the hello installer package");
    private static final byte[] DSC = bytes("Format: 3.0 (quilt)\nSource: hello\nVersion: 2.10-3\n");
    private static final byte[] ORIG = bytes("the upstream tarball");
    private static final byte[] DEBIAN = bytes("the packaging tarball");

    @TempDir
    Path root;

    @BeforeEach
    void forget() {
        UpstreamMemory.reset();
    }

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
        UpstreamMemory.reset();
    }

    /** An upstream mirror answering each path from a map, or {@code 404}, counting what it was asked for. */
    private static final class Mirror implements ProxyFormat.Fetcher.Buffered {

        final Map<String, byte[]> served = new ConcurrentHashMap<>();
        final Map<String, Integer> asked = new ConcurrentHashMap<>();

        Mirror() {
            served.put("dists/stable/main/binary-amd64/Packages", bytes("Package: hello\nVersion: 2.10-3\n"
                    + "Filename: " + DIRECTORY + "/hello_2.10-3_amd64.deb\nSHA256: " + digest(DEB) + "\n"));
            served.put("dists/stable/main/debian-installer/binary-amd64/Packages", bytes("Package: hello-udeb\n"
                    + "Version: 2.10-3\nFilename: " + DIRECTORY + "/hello-udeb_2.10-3_amd64.udeb\nSHA256: "
                    + digest(UDEB) + "\n"));
            served.put("dists/stable/main/source/Sources", bytes("Package: hello\nVersion: 2.10-3\n"
                    + "Directory: " + DIRECTORY + "\nChecksums-Sha256:\n"
                    + " " + digest(DSC) + " " + DSC.length + " hello_2.10-3.dsc\n"
                    + " " + digest(ORIG) + " " + ORIG.length + " hello_2.10.orig.tar.gz\n"
                    + " " + digest(DEBIAN) + " " + DEBIAN.length + " hello_2.10-3.debian.tar.xz\n"
                    + "\nPackage: other\nVersion: 1.0-1\nDirectory: pool/main/o/other\nChecksums-Sha256:\n"
                    + " " + digest(bytes("other")) + " 5 other_1.0-1.dsc\n"));
            served.put(DIRECTORY + "/hello_2.10-3_amd64.deb", DEB);
            served.put(DIRECTORY + "/hello-udeb_2.10-3_amd64.udeb", UDEB);
            served.put(DIRECTORY + "/hello_2.10-3.dsc", DSC);
            served.put(DIRECTORY + "/hello_2.10.orig.tar.gz", ORIG);
            served.put(DIRECTORY + "/hello_2.10-3.debian.tar.xz", DEBIAN);
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
            String path = url.toString().substring(ROOT.toString().length());
            asked.merge(path, 1, Integer::sum);
            byte[] body = served.get(path);
            return Optional.of(body == null ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        }
    }

    private static final List<String> POOL_FILES = List.of("hello_2.10-3_amd64.deb", "hello-udeb_2.10-3_amd64.udeb",
            "hello_2.10-3.dsc", "hello_2.10.orig.tar.gz", "hello_2.10-3.debian.tar.xz");

    @Test
    void every_pool_file_is_kept_once_its_index_has_passed_and_is_then_served_from_the_store() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror();
        relayIndexes(store, mirror);
        for (String file : POOL_FILES) {
            ContractExchange fetched = proxy(store, mirror, DIRECTORY + "/" + file);
            assertThat(fetched.status()).as("%s is answered through the proxy", file).isEqualTo(200);
            assertThat(fetched.responseBytes()).isEqualTo(mirror.served.get(DIRECTORY + "/" + file));
        }

        for (String file : POOL_FILES) {
            ContractExchange local = ContractExchange.of("GET", "/debian/" + DIRECTORY + "/" + file);
            debian().handle(local, store);
            assertThat(local.status()).as("%s is served from the store, the upstream not asked again", file)
                    .isEqualTo(200);
            assertThat(local.responseBytes()).isEqualTo(mirror.served.get(DIRECTORY + "/" + file));
            assertThat(mirror.asked).as("%s was fetched once", file).containsEntry(DIRECTORY + "/" + file, 1);
        }
    }

    @Test
    void a_pool_file_whose_bytes_differ_from_its_declared_digest_is_refused_and_not_kept() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror();
        relayIndexes(store, mirror);
        for (String file : POOL_FILES) {
            mirror.served.put(DIRECTORY + "/" + file, bytes("tampered " + file));
        }
        for (String file : POOL_FILES) {
            ContractExchange exchange = ContractExchange.of("GET", "/debian/" + DIRECTORY + "/" + file);
            assertThat(((ProxyFormat) debian()).proxy(exchange, store, ROOT, mirror))
                    .as("%s does not match the digest its index declared", file).isFalse();
            assertThat(store.exists("debian/" + DIRECTORY + "/" + file)).as("%s is not kept", file).isFalse();
        }
    }

    @Test
    void a_kept_pool_file_is_one_of_its_versions_files() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror();
        relayIndexes(store, mirror);
        for (String file : POOL_FILES) {
            proxy(store, mirror, DIRECTORY + "/" + file);
        }
        BlobLayout layout = (BlobLayout) debian();

        assertThat(layout.blobKeys("hello", "2.10-3", store))
                .as("the binary and the source package's own files, under one name and version")
                .containsExactlyInAnyOrder("debian/" + DIRECTORY + "/hello_2.10-3_amd64.deb",
                        "debian/" + DIRECTORY + "/hello_2.10-3.dsc",
                        "debian/" + DIRECTORY + "/hello_2.10-3.debian.tar.xz");
        assertThat(layout.blobKeys("hello", "2.10", store)).as("the upstream tarball, by the version it names")
                .containsExactly("debian/" + DIRECTORY + "/hello_2.10.orig.tar.gz");
        assertThat(layout.blobKeys("hello-udeb", "2.10-3", store)).as("the installer package")
                .containsExactly("debian/" + DIRECTORY + "/hello-udeb_2.10-3_amd64.udeb");
        assertThat(layout.servedPaths("hello", "2.10-3", store)).as("each as the path it is served from")
                .contains("/debian/" + DIRECTORY + "/hello_2.10-3.dsc");
    }

    @Test
    void a_pool_file_kept_before_its_version_listed_files_is_found_by_the_walk() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror();
        relayIndexes(store, mirror);
        for (String file : POOL_FILES) {
            proxy(store, mirror, DIRECTORY + "/" + file);
        }
        // The versions' documents gone - as for a version kept before its files were listed - the pool walk answers.
        Path documents = root.resolve(MetadataKey.PREFIX);
        assertThat(documents).as("the versions' documents the fills wrote").isDirectory();
        try (Stream<Path> walk = Files.walk(documents)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }

        assertThat(((BlobLayout) debian()).blobKeys("hello", "2.10-3", store))
                .as("the walk reaches the mirror's pool, and reaches no record kept beside it")
                .containsExactlyInAnyOrder("debian/" + DIRECTORY + "/hello_2.10-3_amd64.deb",
                        "debian/" + DIRECTORY + "/hello_2.10-3.dsc",
                        "debian/" + DIRECTORY + "/hello_2.10-3.debian.tar.xz");
    }

    @Test
    void a_pool_file_is_described_by_its_package_and_version() {
        RepositoryFormat debian = debian();
        assertThat(((BlobLayout) debian).describe("/debian/" + DIRECTORY + "/hello-udeb_2.10-3_amd64.udeb"))
                .map(ArtifactDescriptor::coordinate).contains("hello-udeb");
        assertThat(((BlobLayout) debian).describePointer("debian/" + DIRECTORY + "/hello_2.10-3.dsc"))
                .map(described -> described.coordinate() + " " + described.version()).contains("hello 2.10-3");
        assertThat(((BlobLayout) debian).describePointer("debian/" + DIRECTORY + "/hello-udeb_2.10-3_amd64.udeb"))
                .map(described -> described.coordinate() + " " + described.version()).contains("hello-udeb 2.10-3");
    }

    private static void relayIndexes(ArtifactStore store, Mirror mirror) throws IOException {
        for (String index : List.of("dists/stable/main/binary-amd64/Packages",
                "dists/stable/main/debian-installer/binary-amd64/Packages", "dists/stable/main/source/Sources")) {
            assertThat(proxy(store, mirror, index).status()).as("%s is relayed", index).isEqualTo(200);
        }
    }

    private static ContractExchange proxy(ArtifactStore store, Mirror mirror, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", "/debian/" + path);
        ((ProxyFormat) debian()).proxy(exchange, store, ROOT, mirror);
        return exchange;
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static RepositoryFormat debian() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("debian")).findFirst().orElseThrow();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
