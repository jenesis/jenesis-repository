package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependents.DependentsIndex;
import build.jenesis.repository.dependents.DependentsQueryReader;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query path is a standalone {@link DependentsQueryReader} that answers the blast-radius
 * questions from the durable shard objects a sweep pre-built, carrying none of the build/inversion/segment machinery
 * {@link DependentsIndex} drives. Two facts pin the split down. First, the reader answers a store whose shards were
 * written straight through the store SPI, with no builder ever constructed - proving the read path is self-contained
 * (the reader pays for nothing the sweep pre-did). Second, the builder writes exactly the on-store
 * layout the reader reads - a fresh reader that never touched the builder returns the builder's committed answers -
 * so the two halves share the format ({@code DependentsStore}) and cannot drift. No network and no framework: the
 * split is exercised end to end through the store SPI over a real filesystem store.
 */
class DependentsQueryReaderTest {

    private static final String APP1 = "pkg:maven/com.example/app1@1.0.0";
    private static final String LOG4J_CORE = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final String LOG4J_API = "pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1";

    private static final String[] APP1_COORD = {"com.example", "app1", "1.0.0"};
    private static final String[] CORE = {"org.apache.logging.log4j", "log4j-core", "2.14.1"};
    private static final String[] API = {"org.apache.logging.log4j", "log4j-api", "2.14.1"};

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");                 // a per-tenant, per-repository scoped store
    }

    @Test
    void the_reader_answers_a_prebuilt_store_without_ever_constructing_the_builder() throws Exception {
        // Write the durable index straight through the store SPI - a shard line and the built marker in the exact
        // on-store format - so NO build machinery (no DependentsIndex, no walk, no inversion) is involved. The reader
        // alone must answer the blast-radius questions off these bytes.
        String depA = "pkg:maven/com.example/app-a@1.0.0";
        String depB = "pkg:maven/com.example/app-b@2.0.0";
        store.writeVersioned(shardKey(LOG4J_CORE),
                (encode(LOG4J_CORE) + ' ' + encode(depA) + ' ' + encode(depB) + '\n').getBytes(StandardCharsets.UTF_8),
                null);
        Instant stamped = Instant.parse("2026-07-25T12:00:00Z");
        store.writeVersioned("dependents/built", builtMarker(stamped), null);

        DependentsQuery reader = new DependentsQueryReader(store);

        assertThat(reader.dependents(LOG4J_CORE)).as("the read path parses the pre-built shard").containsExactly(depA, depB);
        assertThat(reader.coordinates()).as("enumeration lists the shard's coordinate keys").containsExactly(LOG4J_CORE);
        assertThat(reader.dependents("pkg:maven/com.example/absent@9.9.9")).as("nothing recorded").isEmpty();
        assertThat(reader.built()).as("the built marker is honoured").isTrue();
        assertThat(reader.builtAt()).as("the marker's instant is read back").contains(stamped);
    }

    @Test
    void the_bounded_reachable_probe_answers_only_the_queried_coordinates_off_the_neutral_key() throws Exception {
        // Two purl-keyed shards, each with a dependent, exactly as a sweep would commit them.
        String depA = "pkg:maven/com.example/app-a@1.0.0";
        store.writeVersioned(shardKey(LOG4J_CORE),
                (encode(LOG4J_CORE) + ' ' + encode(depA) + '\n').getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned(shardKey(LOG4J_API),
                (encode(LOG4J_API) + ' ' + encode(depA) + '\n').getBytes(StandardCharsets.UTF_8), null);
        store.writeVersioned("dependents/built", builtMarker(Instant.parse("2026-07-25T12:00:00Z")), null);

        DependentsQuery reader = new DependentsQueryReader(store);

        // The report keys a line on the ecosystem-neutral group:name:version - the purl is neutralised to it. Ask the
        // bounded probe with the vulnerable set (one reachable, one not on any build graph): it returns exactly the
        // reachable member, in the neutral form the caller compares against, and never the un-queried log4j-api.
        String coreNeutral = "org.apache.logging.log4j:log4j-core:2.14.1";
        String apiNeutral = "org.apache.logging.log4j:log4j-api:2.14.1";
        String notHeld = "com.example:absent:9.9.9";
        assertThat(reader.reachable(List.of(coreNeutral, notHeld)))
                .as("only the queried coordinate that sits on a build graph, never the whole index")
                .containsExactly(coreNeutral);
        assertThat(reader.reachable(List.of(notHeld)))
                .as("a coordinate nothing depends on is not reachable").isEmpty();
        assertThat(reader.reachable(List.of())).as("an empty query buffers nothing").isEmpty();
        // The probe returns a subset of exactly what it was handed, so a caller's `reachable.contains(line)` holds for
        // the found coordinate and never for the log4j-api the index also carries but the query never named.
        assertThat(reader.reachable(List.of(coreNeutral, apiNeutral)))
                .as("both queried coordinates are on a graph").containsExactlyInAnyOrder(coreNeutral, apiNeutral);
    }

    @Test
    void the_coordinate_enumeration_pages_the_whole_set_bounded_and_resumes_by_cursor() throws Exception {
        // A root depending on several distinct libraries, so the index holds one coordinate per library - enough to page
        // across more than one bounded page (and, by their SHA-256 shard hashes, across more than one shard object).
        List<String[]> deps = List.of(
                new String[] {"org.a", "lib-a", "1.0"}, new String[] {"org.b", "lib-b", "1.0"},
                new String[] {"org.c", "lib-c", "1.0"}, new String[] {"org.d", "lib-d", "1.0"},
                new String[] {"org.e", "lib-e", "1.0"}, new String[] {"org.f", "lib-f", "1.0"},
                new String[] {"org.g", "lib-g", "1.0"});
        publish(APP1_COORD, deps);
        new DependentsIndex(store).rebuild();
        DependentsQuery reader = new DependentsQueryReader(store);

        List<String> whole = reader.coordinates();
        assertThat(whole).as("every depended-upon library is a coordinate the index holds").hasSize(deps.size());

        // Page the same set in bounded pages of 2: the union of the pages is exactly the whole set (none dropped or
        // repeated) and the walk terminates on a null cursor - the request-path form that never buffers the whole set.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            DependentsQuery.CoordinatePage page = reader.coordinates(cursor, 2);
            assertThat(page.coordinates().size()).as("no page exceeds the limit").isLessThanOrEqualTo(2);
            paged.addAll(page.coordinates());
            cursor = page.nextCursor();
            assertThat(++pages).as("the paging terminates, never loops").isLessThan(20);
        } while (cursor != null);

        assertThat(paged).as("the union of the pages is exactly the whole set, none dropped or repeated")
                .containsExactlyInAnyOrderElementsOf(whole).doesNotHaveDuplicates();
        assertThat(reader.coordinates(null, 0).coordinates()).as("a non-positive limit yields an empty page").isEmpty();
        assertThat(reader.coordinates("zz ~", 2).coordinates())
                .as("a cursor past every shard yields an empty final page").isEmpty();
    }

    @Test
    void the_reader_reports_not_built_over_an_untouched_store() throws IOException {
        DependentsQuery reader = new DependentsQueryReader(store);

        assertThat(reader.built()).as("no sweep has run and nothing was written - not built").isFalse();
        assertThat(reader.coordinates()).isEmpty();
        assertThat(reader.builtAt()).as("no build instant on a never-built index").isEmpty();
    }

    @Test
    void the_builder_writes_the_layout_a_fresh_reader_reads() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));

        int shards = new DependentsIndex(store).rebuild();       // the build path commits the durable layout
        assertThat(shards).as("the transitive tree filled shards").isPositive();

        // A reader that never touched the builder reads the builder's committed layout - proving both halves share the
        // on-store format and cannot drift.
        DependentsQuery reader = new DependentsQueryReader(store);
        assertThat(reader.dependents(LOG4J_CORE)).containsExactly(APP1);
        assertThat(reader.dependents(LOG4J_API)).as("the whole transitive tree, not just direct edges").containsExactly(APP1);
        assertThat(reader.coordinates()).containsExactly(LOG4J_API, LOG4J_CORE);
        assertThat(reader.built()).isTrue();
        assertThat(reader.builtAt()).as("the builder stamps a real completion instant").isPresent();
    }

    /** Store a jar carrying a CycloneDX SBOM for {@code root} depending on {@code dependencies}, as the repo would. */
    private void publish(String[] root, List<String[]> dependencies) throws IOException {
        store.writeBlob(new ByteArrayInputStream(jar(bom(root, dependencies))));
    }

    /** A minimal CycloneDX 1.6 BOM: {@code root} is the metadata component, each of {@code dependencies} a node in
     *  the resolved tree (the transitive closure the index groups on). */
    private static String bom(String[] root, List<String[]> dependencies) {
        StringBuilder components = new StringBuilder();
        for (String[] dependency : dependencies) {
            if (!components.isEmpty()) {
                components.append(",\n");
            }
            components.append(component(dependency));
        }
        return """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "version": 1,
                  "metadata": { "component": %s },
                  "components": [ %s ],
                  "dependencies": []
                }
                """.formatted(component(root), components);
    }

    private static String component(String[] coordinate) {
        String group = coordinate[0], name = coordinate[1], version = coordinate[2];
        return """
                {
                  "type": "library",
                  "bom-ref": "%s/%s/%s",
                  "group": "%s",
                  "name": "%s",
                  "version": "%s",
                  "purl": "pkg:maven/%s/%s@%s"
                }
                """.formatted(group, name, version, group, name, version, group, name, version);
    }

    private static byte[] jar(String bom) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", "META-INF/sbom/app.cdx.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("com/example/App.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            jar.write(bom.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** The URL-encoding the index applies to every shard token, mirrored so the test writes the on-store format. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The built marker's body in the on-store format: the shard-layout generation the shards were placed by, then
     *  the completing sweep's instant. A body naming another generation reads as not built. */
    private static byte[] builtMarker(Instant instant) {
        return ("2 " + instant + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The shard object key a coordinate lands in - the first byte of its SHA-256 - so the test writes straight to the
     *  object the reader reads, mirroring the index's own sharding (the on-store contract the two halves share). */
    private static String shardKey(String coordinate) throws Exception {
        // The shard byte is taken over the NEUTRAL spelling, so a stored purl and the group:name:version a
        // report line keys on land in the same object - mirrored here through the SPI's own neutralise, never a
        // second copy of the mapping.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(DependentsQuery.neutralise(coordinate).getBytes(StandardCharsets.UTF_8));
        return "dependents/" + HexFormat.of().formatHex(digest, 0, 1);
    }
}
