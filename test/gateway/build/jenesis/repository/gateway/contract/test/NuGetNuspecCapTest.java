package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.nuget.NuGetFormat;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link NuGetFormat} caps how much of a {@code .nupkg}'s {@code .nuspec} it inflates before the DOM builder, so a
 * deflate-bomb {@code .nuspec} - a tiny ZIP entry that inflates to hundreds of megabytes - is bounded rather than
 * materialised into a multi-GB DOM that OOMs or hangs the process. This closes the same zip-bomb the NuGet compliance
 * inspector was already hardened against (its {@code MAX_NUSPEC}), across both sites the format cracks the archive:
 * the {@code PUT} publish path ({@code coordinate}) and the <em>unauthenticated</em> registration read
 * ({@code dependencyGroups} via {@code dependencyGroupsFor}) a {@code dotnet restore} drives. A well-formed small
 * {@code .nuspec} is unaffected. Mirrors {@code NuGetQualityInspectorTest}'s deflate-bomb test, at the format layer.
 */
class NuGetNuspecCapTest {

    private final NuGetFormat nuget = new NuGetFormat();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_deflate_bomb_nuspec_is_rejected_on_publish_within_the_cap() throws IOException {
        // A .nupkg whose .nuspec is a tiny ZIP entry that inflates to ~256 MiB of valid XML. Were the publish path to
        // parse it unbounded it would either OOM or store a package keyed on a multi-GB coordinate; instead the read is
        // capped, so coordinate() finds no usable manifest and the push fails closed with 400 - never materialised.
        byte[] bomb = deflateBombNupkg(256L * 1024 * 1024);
        assertThat(bomb.length).as("the bomb's compressed body is tiny - the whole point of a deflate bomb")
                .isLessThan(1024 * 1024);

        Exchange push = new Exchange("PUT", "/nuget/v3/package", "application/octet-stream",
                new ByteArrayInputStream(bomb));
        nuget.handle(push, store);

        assertThat(push.status).as("a deflate-bomb .nuspec push is rejected within the cap, not inflated into a DOM")
                .isEqualTo(400);
    }

    @AfterEach
    void restoreConfig() {
        Features.reset();
    }

    @Test
    void the_cap_is_the_shared_operator_settable_ceiling_rather_than_this_format_s_own_constant() throws IOException {
        // The .nuspec read used to be bounded by NuGetFormat's private MAX_NUSPEC - a number that happened to
        // equal the inspector's, parallel by convention, and keyed to nothing an operator could set. It is now the
        // product's one archive-inflation ceiling. Moving the shared key and watching this format's verdict move with
        // it is what proves the format reads THROUGH the shared bound rather than beside it: before the change the
        // key had no effect here at all.
        byte[] pkg = nupkg("Contoso.Lib", "1.2.3", "");
        assertThat(pkg.length).as("an ordinary tiny package, comfortably under the shared default").isLessThan(4096);

        Features.configure(key -> ArchiveInflation.LARGEST_ENTRY_KEY.equals(key) ? "16" : null);
        Exchange lowered = new Exchange("PUT", "/nuget/v3/package", "application/octet-stream",
                new ByteArrayInputStream(pkg));
        nuget.handle(lowered, store);
        assertThat(lowered.status)
                .as("the operator lowered jenreg.archive.largest-entry below a .nuspec, so the coordinate cannot be "
                        + "read and the push fails CLOSED - never from the prefix that fitted under the ceiling")
                .isEqualTo(400);

        Features.reset();
        Exchange restored = new Exchange("PUT", "/nuget/v3/package", "application/octet-stream",
                new ByteArrayInputStream(pkg));
        nuget.handle(restored, store);
        assertThat(restored.status).as("and the very same bytes publish under the default ceiling").isEqualTo(201);
    }

