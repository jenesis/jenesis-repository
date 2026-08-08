package build.jenesis.repository.test;

import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core structural guard (EPIC 26): screening is the ingress edges' monopoly, so no repository format or importer
 * may run the publish-screen chain itself. Screening was lifted out of the formats onto the ingress edges - the deploy
 * edge ({@code ScreenedDispatch}), the batch explode, the import walk and OCI's manifest choke point - and the formats
 * were demoted to pure layout writers. This is a source-scanning guard in the shape of the downstream structural guards
 * (a {@code *PrincipleTest} that reads the sources rather than booting anything): it walks every concrete
 * format/importer source under {@code source/format/} and asserts none reaches for a screening seam - it neither
 * invokes {@link build.jenesis.repository.store.Publication#screen} nor the removed combined
 * {@code Publication.publish}, and it does not reference the screen SPI type {@code PublishInterceptor}. A NEW
 * screen-in-a-format therefore fails the build.
 *
 * <p>Since T-104a the <b>invocation</b> half has no exception at all. {@code OciManifests} - OCI's documented choke
 * point (T26.7), which exists because a multi-request {@code /v2/} push carries no single body for the
 * {@code ScreenedDispatch} edge - drives the shared hosted-publish operation
 * {@link build.jenesis.repository.store.Publication#commit}, which screens once on its behalf and fires the
 * after-commit observers once OCI's declared visibility has committed. It therefore invokes no screen of its own; it
 * only reads the operation's {@code Disposition} back to map the verdict onto OCI's protocol codes, which is the sole
 * remaining, explicitly justified carve-out for the <b>type reference</b> half.
 *
 * <p>The {@code source/format/spi} contract module is out of scope: it is the format <em>SPI</em>, not a format or
 * importer, and its interface javadoc necessarily names the screen SPI when documenting where screening happens - it
 * lays nothing out and screens nothing.
 */
class FormatScreeningMonopolyPrincipleTest {

    /** The one file that may still <em>name</em> the screen SPI type: OCI's manifest choke point (T26.7), which has no
     *  single-body ingress edge to ride because a {@code /v2/} push is multi-request, so it drives the shared
     *  hosted-publish operation itself and reads {@code PublishInterceptor.Disposition} back off the commit to map the
     *  verdict onto OCI's protocol codes. It <em>invokes</em> no screen (T-104a routed it through
     *  {@code Publication.commit}), so it is no longer exempt from the invocation check below - only from the type
     *  reference. Named here so the carve-out is explicit and any other format naming the screen SPI is caught. */
    private static final String ALLOWLISTED_DISPOSITION_READER = "OciManifests.java";

    /** The format SPI contract module - interfaces the concrete formats implement, not a format itself; its javadoc
     *  names the screen SPI when documenting the edge relationship, so it is not scanned. */
    private static final String CONTRACT_MODULE = "spi";

    /** The screen SPI type. Naming it is allowed only in {@link #ALLOWLISTED_DISPOSITION_READER}, which maps the shared
     *  operation's {@code Disposition} onto OCI's protocol codes. */
    private static final String SCREEN_SPI = "PublishInterceptor";

    /** The screening <em>invocations</em> no format/importer may make - <b>with no exception at all</b> since T-104a
     *  routed the OCI choke point through {@code Publication.commit}: {@code .screen(} is the screen invocation and
     *  {@code Publication.publish} / {@code Publication#publish} is the removed combined screen-and-link. Layout-only
     *  use of {@code Publication} ({@code storeBlob}, {@code link}, {@code located}, {@code unpublish}) and the
     *  {@code ModuleView}/{@code ModuleViewPublisher} cross-publish ({@code view.publish(...)}) are format concerns and
     *  stay allowed, as is the shared {@code Publication.commit} operation a choke point drives. */
    private static final List<String> FORBIDDEN = List.of(
            ".screen(", "Publication.publish", "Publication#publish");

    @Test
    void no_format_or_importer_screens_outside_the_ingress_edges() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : formatSources()) {
            String name = source.getFileName().toString();
            String body = Files.readString(source);
            for (String forbidden : FORBIDDEN) {
                if (body.contains(forbidden)) {
                    offenders.add(name + " invokes '" + forbidden + "'");
                }
            }
        }

        assertThat(offenders)
                .as("screening is the ingress edges' monopoly - a format/importer must lay out only, never screen. "
                        + "A format with no single-body ingress edge to ride (OCI) drives the shared hosted-publish "
                        + "operation Publication.commit, which screens once on its behalf; it never invokes the screen "
                        + "itself, so this check has no exceptions")
                .isEmpty();
    }

    @Test
    void only_the_oci_choke_point_names_the_screen_spi() throws IOException {
        List<String> offenders = new ArrayList<>();
        boolean readerFound = false;
        for (Path source : formatSources()) {
            String name = source.getFileName().toString();
            boolean names = Files.readString(source).contains(SCREEN_SPI);
            if (name.equals(ALLOWLISTED_DISPOSITION_READER)) {
                readerFound = names;
            } else if (names) {
                offenders.add(name + " references '" + SCREEN_SPI + "'");
            }
        }

        assertThat(offenders)
                .as("only " + ALLOWLISTED_DISPOSITION_READER + " may name the screen SPI, to map the shared "
                        + "operation's Disposition onto OCI's protocol codes; a format that needs a verdict takes it "
                        + "from the Publication.commit outcome it already has")
                .isEmpty();
        assertThat(readerFound)
                .as("the " + ALLOWLISTED_DISPOSITION_READER + " carve-out no longer matches anything - it stopped "
                        + "naming " + SCREEN_SPI + ", so drop the allowlist entry rather than let it mask a future "
                        + "screen SPI reference")
                .isTrue();
    }

    /** Every concrete format/importer source: the {@code source/format} tree minus {@code module-info.java} and the
     *  format SPI contract module (interfaces, not formats). */
    private static List<Path> formatSources() throws IOException {
        Path formats = repositoryRoot().resolve("source").resolve("format");
        assertThat(Files.isDirectory(formats))
                .as("the format sources must be present for the guard to scan them: " + formats).isTrue();
        try (Stream<Path> sources = Files.walk(formats)) {
            return sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                    .filter(path -> !formats.relativize(path).getName(0).toString().equals(CONTRACT_MODULE))
                    .sorted()
                    .toList();
        }
    }

    /** The repository root: walk up from the working directory (the reactor runs each test JVM from the repo root)
     *  until the {@code source/format} tree is found, so the guard locates the sources whether the suite runs from the
     *  root or a nested module directory. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("source").resolve("format"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate a repository root containing source/format from "
                + Path.of("").toAbsolutePath());
    }
}
