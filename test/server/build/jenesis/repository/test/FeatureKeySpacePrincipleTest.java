package build.jenesis.repository.test;

import build.jenesis.repository.store.Features;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>One key, one meaning: a provider name may not be an SPI name (D-005).</b>
 *
 * <p>{@link Features} spends a single namespace on two different questions. {@code jenesis.repository.<spi>=<name>}
 * <em>selects</em> a singleton implementation, and {@code jenesis.repository.<name>=false} <em>switches off</em> a
 * discovered one. Nothing binds those two vocabularies to each other, so a provider that takes an SPI's name for its
 * own quietly gives one key two readings - and the two readings do not fail together. The reference artifact walk was
 * called {@code store}: every deployment already sets {@code jenesis.repository.store} (the shipped
 * {@code application.properties} binds it to {@code ${JENESIS_STORE:filesystem}}), so the walk's toggle was being
 * answered by the storage backend's selection on every boot - benignly, because a backend name is never the literal
 * {@code false} - while the walk's own documented off-switch could not be used at all: writing
 * {@code jenesis.repository.store=false} selects an artifact-store backend called {@code false} and refuses to boot
 * (&sect;9). A toggle that is answered by someone else's value and cannot be set to its own is not a toggle.
 *
 * <p>This is the {@code KeyLoginMechanism} shape (T-211) in the configuration namespace rather than the settings
 * catalogue - an identifier whose spellings were never bound to each other - which is why it is a scan and not a
 * convention: the two sides are written in different modules by different authors, and neither can see the other.
 *
 * <h2>What it reads</h2>
 * Both sides come out of {@code source/**}, so neither is a hand-maintained list that could drift from the code:
 * <ul>
 *   <li><b>SPI names</b> - the family key every resolution passes as its first argument, to
 *       {@link Features#selection(String)} or to one of the {@code Providers} primitives. That argument is what the
 *       diagnostics spell as {@code jenesis.repository.<spi>}.</li>
 *   <li><b>Provider names</b> - the literal a {@code String name()} implementation returns, which is what
 *       {@code Features.enabled}/{@code active} spell as {@code jenesis.repository.<name>}.</li>
 * </ul>
 * A name resolved out of a constant would need the constant's declaring class to be resolved first (the trap D-115
 * records, where a bare {@code NAME} was matched against a tree-wide index and reported the wrong provider); every
 * {@code name()} in this tree returns its literal directly, and {@link #the_scan_is_alive()} fails if the scan stops
 * seeing the ones it is built on rather than passing vacuously over a tree it no longer parses.
 */
class FeatureKeySpacePrincipleTest {

    /** The family key each resolution is spelled with - the first argument of a selection read or a
     *  {@code Providers} primitive, which is exactly the {@code <spi>} in {@code jenesis.repository.<spi>}. */
    private static final Pattern SPI_NAME = Pattern.compile(
            "(?:Features\\.selection|Providers\\.(?:all|optionalUnique|namedUnique|exclusiveWithDefault"
                    + "|installedNames))\\(\\s*\"([A-Za-z0-9._-]+)\"");

    /** A provider's declared name - the {@code <name>} in {@code jenesis.repository.<name>=false}. */
    private static final Pattern PROVIDER_NAME = Pattern.compile(
            "String\\s+name\\(\\)\\s*\\{\\s*return\\s+\"([^\"]+)\"\\s*;");

    private record Declaration(String name, String file) {
    }

    private static List<Declaration> scan(Pattern pattern) throws IOException {
        List<Declaration> found = new ArrayList<>();
        Path source = root().resolve("source");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(new Declaration(matcher.group(1), source.relativize(file).toString()));
                }
            }
        }
        return found;
    }

    @Test
    void no_provider_name_is_also_an_spi_name() throws IOException {
        Set<String> spis = scan(SPI_NAME).stream().map(Declaration::name).collect(Collectors.toCollection(TreeSet::new));
        List<String> collisions = scan(PROVIDER_NAME).stream()
                .filter(provider -> spis.contains(provider.name()))
                .map(provider -> "  - the provider named '" + provider.name() + "' (" + provider.file()
                        + ") shares jenesis.repository." + provider.name() + " with the SPI of the same name, so its "
                        + "toggle and that SPI's selection are one key")
                .distinct()
                .toList();

        assertThat(collisions)
                .as("a provider name is a configuration key; taking an SPI's name gives that key two meanings, and "
                        + "the deployment obeys whichever reader looks first.%n%s",
                        String.join(System.lineSeparator(), collisions))
                .isEmpty();
    }

    @Test
    void the_scan_is_alive() throws IOException {
        // Both sides are matched by shape, so the failure mode that matters is a scan that quietly stops matching -
        // it would report no collisions over a tree it never parsed. These anchors are the ones this rule is about:
        // the store SPI whose key was taken, and the walk provider that took it and has since been renamed off it.
        assertThat(scan(SPI_NAME)).extracting(Declaration::name)
                .as("the SPI-name side of the scan is reading real resolutions")
                .contains("store", "walk", "gc", "token-exchange");
        assertThat(scan(PROVIDER_NAME)).extracting(Declaration::name)
                .as("the provider-name side of the scan is reading real providers")
                .contains("filesystem", "mark-sweep", "paged-descent");
    }

    private static Path root() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            // Standalone the tree is source/; inside an enclosing project it is core/source/.
            if (Files.isDirectory(directory.resolve("core").resolve("source").resolve("store").resolve("spi"))) {
                return directory.resolve("core");
            }
            if (Files.isDirectory(directory.resolve("source").resolve("store").resolve("spi"))) {
                return directory;
            }
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
