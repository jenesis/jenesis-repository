package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.contract.testkit.ContractCensus;
import build.jenesis.repository.contract.testkit.ContractCensus.Exemption;
import build.jenesis.repository.contract.testkit.ContractCensus.Provider;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.testkit.TraversalVectors;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The core half of the cross-format {@link RepositoryImporter} contract (the downstream half lives in the gateway
 * test module): the coordinate-derivation and leading-slash invariants every importer must uphold, plus a ratchet that
 * fails if a newly-registered importer has no contract coverage. Importers are discovered exactly as the server does -
 * {@code ServiceLoader.load(RepositoryFormat.class)} filtered to the import capability - so the test works through the
 * SPI alone and needs no per-importer wiring.
 *
 * <p>Core ships three importers of two shapes: {@link #COORDINATE Maven} is coordinate+versioned (its parsed
 * version is a traversal-free store segment, and a leading-slash H2 path resolves identically); {@code raw} returns a
 * descriptor for <em>every</em> asset (it is the un-inspected catch-all, so it never declines) and {@code oci} returns
 * {@code Optional.empty()} for {@code importTarget} by design (OCI owns its own manifest screening choke point). The two
 * special shapes are asserted directly; the shared {@link ContractCensus} ratchet then confirms every statically
 * declared format provider is runtime-visible and has importer coverage or a reason-bearing exemption.
 *
 * <p>The traversal row below is the one property every importer runs, over the format kit's shared
 * {@link TraversalVectors} list rather than over per-importer shapes. The <em>read</em> half of a migration - what an
 * {@code ImportSource} enumerates, resumes, streams and classifies - is the importer kit's job instead
 * ({@code source/importer/testkit} + {@code test/importer/contract}), and its census covers the five
 * {@code ImportSourceProvider}s the same way this one covers the three importing formats.
 */
class ImporterContractTest {

    private static final String JENREG_FORMAT =
            "build.jenesis.repository.format.jenesis.JenesisFormat";

    record Case(String format, String deepPath, String nonDistributionPath) {
    }

    private static final List<Case> COORDINATE = List.of(
            new Case("maven", "org/example/lib/1.0/lib-1.0.jar", "org/example/lib/maven-metadata.xml"));

    private static List<RepositoryImporter> discovered() {
        return discoveredFormats().stream()
                .filter(RepositoryImporter.class::isInstance)
                .map(RepositoryImporter.class::cast)
                .toList();
    }

