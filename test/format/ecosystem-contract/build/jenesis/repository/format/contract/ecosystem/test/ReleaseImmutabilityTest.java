package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A released version of a PyPI, NuGet, RubyGems or Cargo package keeps its bytes: every public registry of the four
 * refuses a second upload of a version, and a consumer pinning hashes ({@code pip --require-hashes},
 * {@code Cargo.lock}, NuGet's lock file) breaks the day the bytes under a version change. Each of these formats knows
 * the version an upload collides on only inside its layout, once the envelope or the package has been read, so the
 * refusal is taken at the version's pointer, inside its compare-and-set - and answered as the format's own registry
 * answers it, which is what its client recognises ({@code twine upload --skip-existing} reads PyPI's {@code 400} and
 * its words, {@code dotnet nuget push --skip-duplicate} NuGet's {@code 409}).
 *
 * <p>The refusal leaves the release as it was, and that is checked over the whole store rather than over the one
 * pointer: every key the release had keeps its content, since a sidecar keyed by the version - NuGet's dependency
 * groups, a gem's quick spec and attestations - written before the pointer refused would change the release as
 * surely as moving the pointer would.
 */
class ReleaseImmutabilityTest {

    @TempDir
    Path root;

    /** One format's release, uploaded through its own write path with bytes {@code variant} makes distinct. */
    private record Format(String name, EcosystemFormatFixture fixture, int accepted, int refusal, String words,
                          String served, Uploader uploader) {

        @Override
        public String toString() {
            return name;
        }

        Upload upload(ArtifactStore store, String variant) throws IOException {
            Upload upload = uploader.upload(variant);
            fixture.serving().handle(upload.exchange(), store);
            return upload;
        }

        byte[] serve(ArtifactStore store) throws IOException {
            ContractExchange get = ContractExchange.of("GET", served);
            fixture.serving().handle(get, store);
            assertThat(get.status()).as("%s serves the release", name).isEqualTo(200);
            return get.responseBytes();
        }
    }

    private record Upload(ContractExchange exchange, byte[] artifact) {
    }

    @FunctionalInterface
    private interface Uploader {
        Upload upload(String variant) throws IOException;
    }

    static List<Format> formats() {
        String boundary = "release-boundary";
        return List.of(
                new Format("pypi", new PyPiFormatFixture(), 200, 400, "File already exists",
                        "/pypi/simple/release-lib/release_lib-1.0.0-py3-none-any.whl", variant -> {
                            byte[] wheel = ("a wheel " + variant).getBytes(StandardCharsets.UTF_8);
                            return new Upload(ContractExchange.of("POST", "/pypi/", Packages.twineForm(boundary,
                                            "release-lib", "release_lib-1.0.0-py3-none-any.whl", wheel))
                                    .header("Content-Type", "multipart/form-data; boundary=" + boundary), wheel);
                        }),
                new Format("nuget", new NuGetFormatFixture(), 201, 409, "already exists",
                        "/nuget/v3-flatcontainer/release.lib/1.0.0/release.lib.1.0.0.nupkg", variant -> {
                            byte[] nupkg = Packages.nupkg("release.lib", "1.0.0", variant);
                            return new Upload(ContractExchange.of("PUT", "/nuget/v3/package", nupkg), nupkg);
                        }),
                new Format("gems", new RubyGemsFormatFixture(), 200, 409, "Repushing of gem versions is not allowed",
                        "/rubygems/gems/release-lib-1.0.0.gem", variant -> {
                            byte[] gem = Packages.gem("release-lib", "1.0.0", variant);
                            return new Upload(ContractExchange.of("POST", "/rubygems/api/v1/gems", gem), gem);
                        }),
                new Format("cargo", new CargoFormatFixture(), 200, 400, "is already uploaded",
                        "/cargo/release/api/v1/crates/release-lib/1.0.0/download", variant -> {
                            byte[] crate = ("a crate " + variant).getBytes(StandardCharsets.UTF_8);
                            return new Upload(ContractExchange.of("PUT", "/cargo/release/api/v1/crates/new",
                                    Packages.cargoFrame("release-lib", "1.0.0", crate)), crate);
                        }));
    }

    /** The three formats whose own publish runs the screen, so a held upload is laid out by the format itself. */
    static List<Format> screening() {
        return formats().stream().filter(format -> !format.name().equals("gems")).toList();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void a_second_upload_of_a_released_version_is_refused_and_the_release_is_untouched(Format format)
            throws IOException {
        ArtifactStore store = store(format.name());
        Upload first = format.upload(store, "");
        assertThat(first.exchange().status()).as("the first upload of %s lands", format).isEqualTo(format.accepted());
        Map<String, byte[]> released = contents(format.name());

        Upload second = format.upload(store, "rebuilt");

        assertThat(second.exchange().status()).as("%s refuses the version as its registry does", format)
                .isEqualTo(format.refusal());
        assertThat(second.exchange().responseText()).contains(format.words());
        assertThat(format.serve(store)).as("%s still serves the first bytes", format).isEqualTo(first.artifact());
        assertThat(changed(released, contents(format.name())))
                .as("no key the release had is rewritten by a refused upload of %s", format).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void an_identical_re_upload_converges(Format format) throws IOException {
        ArtifactStore store = store(format.name());
        Upload first = format.upload(store, "");

        Upload again = format.upload(store, "");

        assertThat(again.exchange().status()).as("an upload whose answer was lost can be sent again")
                .isEqualTo(format.accepted());
        assertThat(format.serve(store)).isEqualTo(first.artifact());
    }

    @ParameterizedTest
    @MethodSource("formats")
    void racing_first_uploads_land_exactly_one(Format format) throws Exception {
        ArtifactStore store = store(format.name());
        int rivals = 12;
        List<Upload> uploads = new ArrayList<>();
        for (int rival = 0; rival < rivals; rival++) {
            uploads.add(format.uploader().upload("rival " + rival));
        }
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = new ArrayList<>();
            for (Upload upload : uploads) {
                sent.add(executor.submit(() -> {
                    start.await();
                    format.fixture().serving().handle(upload.exchange(), store);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : sent) {
                future.get();
            }
        }

        List<Integer> statuses = uploads.stream().map(upload -> upload.exchange().status()).toList();
        assertThat(statuses).as("one first upload of %s lands, every rival is refused", format)
                .containsOnly(format.accepted(), format.refusal())
                .filteredOn(status -> status == format.accepted()).hasSize(1);
        Upload winner = uploads.stream().filter(upload -> upload.exchange().status() == format.accepted())
                .findFirst().orElseThrow();
        assertThat(format.serve(store)).as("%s serves the bytes of the upload told it landed", format)
                .isEqualTo(winner.artifact());
    }

    @ParameterizedTest
    @MethodSource("screening")
    void a_held_upload_of_a_released_version_is_refused_before_anything_is_held(Format format) throws IOException {
        ArtifactStore store = store(format.name());
        Upload first = format.upload(store, "");
        Map<String, byte[]> released = contents(format.name());

        Upload held;
        ContractHoldInterceptor.QUARANTINE_UPLOADS.set(true);
        try {
            held = format.upload(store, "rebuilt");
        } finally {
            ContractHoldInterceptor.QUARANTINE_UPLOADS.set(false);
        }

        assertThat(held.exchange().status()).as("a hold never replaces a released %s", format)
                .isEqualTo(format.refusal());
        assertThat(format.serve(store)).isEqualTo(first.artifact());
        Map<String, byte[]> after = contents(format.name());
        assertThat(changed(released, after)).as("no key the release had is rewritten").isEmpty();
        assertThat(after.keySet()).as("and no hold is left behind for a version that cannot be released")
                .noneMatch(key -> key.startsWith("withheld/") || key.startsWith("publish/quarantine"));
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }

    /** Every file under a store's root, by its path relative to the root. */
    private Map<String, byte[]> contents(String name) throws IOException {
        Path directory = root.resolve(name);
        Map<String, byte[]> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(directory.relativize(file).toString().replace(File.separatorChar, '/'),
                        Files.readAllBytes(file));
            }
        }
        return contents;
    }

    /** The keys of {@code before} whose content is gone or different in {@code after}. */
    private static List<String> changed(Map<String, byte[]> before, Map<String, byte[]> after) {
        return before.entrySet().stream()
                .filter(entry -> !Arrays.equals(entry.getValue(), after.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .toList();
    }
}
