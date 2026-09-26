package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Alpine proxy leg's integrity, over real packages. An upstream index declares each package's {@code C:}, a
 * checksum of its control member only; the control member declares the {@code datahash} of the data. A package is
 * cached only when both hold, so a byte changed anywhere in it is refused - which an arbitrary body, the shared kit's
 * subject, cannot show.
 */
class ApkProxyTest {

    private static final URI ROOT = URI.create("https://alpine.invalid/v3.20/main/");
    private static final String FILE = "widget-1.0.0-r0.apk";
    private static final String REQUEST = "/apk/main/x86_64/" + FILE;

    @TempDir
    Path root;

    @Test
    void a_package_matching_its_index_is_cached_and_served() throws IOException {
        byte[] apk = Packages.apk("widget", "1.0.0-r0", "x86_64");
        ArtifactStore store = store("honest");
        ContractExchange exchange = proxy(store, apk, entry(checksum(apk)));

        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.responseBytes()).isEqualTo(apk);
        assertThat(get(store, REQUEST).status()).as("cached, so the next read is local").isEqualTo(200);
    }

    @Test
    void a_package_whose_control_member_differs_from_the_index_is_refused() throws IOException {
        byte[] apk = Packages.apk("widget", "1.0.0-r0", "x86_64");
        ArtifactStore store = store("checksum");
        ContractExchange exchange = proxy(store, apk, entry("Q1" + "A".repeat(27) + "="));

        assertThat(exchange.status()).isNotEqualTo(200);
        assertThat(get(store, REQUEST).status()).isEqualTo(404);
    }

    @Test
    void a_package_whose_data_differs_from_its_datahash_is_refused() throws IOException {
        byte[] apk = Packages.apk("widget", "1.0.0-r0", "x86_64");
        String checksum = checksum(apk);
        byte[] tampered = apk.clone();
        tampered[tampered.length - 20] ^= 0x01;       // inside the data member: C: still matches
        ArtifactStore store = store("datahash");
        ContractExchange exchange = proxy(store, tampered, entry(checksum));

        assertThat(exchange.status()).isNotEqualTo(200);
        assertThat(get(store, REQUEST).status()).isEqualTo(404);
    }

    @Test
    void an_unpublished_repository_misses_so_its_index_can_be_proxied() throws IOException {
        assertThat(get(store("empty"), "/apk/main/x86_64/APKINDEX.tar.gz").status()).isEqualTo(404);
    }

    /** Proxy one request for {@link #FILE} against an upstream serving {@code apk} and an index of {@code entries}. */
    private static ContractExchange proxy(ArtifactStore store, byte[] apk, String entries) throws IOException {
        byte[] index = index(entries);
        ProxyFormat.Fetcher fetcher = new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (url.equals(ROOT.resolve("x86_64/APKINDEX.tar.gz"))) {
                    return Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(index), Map.of()));
                }
                return url.equals(ROOT.resolve("x86_64/" + FILE))
                        ? Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(apk), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
        ContractExchange exchange = ContractExchange.of("GET", REQUEST);
        ((ProxyFormat) apk()).proxy(exchange, store, ROOT, fetcher);
        return exchange;
    }

    /** One index block for the package, declaring {@code checksum}. */
    private static String entry(String checksum) {
        return "C:" + checksum + "\nP:widget\nV:1.0.0-r0\nA:x86_64\n";
    }

    /** An {@code APKINDEX.tar.gz} holding {@code entries} as its index. */
    static byte[] index(String entries) {
        try {
            byte[] text = entries.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
                TarArchiveEntry member = new TarArchiveEntry("APKINDEX");
                member.setSize(text.length);
                tar.putArchiveEntry(member);
                tar.write(text);
                tar.closeArchiveEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code C:} of the package {@link Packages#apk} builds: SHA-1 over its control member, behind {@code Q1}. */
    private static String checksum(byte[] apk) throws IOException {
        byte[] control = Packages.apkMembers("widget", "1.0.0-r0", "x86_64")[0];
        assertThat(Arrays.copyOf(apk, control.length)).as("the package begins with its control member")
                .isEqualTo(control);
        try {
            return "Q1" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(control));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RepositoryFormat apk() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("apk")).findFirst().orElseThrow();
    }

    private static ContractExchange get(ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        apk().handle(exchange, store);
        return exchange;
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