    private static List<RepositoryFormat> discoveredFormats() {
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    private static RepositoryImporter importerFor(String format) {
        return discovered().stream()
                .filter(importer -> importer.imports(format))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no registered importer claims the format '" + format + "'"));
    }

    @Test
    void a_coordinate_versioned_importer_derives_a_traversal_safe_coordinate_and_normalises_a_leading_slash() {
        for (Case testCase : COORDINATE) {
            RepositoryImporter importer = importerFor(testCase.format());
            String label = testCase.format() + " (" + testCase.deepPath() + ")";

            ArtifactDescriptor deep = importer.importTarget(testCase.deepPath())
                    .orElseThrow(() -> new AssertionError(label + ": a deep incumbent path must resolve"));
            assertThat(deep.version()).as(label + ": the parsed version never carries a path slash")
                    .isNotNull().doesNotContain("/");

            ArtifactDescriptor absolute = importer.importTarget("/" + testCase.deepPath())
                    .orElseThrow(() -> new AssertionError(label + ": the leading-slash absolute path must resolve"));
            assertThat(absolute).as(label + ": a Nexus 3.71 leading-slash path resolves identically").isEqualTo(deep);

            assertThat(importer.importTarget(testCase.nonDistributionPath()))
                    .as(label + ": a non-distribution asset is declined").isEmpty();
        }
    }

    @Test
    void the_raw_importer_screens_every_asset_and_normalises_a_leading_slash() {
        RepositoryImporter raw = importerFor("raw");
        ArtifactDescriptor descriptor = raw.importTarget("dir/file.txt")
                .orElseThrow(() -> new AssertionError("raw screens every asset, so importTarget is never empty"));
        assertThat(raw.importTarget("/dir/file.txt")).as("a leading-slash raw path resolves identically")
                .hasValue(descriptor);
    }

    @Test
    void the_oci_importer_declines_import_target_by_design() {
        RepositoryImporter oci = importerFor("oci");
        assertThat(oci.importTarget("v2/app/manifests/1.0"))
                .as("OCI owns its own manifest screening, so importTarget is empty").isEmpty();
        assertThat(oci.importTarget("/v2/app/blobs/sha256:abc")).isEmpty();
    }

    /**
     * The traversal row, run over <em>every</em> discovered importer with the shared probe vectors rather than per
     * format, because a screen that each importer's own suite probes with the shapes its author thought of is exactly
     * how the coordinate seam rotted format by format before.
     *
     * <p>What it holds open: {@code RawImporter.importTarget("/../x")} used to answer a descriptor whose path was
     * {@code /raw/../x}, and {@code MavenImporter} inherited {@code MavenFormat.describe}'s fall-through to
     * {@code ArtifactDescriptor.at(ECOSYSTEM, path)}, so the same shape came back as {@code /maven/../x}. That
     * descriptor is what the import edge screens against, what an edition records for a held or rejected asset, and
     * what a quarantine diversion composes its key from - so a traversal-shaped path there points all three at a
     * coordinate the asset will never occupy. Answering {@link Optional#empty()} instead would be worse, not better:
     * the edge reads empty as "this format screens elsewhere, stream the source bytes straight through".
     */
    @Test
    void no_importer_echoes_a_traversal_shaped_source_path_into_a_descriptor() {
        List<RepositoryImporter> importers = discovered();
        assertThat(importers).as("the traversal row must probe at least one importer").isNotEmpty();

        for (RepositoryImporter importer : importers) {
            String label = importer.getClass().getSimpleName();
            for (TraversalVectors.Vector vector : TraversalVectors.all()) {
                for (String path : List.of(vector.relative(), "/" + vector.relative())) {
                    String probe = label + " (" + vector.id() + ": " + path + ")";
                    switch (vector.kind()) {
                        case DECODED -> {
                            // assertThatExceptionOfType, not assertThatThrownBy: the description of a
                            // `.as(...)` chained after assertThatThrownBy is lost in exactly the case that matters -
                            // nothing was thrown - so the failure would name no vector and no importer.
                            assertThatExceptionOfType(IllegalArgumentException.class)
                                    .as("%s: a source path carrying a '.' or '..' segment addresses nothing this "
                                            + "format can lay out, so it is refused by name - never echoed into the "
                                            + "descriptor the edge screens and records, and never demoted to the "
                                            + "empty answer that means 'lay it out unscreened'", probe)
                                    .isThrownBy(() -> importer.importTarget(path));
                            // The write half refuses the same shapes before it touches the store, so an importer
                            // reached by another edge cannot compose the key either. The screen runs first, so the
                            // arguments below are never used.
                            assertThatExceptionOfType(IllegalArgumentException.class)
                                    .as("%s: the layout half refuses the same shape", probe)
                                    .isThrownBy(() -> importer.importArtifact(path, InputStream.nullInputStream(),
                                            (ArtifactStore) null));
                        }
                        case ENCODED, SHAPE_CAP -> {
                            // A percent-encoded traversal is a literal name here (no importer decodes its own paths),
                            // and an over-shaped one is the store key cap's refusal, not the importer's. Either may
                            // resolve - what may never happen is that the composed target path is itself traversal
                            // shaped, which would mean something below decoded or normalised it.
                            assertThatCode(() -> importer.importTarget(path).ifPresent(descriptor ->
                                    assertThat(ArtifactStore.traversalFree(descriptor.path()))
                                            .as("%s: the composed target path '%s' must not carry a traversal segment",
                                                    probe, descriptor.path())
                                            .isTrue()))
                                    .as("%s: a literal or over-shaped name is not the importer's refusal to make",
                                            probe)
                                    .doesNotThrowAnyException();
                        }
                    }
                }
            }
        }
    }

    @Test
    void an_importable_source_path_still_resolves_after_the_traversal_screen() {
        // Non-vacuity for the row above: the screen refuses the laced shapes because they are laced, not because the
        // importers stopped answering. A leading slash stays the Nexus 3.71 shape the walk normalises, not an escape.
        RepositoryImporter raw = importerFor("raw");
        assertThat(raw.importTarget("dir/sub/file.txt")).isPresent();
        assertThat(raw.importTarget("/dir/sub/file.txt")).isPresent();
        assertThat(importerFor("maven").importTarget("org/example/lib/1.0/lib-1.0.jar")).isPresent();
        assertThat(RepositoryImporter.importable("%2e%2e/name.txt"))
                .as("a percent-encoded traversal is an ordinary literal name at this seam").isTrue();
        assertThat(RepositoryImporter.importable("dir/../file.txt")).isFalse();
        assertThat(RepositoryImporter.importable("dir\\file.txt"))
                .as("a backslash segment separator is refused here even where the store's key screen still folds none")
                .isFalse();
        assertThat(RepositoryImporter.importable("")).isFalse();
        assertThat(RepositoryImporter.importable("/")).isFalse();
        assertThat(RepositoryImporter.importable("a//b")).isFalse();
    }

    @Test
    void every_declared_importer_provider_has_a_contract_row() throws IOException {
        List<String> fixtures = Stream.concat(
                        COORDINATE.stream().map(testCase -> importerFor(testCase.format())),
                        Stream.of(importerFor("raw"), importerFor("oci")))
                .map(importer -> importer.getClass().getName())
                .toList();
        List<Provider> runtime = discoveredFormats().stream()
                .map(format -> Provider.runtime(format.name(), format))
                .toList();

        ContractCensus.of(RepositoryImporter.class,
                ContractCensus.declaredProviders(repositoryRoot().resolve("source"), RepositoryFormat.class),
                runtime,
                fixtures,
                List.of(new Exemption(JENREG_FORMAT,
                        "the Jenesis module layout does not implement the migration-import capability")));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            // Standalone the tree is source/; inside an enclosing project it is core/source/.
            if (Files.isDirectory(candidate.resolve("core").resolve("source").resolve("format"))) {
                return candidate.resolve("core");
            }
            if (Files.isDirectory(candidate.resolve("source").resolve("format"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("cannot locate the repository source tree");
    }
}
