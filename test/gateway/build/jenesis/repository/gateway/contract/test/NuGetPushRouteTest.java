package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A NuGet package push is accepted at the one address the service index advertises as {@code PackagePublish/2.0.0}
 * and refused everywhere else.
 *
 * <p>The coordinate a NuGet push lands under comes from the {@code .nuspec} inside the package, not from the request
 * path, so the format used to read <em>every</em> {@code PUT} under {@code /nuget/} as a push - including one aimed at
 * a read address such as {@code v3/index.json} or a flat-container file, and one aimed at an endpoint that has never
 * existed. Each published and each answered {@code 201}. Nothing landed at a wrong key and nothing traversed (a
 * {@code .}/{@code ..} path is a 404 here); what was lost is the refusal, so a client configured against
 * an endpoint this repository never offered was told its pushes had succeeded.
 *
 * <p>The positive leg is the load-bearing half: a route screen that refused the real push endpoint too would satisfy
 * every negative assertion here.
 */
class NuGetPushRouteTest {

    private static final String BOUNDARY = "BoUnDaRyNuGetPushRouteTest";

    /** The pointer key the layout resolves for the package this suite pushes. */
    private static final String POINTER = "nuget/routepkg/1.0.0/routepkg.1.0.0.nupkg";

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");
    }

    @Test
    void the_advertised_push_endpoint_publishes() throws IOException {
        ArtifactStore store = store();
        PushExchange push = put("/nuget/v3/package");

        nuget().handle(push, store);

        assertThat(push.status).as("the advertised PackagePublish address still accepts a push").isEqualTo(201);
        assertThat(store.exists(POINTER)).as("and the package really landed").isTrue();
    }

    @Test
    void a_trailing_slash_on_the_advertised_endpoint_is_the_same_endpoint() throws IOException {
        ArtifactStore store = store();
        PushExchange push = put("/nuget/v3/package/");

        nuget().handle(push, store);

        assertThat(push.status)
                .as("a client that appends a slash to the resource @id is at the same resource, not at an unknown one")
                .isEqualTo(201);
        assertThat(store.exists(POINTER)).isTrue();
    }

    @Test
    void a_push_at_an_address_the_service_index_never_offered_is_refused_and_stores_nothing() throws IOException {
        for (String path : List.of(
                "/nuget/v3/packages",                                       // a plausible typo
                "/nuget/api/v2/package",                                    // the v2 protocol this format does not speak
                "/nuget/v3/index.json",                                     // a read address
                "/nuget/v3-flatcontainer/routepkg/1.0.0/routepkg.1.0.0.nupkg",  // the package's own read address
                "/nuget/")) {
            ArtifactStore store = store();
            PushExchange push = put(path);

            nuget().handle(push, store);

            assertThat(push.status).as("PUT %s is not a push endpoint, so it addresses nothing here", path)
                    .isEqualTo(404);
            assertThat(store.exists(POINTER))
                    .as("PUT %s published the package anyway - the coordinate comes from the .nuspec, so a push at "
                            + "an unoffered address lands a perfectly valid artifact nobody was pointed at", path)
                    .isFalse();
        }
    }

    @Test
    void the_refused_read_address_still_answers_its_own_verb() throws IOException {
        // The screen is about the verb at that address, not about the address: a GET of the service index still
        // works, and it is what advertises the one endpoint the push leg accepts.
        ArtifactStore store = store();
        ReadExchange read = new ReadExchange("/nuget/v3/index.json");

        nuget().handle(read, store);

        assertThat(read.status).isEqualTo(200);
        assertThat(read.body.toString(StandardCharsets.UTF_8))
                .as("the advertised PackagePublish resource is the address the push leg accepts, stated once")
                .contains("\"PackagePublish/2.0.0\"")
                .contains("/nuget/v3/package");
    }

    /** The discovered {@link RepositoryFormat} (the modules are not exported, so a test reaches them
     *  through the same {@link ServiceLoader} seam the server uses). */
    private static RepositoryFormat nuget() {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals("nuget")) {
                return format;
            }
        }
        throw new AssertionError("no format named nuget on the module path");
    }

    private static PushExchange put(String path) throws IOException {
        return new PushExchange(path, multipart(nupkg()));
    }

    /** A minimal .nupkg: a zip whose first entry is the {@code .nuspec} the push reads the id/version from. */
    private static byte[] nupkg() throws IOException {
        String nuspec = "<?xml version=\"1.0\"?>\n<package><metadata>"
                + "<id>RoutePkg</id><version>1.0.0</version></metadata></package>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("RoutePkg.nuspec"));
            zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The multipart form a {@code dotnet nuget push} sends, with the package as its single file part. */
    private static byte[] multipart(byte[] nupkg) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"package\"; "
                + "filename=\"RoutePkg.1.0.0.nupkg\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(nupkg);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static final class PushExchange implements FormatExchange {

        private final String path;
        private final byte[] body;
        private int status = -1;

        private PushExchange(String path, byte[] body) {
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return "PUT";
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
            return name.equalsIgnoreCase("Content-Type")
                    ? "multipart/form-data; boundary=" + BOUNDARY
                    : null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return OutputStream.nullOutputStream();
        }
    }

    private static final class ReadExchange implements FormatExchange {

        private final String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;

        private ReadExchange(String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String requestUri() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return body;
        }
    }
}
