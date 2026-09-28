package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A released version keeps its bytes - a PyPI, NuGet, RubyGems, Cargo, npm, conda, Debian, RPM, Alpine, Composer or
 * CocoaPods package, a Go module version, a Helm chart, a Swift release, a Conan revision's file, a Homebrew bottle,
 * a winget installer, a Terraform module or provider archive, a Hugging Face file at a commit: the public registry
 * of each refuses a second upload of a version, or names the version by its content, and a consumer pinning hashes
 * ({@code pip --require-hashes}, {@code Cargo.lock}, NuGet's lock file) breaks the day the bytes under a version
 * change. Each of these formats links the file under its own pointer through {@code Blobs.linkRelease}, so the
 * refusal is taken there, inside the pointer's compare-and-set - which is what makes one of two racing first uploads
 * land and the other be refused - and answered as the format's own registry answers it, which is what its client
 * recognises ({@code twine upload --skip-existing} reads PyPI's {@code 400} and its words,
 * {@code dotnet nuget push --skip-duplicate} NuGet's {@code 409}). {@code ReleaseImmutabilityCensusTest} holds every
 * format that keeps its release to a row here, or to a stated reason it has none.
 *
 * <p>The refusal leaves the release as it was, and that is checked over the whole store rather than over the one
 * pointer: every key the release had keeps its content, since a sidecar keyed by the version - NuGet's dependency
 * groups, a gem's quick spec and attestations - written before the pointer refused would change the release as
 * surely as moving the pointer would.
 */
class ReleaseImmutabilityTest {

    @TempDir
    Path root;

    /** Finish any derivation a publish queued - a conda repodata's compressed twin, a Debian index's signed release -
     *  before the {@code @TempDir} under it is deleted. */
    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    /** One format's release, uploaded through its own write path with bytes {@code variant} makes distinct, into a
     *  store {@code arrangement} has prepared - a winget installer belongs to a version whose manifest came first. */
    record Format(String name, EcosystemFormatFixture fixture, int accepted, int refusal, String words,
                          String served, Arrangement arrangement, Uploader uploader) {

        Format(String name, EcosystemFormatFixture fixture, int accepted, int refusal, String words, String served,
               Uploader uploader) {
            this(name, fixture, accepted, refusal, words, served, _ -> { }, uploader);
        }

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

    record Upload(ContractExchange exchange, byte[] artifact) {
    }

    @FunctionalInterface
    interface Uploader {
        Upload upload(String variant) throws IOException;
    }

    @FunctionalInterface
    interface Arrangement {
        void arrange(ArtifactStore store) throws IOException;
    }

    /** An upload of opaque bytes, which every format below that reads nothing inside its artifact takes. */
    private static Uploader opaque(String method, String path, String what) {
        return variant -> {
            byte[] bytes = (what + " " + variant).getBytes(StandardCharsets.UTF_8);
            return new Upload(ContractExchange.of(method, path, bytes), bytes);
        };
    }

    private static final String TERRAFORM_PROVIDER =
            "/terraform/release/providers/acme/widget/1.0.0/terraform-provider-widget_1.0.0_linux_amd64.zip";

    private static final String CONAN_EXPORT = "/conan/release/v2/conans/release-lib/1.0.0/_/_/revisions/"
            + "e4e1703f72ed07c15d73a555ec3a2fa1/files/conan_export.tgz";

    private static final String WINGET_INSTALLER = "/winget/release/installers/Acme.Widget/1.0.0/widget.exe";

    private static final String HUGGING_FACE_COMMIT_FILE = "/huggingface/release/acme/release-model/resolve/"
            + "0123456789abcdef0123456789abcdef01234567/model.safetensors";

    private static final String SWIFT_RELEASE = "/swift/registry/release/lib/1.0.0";

    /** The manifest a winget version is established by, before any installer of it is accepted. */
    private static final byte[] WINGET_MANIFEST = """
            {
              "PackageIdentifier": "Acme.Widget",
              "PackageVersion": "1.0.0",
              "DefaultLocale": { "PackageLocale": "en-US", "Publisher": "Acme", "PackageName": "Widget",
                                 "License": "MIT" },
              "Installers": [
                { "Architecture": "x64", "InstallerType": "exe",
                  "InstallerUrl": "https://acme.invalid/widget.exe",
                  "InstallerSha256": "0000000000000000000000000000000000000000000000000000000000000000" }
              ]
            }""".getBytes(StandardCharsets.UTF_8);

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
                        }),
                new Format("npm", new NpmFormatFixture(), 201, 403, "cannot publish over",
                        "/npm/release-lib/-/release-lib-1.0.0.tgz", variant -> {
                            byte[] tarball = ("a tarball " + variant).getBytes(StandardCharsets.UTF_8);
                            return new Upload(ContractExchange.of("PUT", "/npm/release-lib",
                                    Packages.npmEnvelope("release-lib", "1.0.0", tarball)), tarball);
                        }),
                new Format("go", new GoFormatFixture(), 201, 409, "already published",
                        "/go/example.com/release/@v/v1.0.0.zip", variant -> {
                            byte[] zip = ("a module zip " + variant).getBytes(StandardCharsets.UTF_8);
                            return new Upload(ContractExchange.of("PUT", "/go/example.com/release/@v/v1.0.0.zip", zip),
                                    zip);
                        }),
                new Format("conda", new CondaFormatFixture(), 201, 409, "already exists",
                        "/conda/release/linux-64/release-lib-1.0.0-0.conda", variant -> {
                            byte[] conda = Packages.conda("release-lib", "1.0.0", "0", variant);
                            return new Upload(ContractExchange.of("PUT",
                                    "/conda/release/linux-64/release-lib-1.0.0-0.conda", conda), conda);
                        }),
                new Format("terraform", new TerraformFormatFixture(), 201, 409, "already published",
                        TERRAFORM_PROVIDER, variant -> {
                            byte[] zip = ("a provider binary " + variant).getBytes(StandardCharsets.UTF_8);
                            return new Upload(ContractExchange.of("PUT", TERRAFORM_PROVIDER, zip), zip);
                        }),
                new Format("apk", new ApkFormatFixture(), 201, 409, "already published",
                        "/apk/release/x86_64/release-lib-1.0.0-r0.apk", variant -> {
                            byte[] apk = Packages.apk("release-lib", "1.0.0-r0", "x86_64", variant);
                            return new Upload(ContractExchange.of("PUT",
                                    "/apk/release/x86_64/release-lib-1.0.0-r0.apk", apk), apk);
                        }),
                new Format("cocoapods", new CocoaPodsFormatFixture(), 201, 409, "already published",
                        "/cocoapods/release/pods/ReleaseKit/1.0.0/ReleaseKit.zip", variant -> {
                            byte[] pod = Packages.podspec("ReleaseKit", "1.0.0", variant);
                            return new Upload(ContractExchange.of("PUT", "/cocoapods/release/ReleaseKit/1.0.0", pod),
                                    pod);
                        }),
                new Format("composer", new ComposerFormatFixture(), 201, 409, "already published",
                        "/composer/release/dists/acme/widget/1.0.0.zip", variant -> {
                            byte[] zip = Packages.composer("acme/widget", "a release " + variant);
                            return new Upload(ContractExchange.of("PUT", "/composer/release/acme/widget/1.0.0", zip),
                                    zip);
                        }),
                new Format("conan", new ConanFormatFixture(), 201, 409, "already published", CONAN_EXPORT,
                        opaque("PUT", CONAN_EXPORT, "the exported recipe sources")),
                new Format("debian", new DebianFormatFixture(), 201, 409, "already published",
                        "/debian/sid/pool/main/release-lib_1.0.0_amd64.deb", variant -> {
                            byte[] deb = Packages.deb("release-lib", "1.0.0", "amd64", variant);
                            return new Upload(ContractExchange.of("PUT",
                                    "/debian/sid/pool/main/release-lib_1.0.0_amd64.deb", deb), deb);
                        }),
                new Format("helm", new HelmFormatFixture(), 201, 409, "already published",
                        "/helm/release/charts/release-chart-1.0.0.tgz", variant -> {
                            byte[] chart = Packages.helmChart("release-chart", "1.0.0", variant);
                            return new Upload(ContractExchange.of("PUT", "/helm/release/charts/release-chart-1.0.0.tgz",
                                    chart), chart);
                        }),
                new Format("homebrew", new HomebrewFormatFixture(), 201, 409, "already published",
                        "/homebrew/bottles/release-tool-1.0.0.x86_64_linux.bottle.tar.gz",
                        opaque("PUT", "/homebrew/bottles/release-tool-1.0.0.x86_64_linux.bottle.tar.gz", "a bottle")),
                new Format("huggingface", new HuggingFaceFormatFixture(), 201, 409, "already published",
                        HUGGING_FACE_COMMIT_FILE, opaque("PUT", HUGGING_FACE_COMMIT_FILE, "model weights")),
                new Format("rpm", new RpmFormatFixture(), 201, 409, "already published",
                        "/rpm/release/pool/release-lib-1.0.0-1.x86_64.rpm", variant -> {
                            byte[] rpm = Packages.rpm("release-lib", "1.0.0", "1", "x86_64", variant);
                            return new Upload(ContractExchange.of("PUT",
                                    "/rpm/release/pool/release-lib-1.0.0-1.x86_64.rpm", rpm), rpm);
                        }),
                new Format("swift", new SwiftFormatFixture(), 201, 409, "already published", SWIFT_RELEASE + ".zip",
                        variant -> {
                            byte[] archive = Packages.zip(Map.of("lib/Package.swift",
                                    ("// swift-tools-version:5.9\n// " + variant).getBytes(StandardCharsets.UTF_8)));
                            return new Upload(ContractExchange.of("PUT", SWIFT_RELEASE,
                                            Packages.swiftForm("release-boundary", archive, "{}", null))
                                    .header("Content-Type", "multipart/form-data; boundary=release-boundary"),
                                    archive);
                        }),
                new Format("winget", new WingetFormatFixture(), 201, 409, "already published", WINGET_INSTALLER,
                        store -> new WingetFormatFixture().seed(store,
                                ContractExchange.of("PUT", "/winget/release/manifests/Acme.Widget/1.0.0",
                                        WINGET_MANIFEST), 201),
                        opaque("PUT", WINGET_INSTALLER, "an installer")));
    }

    /** The formats whose own publish runs the screen, so a held upload is laid out by the format itself. */
    static List<Format> screening() {
        Set<String> screening = Set.of("pypi", "nuget", "cargo", "npm");
        return formats().stream().filter(format -> screening.contains(format.name())).toList();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void a_second_upload_of_a_released_version_is_refused_and_the_release_is_untouched(Format format)
            throws IOException {
        ArtifactStore store = store(format);
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

    /** npm prints a refusal's reason only from the {@code error} field of a JSON body, so that is where it is. */
    @Test
    void npm_refuses_in_the_document_its_client_reads_the_reason_from() throws IOException {
        Format npm = formats().stream().filter(format -> format.name().equals("npm")).findFirst().orElseThrow();
        ArtifactStore store = store(npm);
        npm.upload(store, "");

        Upload second = npm.upload(store, "rebuilt");

        assertThat(second.exchange().status()).isEqualTo(403);
        assertThat(second.exchange().responseHeader("Content-Type")).startsWith("application/json");
        assertThat(second.exchange().responseText())
                .isEqualTo("{\"error\":\"You cannot publish over the previously published versions.\"}");
    }

    /** The operator's opt-out, as the edge resolves it for the publishing tenant, lets a release be replaced - the one
     *  dial every release link honours rather than each format reading it. */
    @ParameterizedTest
    @MethodSource("formats")
    void with_redeploy_allowed_a_second_upload_replaces_the_release(Format format) throws IOException {
        ArtifactStore store = store(format);
        format.upload(store, "");

        Upload second = Publication.redeploying(true, () -> format.upload(store, "rebuilt"));

        assertThat(second.exchange().status()).as("%s accepts the replacement", format).isEqualTo(format.accepted());
        assertThat(format.serve(store)).as("%s serves the replacement", format).isEqualTo(second.artifact());
    }

    @ParameterizedTest
    @MethodSource("formats")
    void an_identical_re_upload_converges(Format format) throws IOException {
        ArtifactStore store = store(format);
        Upload first = format.upload(store, "");

        Upload again = format.upload(store, "");

        assertThat(again.exchange().status()).as("an upload whose answer was lost can be sent again")
                .isEqualTo(format.accepted());
        assertThat(format.serve(store)).isEqualTo(first.artifact());
    }

    /**
     * Rivals are held at the first blob each stores until every one has stored its bytes, so the race is forced
     * rather than hoped for: every rival has passed whatever its format checks before storing, and none has linked.
     * A format that decides by reading the pointer ahead of its link answers all of them as first uploads here, where
     * an unforced race would mostly let one finish before the next began and pass.
     */
    @ParameterizedTest
    @MethodSource("formats")
    void racing_first_uploads_land_exactly_one(Format format) throws Exception {
        ArtifactStore store = store(format);
        int rivals = 12;
        List<Upload> uploads = new ArrayList<>();
        for (int rival = 0; rival < rivals; rival++) {
            uploads.add(format.uploader().upload("rival " + rival));
        }
        Set<Thread> racing = ConcurrentHashMap.newKeySet();
        Set<Thread> stored = ConcurrentHashMap.newKeySet();
        CyclicBarrier allStored = new CyclicBarrier(rivals);
        AtomicInteger held = new AtomicInteger();
        ArtifactStore gated = WatchingStore.holding(store, () -> {
            Thread rival = Thread.currentThread();
            if (racing.contains(rival) && stored.add(rival)) {
                try {
                    allStored.await(1, TimeUnit.MINUTES);
                    held.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    rival.interrupt();
                } catch (BrokenBarrierException | TimeoutException _) {
                    // counted below: a rival that never reached the barrier leaves the race unforced
                }
            }
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = new ArrayList<>();
            for (Upload upload : uploads) {
                sent.add(executor.submit(() -> {
                    racing.add(Thread.currentThread());
                    format.fixture().serving().handle(upload.exchange(), gated);
                    return null;
                }));
            }
            for (Future<?> future : sent) {
                future.get();
            }
        }

        assertThat(held).as("every rival of %s stored its bytes before any of them linked", format).hasValue(rivals);
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
        ArtifactStore store = store(format);
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

    private ArtifactStore store(Format format) throws IOException {
        Path directory = Files.createDirectories(root.resolve(format.name()));
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? directory.toString() : null);
        format.arrangement().arrange(store);
        return store;
    }

    /** Every file under a store's root, by its path relative to the root, once every derivation a publish queued has
     *  landed: a Debian index's signed release is written behind the publish, and a snapshot taken while it is being
     *  written meets its temporary file or reports the rewrite as the refused upload's. */
    private Map<String, byte[]> contents(String name) throws IOException {
        StoredListing.settle();
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