    @Test
    void a_normal_nupkg_still_publishes_and_its_registration_reads_the_dependency() throws IOException {
        // The cap must not change what a well-formed small .nuspec does: it publishes 201 and the registration read
        // (the unauthenticated restore path) still parses its dependency groups from the .nuspec through the same cap.
        byte[] pkg = nupkg("Contoso.Lib", "1.2.3",
                "<dependencies><group targetFramework=\"net8.0\">"
                        + "<dependency id=\"Newtonsoft.Json\" version=\"13.0.1\" /></group></dependencies>");
        Exchange push = new Exchange("PUT", "/nuget/v3/package", "application/octet-stream",
                new ByteArrayInputStream(pkg));
        nuget.handle(push, store);
        assertThat(push.status).as("a well-formed small .nuspec publishes unchanged").isEqualTo(201);

        Exchange registration = new Exchange("GET", "/nuget/v3/registrations/contoso.lib/index.json", null,
                InputStream.nullInputStream());
        nuget.handle(registration, store);
        assertThat(registration.status).isEqualTo(200);
        assertThat(registration.body()).as("the small .nuspec's dependency is still read through the cap")
                .contains("\"version\":\"1.2.3\"").contains("Newtonsoft.Json").contains("\"range\":\"13.0.1\"");
    }

    @Test
    void the_unauthenticated_registration_read_bounds_a_stored_deflate_bomb() throws IOException {
        // A .nupkg cached by the pull-through proxy is stored WITHOUT being parsed, so a bomb can reach the store even
        // though the publish path rejects it. The unauthenticated registration read (dependencyGroupsFor) then reopens
        // it; the cap bounds that read, so a bomb whose dependency payload inflates to ~256 MiB yields empty groups and
        // a tiny response rather than a multi-GB DOM/JSON that OOMs or hangs. Planted the way proxy() caches a .nupkg.
        new Blobs(store).write("nuget/evil.pkg/1.0.0/evil.pkg.1.0.0.nupkg", deflateBombNupkg(256L * 1024 * 1024));

        Exchange registration = new Exchange("GET", "/nuget/v3/registrations/evil.pkg/index.json", null,
                InputStream.nullInputStream());
        nuget.handle(registration, store);

        assertThat(registration.status).isEqualTo(200);
        assertThat(registration.body().length())
                .as("the registration response is bounded - the bomb's payload never reached the DOM/JSON")
                .isLessThan(64 * 1024);
        assertThat(registration.body()).contains("\"version\":\"1.0.0\"").contains("\"dependencyGroups\":[]");
    }

    /** A minimal .nupkg: a ZIP whose only entry is the {@code .nuspec} the format reads the id/version and dependency
     *  groups from. */
    private static byte[] nupkg(String id, String version, String dependencies) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(id + ".nuspec"));
            zip.write(("<?xml version=\"1.0\"?><package><metadata>"
                    + "<id>" + id + "</id><version>" + version + "</version>"
                    + dependencies
                    + "</metadata></package>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** A .nupkg whose single {@code .nuspec} entry is valid XML that inflates to {@code padding} bytes from a tiny
     *  compressed footprint - a repeated-byte fill inside a {@code <dependency>} version so an <em>unbounded</em> parse
     *  would materialise the fill into the DOM (and, on the registration path, into the response JSON), the deflate-bomb
     *  shape the format must bound within its read cap. The id/version stay small, so an unbounded publish parse would
     *  otherwise succeed - the cap is what makes the bomb observably fail closed. */
    private static byte[] deflateBombNupkg(long padding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Evil.Pkg.nuspec"));
            zip.write(("<?xml version=\"1.0\"?><package><metadata>"
                    + "<id>Evil.Pkg</id><version>1.0.0</version>"
                    + "<dependencies><group targetFramework=\"net8.0\"><dependency id=\"Filler\" version=\"")
                    .getBytes(StandardCharsets.UTF_8));
            byte[] chunk = new byte[1024 * 1024];
            Arrays.fill(chunk, (byte) 'A');
            for (long written = 0; written < padding; written += chunk.length) {
                zip.write(chunk, 0, (int) Math.min(chunk.length, padding - written));
            }
            zip.write("\" /></group></dependencies></metadata></package>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** A {@link FormatExchange} that streams a request body and captures the response status and body. */
    private static final class Exchange implements FormatExchange {

        private final String method;
        private final String path;
        private final Map<String, String> headers = new HashMap<>();
        private final InputStream body;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private int status = -1;

        private Exchange(String method, String path, String contentType, InputStream body) {
            this.method = method;
            this.path = path;
            this.body = body;
            headers.put("Host", "localhost");
            if (contentType != null) {
                headers.put("Content-Type", contentType);
            }
        }

        String body() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();
                }
            }
            return null;
        }

        @Override
        public InputStream requestStream() {
            return body;
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return captured;
        }
    }
}
