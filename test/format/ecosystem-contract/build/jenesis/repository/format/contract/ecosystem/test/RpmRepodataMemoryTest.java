package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.UpstreamMemory;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied RPM repository's {@code repomd.xml} is remembered with its signature and key for
 * {@code jenrepo.cache.upstream-ttl} where every file it names carries its checksum in its name, so the remembered
 * document agrees with the files it names; a named file the upstream has removed makes the node forget it. A
 * repository whose metadata names carry no checksum is relayed fresh.
 */
class RpmRepodataMemoryTest {

    private static final URI ROOT = URI.create("https://rpm.invalid/");
    private static final String REPODATA = "os/repodata/";

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }

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

    /** An upstream whose metadata revision can move, counting what it was asked for. */
    private static final class Mirror implements ProxyFormat.Fetcher.Buffered {

        final Map<String, Integer> asked = new ConcurrentHashMap<>();
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final boolean unique;
        volatile String revision;

        Mirror(boolean unique, String revision) {
            this.unique = unique;
            publish(revision);
        }

        String primary(String revision) {
            return REPODATA + (unique ? checksum(revision) + "-" : "") + "primary.xml.gz";
        }

        void publish(String revision) {
            this.revision = revision;
            files.put(primary(revision), revision.getBytes(StandardCharsets.UTF_8));
            String repomd = "<repomd xmlns=\"http://linux.duke.edu/metadata/repo\"><data type=\"primary\">"
                    + "<checksum type=\"sha256\">" + checksum(revision) + "</checksum><location href=\"repodata/"
                    + primary(revision).substring(REPODATA.length()) + "\"/></data></repomd>";
            files.put(REPODATA + "repomd.xml", repomd.getBytes(StandardCharsets.UTF_8));
            files.put(REPODATA + "repomd.xml.asc", ("signature of " + revision).getBytes(StandardCharsets.UTF_8));
        }

        static String checksum(String revision) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(revision.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
            String path = ROOT.relativize(url).toString();
            asked.merge(path, 1, Integer::sum);
            byte[] body = files.get(path);
            return Optional.of(body == null ? new ProxyFormat.Fetched(404, new byte[0], Map.of())
                    : new ProxyFormat.Fetched(200, body, Map.of()));
        }
    }

    private static RepositoryFormat rpm() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("rpm")).findFirst().orElseThrow();
    }

    private static ContractExchange read(ArtifactStore store, Mirror mirror, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", "/rpm/" + path);
        ((ProxyFormat) rpm()).proxy(exchange, store, ROOT, mirror);
        return exchange;
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static String text(ContractExchange exchange) {
        return new String(exchange.responseBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void a_uniquely_named_repodata_is_remembered_and_forgotten_once_what_it_names_is_gone() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror(true, "revision 1");
        String first = text(read(store, mirror, REPODATA + "repomd.xml"));
        assertThat(read(store, mirror, REPODATA + "repomd.xml.asc").status()).isEqualTo(200);
        assertThat(mirror.asked).as("the repomd.xml, its signature and its key were fetched once, together")
                .containsEntry(REPODATA + "repomd.xml", 1).containsEntry(REPODATA + "repomd.xml.asc", 1)
                .containsEntry(REPODATA + "repomd.xml.key", 1);

        String named = mirror.primary("revision 1");
        mirror.publish("revision 2");
        assertThat(text(read(store, mirror, REPODATA + "repomd.xml"))).as("within the ttl, the remembered one")
                .isEqualTo(first);
        assertThat(text(read(store, mirror, named))).as("and the file it names, still at its name upstream")
                .isEqualTo("revision 1");

        mirror.files.remove(named);
        read(store, mirror, named);
        assertThat(text(read(store, mirror, REPODATA + "repomd.xml")))
                .as("a named file gone from the upstream forgets the remembered repomd.xml").contains(
                        Mirror.checksum("revision 2"));
    }

    @Test
    void a_repodata_whose_names_carry_no_checksum_is_relayed_fresh() throws IOException {
        ArtifactStore store = store();
        Mirror mirror = new Mirror(false, "revision 1");
        read(store, mirror, REPODATA + "repomd.xml");
        read(store, mirror, REPODATA + "repomd.xml");

        assertThat(mirror.asked).as("each read costs the one fetch a fresh relay makes, and nothing else is fetched")
                .containsEntry(REPODATA + "repomd.xml", 2)
                .doesNotContainKeys(REPODATA + "repomd.xml.asc", REPODATA + "repomd.xml.key");
    }
}
