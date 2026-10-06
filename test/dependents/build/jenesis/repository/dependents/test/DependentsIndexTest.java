package build.jenesis.repository.dependents.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependents.DependentsIndex;
import build.jenesis.repository.dependents.DependentsIndexTask;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reverse-dependency index over a real filesystem store, no network and no framework: an artifact's embedded
 * CycloneDX SBOM is inverted into a sharded "who depends on X" index, the transitive tree (not just the direct
 * edges) is recorded, a removed artifact drops out and its emptied shard is compacted on the next rebuild, a
 * non-jar or SBOM-less blob is skipped rather than derailing the sweep, and the pass and its settings are
 * discovered and gated through ServiceLoader. The index never buffers an artifact - only the embedded BOM is read.
 */
class DependentsIndexTest {

    private static final String APP1 = "pkg:maven/com.example/app1@1.0.0";
    private static final String APP2 = "pkg:maven/com.example/app2@1.0.0";
    private static final String LOG4J_CORE = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";
    private static final String LOG4J_API = "pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1";

    /** The JDK's own XML element-depth limit (default 100 via {@code conf/jaxp.properties}); lifted for the one test
     *  that needs the DOM to build a deep tree so the parser's own recursion bound - not the ambient JDK default - is
     *  what refuses the crafted blob, exactly as an unbounded production JDK would let the walk run. */
    private static final String MAX_ELEMENT_DEPTH = "jdk.xml.maxElementDepth";

    /** A nesting far past both any real resolved dependency tree and the parser's recursion cap (~a thousand): deep
     *  enough that the unguarded recursive walk overflowed the stack. */
    private static final int PATHOLOGICAL_NESTING = 20_000;

    private static final String[] APP1_COORD = {"com.example", "app1", "1.0.0"};
    private static final String[] APP2_COORD = {"com.example", "app2", "1.0.0"};
    private static final String[] CORE = {"org.apache.logging.log4j", "log4j-core", "2.14.1"};
    private static final String[] API = {"org.apache.logging.log4j", "log4j-api", "2.14.1"};

    @TempDir
    Path root;

    private ArtifactStore store;
    private DependentsIndex index;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("app");                 // a per-tenant, per-repository scoped store
        index = new DependentsIndex(store);
    }

    @Test
    void who_depends_on_a_coordinate_is_recorded_across_every_artifact() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));
        publish(APP2_COORD, List.of(CORE, API));

        index.rebuild();

        assertThat(index.dependents(LOG4J_CORE)).containsExactly(APP1, APP2);
        assertThat(index.coordinates()).containsExactly(LOG4J_API, LOG4J_CORE);
    }

    @Test
    void the_reachability_probe_answers_the_purl_keys_under_the_neutral_release_coordinate() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));

        index.rebuild();

        // the index keys a component by its purl; the probe is asked in the group:name:version a Release carries, so
        // the vulnerability report can rank a reachable line above a merely-scored one. That join is the
        // SHARD function rather than a read-time scan, so the two spellings meet in one object.
        assertThat(index.reachable(List.of(
                "org.apache.logging.log4j:log4j-core:2.14.1",
                "org.apache.logging.log4j:log4j-api:2.14.1",
                "com.nobody:unheard-of:1.0.0")))
                .containsExactlyInAnyOrder(
                        "org.apache.logging.log4j:log4j-core:2.14.1",
                        "org.apache.logging.log4j:log4j-api:2.14.1");
    }

    @Test
    void neutralisation_maps_every_purl_shape_and_passes_a_neutral_coordinate_through() {
        assertThat(List.of(
                "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",  // maven: group namespace
                "pkg:npm/left-pad@1.3.0",                                // no namespace
                "pkg:maven/com.example/app@1.0.0?type=jar#classes",      // qualifiers and a subpath
                "org.legacy:artifact:9.9.9")                             // already neutral - passed through
                .stream().map(DependentsQuery::neutralise).toList())
                .containsExactly(
                        "org.apache.logging.log4j:log4j-core:2.14.1",
                        "left-pad:1.3.0",
                        "com.example:app:1.0.0",
                        "org.legacy:artifact:9.9.9");
    }

    @Test
    void a_hostile_or_malformed_purl_does_not_crash_the_reachable_set_and_a_plus_stays_literal() {
        // a single bad purl once threw IllegalArgumentException out of the reachable path and took the whole
        // vulnerability / blast-radius report down; now each coordinate degrades to its best-effort neutral form.
        // This mapping is also the shard function, so a coordinate that cannot be neutralised still
        // hashes deterministically (to its raw spelling) on both the write and the read side.
        assertThat(List.of(
                "pkg:maven/com.example/tool@9+181",      // '+' is a literal purl char, NOT a space
                "pkg:maven/com.example/ba%zzd@1.0",      // a malformed %-escape must not throw
                "pkg:maven/com.example/a%2Fb@2.0")       // a valid %-escape still decodes
                .stream().map(DependentsQuery::neutralise).toList())
                .containsExactly(
                        "com.example:tool:9+181",
                        "com.example:ba%zzd:1.0",
                        "com.example:a/b:2.0");
    }

    @Test
    void a_corrupt_shard_line_is_skipped_rather_than_derailing_the_read() throws IOException {
        publish(APP1_COORD, List.<String[]>of(CORE));            // a real, correctly-keyed shard
        index.rebuild();
        assertThat(index.coordinates()).contains(LOG4J_CORE);

        // hand-write a torn shard alongside (its name is not a hex byte, so it collides with no real shard); the
        // single line's token is not a valid %-escape, which once threw IllegalArgumentException out of the read path
        store.writeVersioned("dependents/zz", "br%zzoken entry\n".getBytes(StandardCharsets.UTF_8), null);

        assertThat(index.coordinates()).as("the bad line is skipped, the good shard still reads").contains(LOG4J_CORE);
        assertThat(index.dependents(LOG4J_CORE)).containsExactly(APP1);
    }

    @Test
    void a_query_finds_and_decodes_only_its_own_line_in_a_densely_populated_shard() throws Exception {
        // A "who depends on X" read fetches one shard and must find X's line among every coordinate that hashes to the
        // same shard byte without decoding the whole reverse-edge set - so a shard is hand-written with the target line
        // buried under many decoys (and one well-keyed line whose dependent token is torn), and the read still returns
        // exactly the target's dependents, isolating the torn line and never derailing on it.
        String target = "pkg:maven/com.example/target@1.0.0";
        String depA = "pkg:maven/com.example/app-a@1.0.0";
        String depB = "pkg:maven/com.example/app-b@2.0.0";

        StringBuilder shard = new StringBuilder();
        for (int i = 0; i < 400; i++) {                         // decoys sharing the object; the read skips them all
            shard.append(encode("pkg:maven/com.example/decoy-" + i + "@1.0.0"))
                    .append(' ').append(encode("pkg:maven/com.example/decoy-dep-" + i + "@1.0.0")).append('\n');
        }
        shard.append(encode("pkg:maven/com.example/torn@1.0.0")).append(" br%zztorn\n");    // valid key, torn dependent
        shard.append(encode(target)).append(' ').append(encode(depA)).append(' ').append(encode(depB)).append('\n');
        store.writeVersioned(shardKey(target), shard.toString().getBytes(StandardCharsets.UTF_8), null);

        assertThat(index.dependents(target)).as("the target line is found and decoded among many decoys")
                .containsExactly(depA, depB);
        assertThat(index.dependents("pkg:maven/com.example/torn@1.0.0"))
                .as("a torn dependent token yields no dependents rather than derailing the read").isEmpty();
        assertThat(index.dependents("pkg:maven/com.example/absent@9.9.9")).as("nothing recorded").isEmpty();
        assertThat(index.coordinates()).as("enumeration lists the shard's coordinate keys").contains(target);
    }

    @Test
    void the_whole_transitive_tree_is_indexed_not_just_the_direct_edges() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));                // log4j-api is a transitive dependency of the app

        index.rebuild();

        assertThat(index.dependents(LOG4J_CORE)).containsExactly(APP1);
        assertThat(index.dependents(LOG4J_API)).containsExactly(APP1);
    }

    @Test
    void a_coordinate_nothing_depends_on_has_no_dependents() throws IOException {
        publish(APP1_COORD, List.<String[]>of(CORE));

        index.rebuild();

        assertThat(index.dependents("pkg:maven/com.example/unused@9.9.9")).isEmpty();
    }

    @Test
    void a_removed_artifact_drops_out_and_its_emptied_shard_is_compacted() throws IOException {
        publish(APP1_COORD, List.<String[]>of(CORE));
        index.rebuild();
        assertThat(index.dependents(LOG4J_CORE)).containsExactly(APP1);
        assertThat(store.list("dependents")).as("a shard is written").isNotEmpty();

        for (String hash : store.list("blobs")) {
            store.delete("blobs/" + hash);                      // the artifact is evicted since the last pass
        }
        index.rebuild();

        assertThat(index.dependents(LOG4J_CORE)).isEmpty();
        assertThat(store.list("dependents")).as("emptied shards compacted away, only the built marker remains")
                .containsExactly("built");
    }

    @Test
    void built_is_false_before_the_first_sweep_and_true_after_even_a_zero_shard_one() throws IOException {
        // The load-bearing self-heal sentinel: enabled late over a store whose artifacts predate the index, the
        // query must not read a never-built index as a false-complete empty blast radius. An SBOM-less artifact
        // contributes no edges, so the first sweep fills zero shards - yet it is a genuine, authoritative empty
        // answer, which must be told apart from "the sweep never ran".
        store.writeBlob(new ByteArrayInputStream(sbomLessJar()));
        assertThat(index.built()).as("no sweep has run yet - the index is not built").isFalse();

        int shards = index.rebuild();
        assertThat(shards).as("nothing to invert, so zero shards written").isZero();
        assertThat(index.built()).as("a swept-but-empty index is authoritative-empty, not never-built").isTrue();
        assertThat(index.coordinates()).isEmpty();

        index.rebuild();
        assertThat(index.built()).as("the marker survives an idempotent re-sweep").isTrue();
    }

    @Test
    void a_shard_set_without_the_marker_reads_as_not_built() throws IOException {
        // built() is a single small-object existence probe over the sweep's own completion marker, and nothing else:
        // shards prove that edges were recorded, not that a sweep committed, and reading them to answer this
        // question is both a scan and the wrong evidence - it cannot speak for the swept-empty index this probe
        // exists to name. A marker-less shard set therefore reads as not built until the next sweep stamps one.
        publish(APP1_COORD, List.<String[]>of(CORE));
        index.rebuild();
        store.delete("dependents/built");
        assertThat(store.list("dependents")).as("a shard remains").anyMatch(name -> name.length() == 2);

        assertThat(index.built()).as("no completion marker - not built, whatever the shards hold").isFalse();

        index.rebuild();
        assertThat(index.built()).as("the next sweep stamps the marker and the index reads as built again").isTrue();
    }

    @Test
    void builtAt_is_empty_before_the_first_sweep_and_the_completion_instant_after() throws IOException {
        // Principle 10's staleness stamp: before any sweep the index has no build instant to show (rendered as "not
        // yet built", never as freshly built); a completed rebuild stamps its completion time into the built marker,
        // read back so the dependents panel and /api/dependents show how fresh the blast radius is.
        assertThat(index.builtAt()).as("never swept - no build instant").isEmpty();

        publish(APP1_COORD, List.<String[]>of(CORE));
        Instant before = Instant.now();
        index.rebuild();

        assertThat(index.builtAt()).as("a completed sweep stamps its completion instant").isPresent();
        assertThat(index.builtAt().orElseThrow())
                .as("the stamp is the sweep's completion time, read straight off the marker - no rebuild on read")
                .isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void a_marker_of_this_layout_whose_instant_does_not_parse_reads_as_built_with_an_unknown_instant()
            throws IOException {
        // A marker stamped by THIS shard layout still proves a sweep committed even when its instant is torn, so the
        // build time is honestly unknown: degrade to empty rather than fabricate a freshness, converging on the next
        // sweep's real stamp.
        publish(APP1_COORD, List.<String[]>of(CORE));
        index.rebuild();
        store.write("dependents/built", new ByteArrayInputStream("2 not-an-instant\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(index.built()).as("a marker of this layout is the built signal").isTrue();
        assertThat(index.builtAt()).as("an unparseable instant degrades to unknown, never a fabricated freshness")
                .isEmpty();
    }

    @Test
    void a_marker_stamped_by_another_shard_layout_reads_as_not_built_and_the_next_sweep_heals_it() throws IOException {
        // A shard written under another addressing is in an object this reader does not address. The honest answer
        // is "not built" - exactly what the marker-less case answers, for exactly the same reason - and it is also
        // the self-heal:
        // the incremental apply defers to the bootstrap on this signal, and the full rebuild re-derives every shard
        // from the blobs and compacts away the ones it did not fill.
        publish(APP1_COORD, List.<String[]>of(CORE));
        index.rebuild();
        // The body an edition before the layout generation existed wrote: a bare instant, no generation.
        store.write("dependents/built",
                new ByteArrayInputStream(Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        assertThat(index.built()).as("shards this reader cannot address are not a built index").isFalse();
        assertThat(index.builtAt()).as("and there is no freshness to show for them").isEmpty();

        index.rebuild();
        assertThat(index.built()).as("one pass converges - the rebuild re-places every shard and restamps").isTrue();
        assertThat(index.dependents(LOG4J_CORE)).containsExactly(APP1);
    }

    @Test
    void a_blob_whose_sbom_nests_pathologically_deep_is_skipped_and_negative_cached_not_a_crashed_sweep()
            throws IOException {
        // A crafted artifact whose embedded CycloneDX XML nests a few thousand <dependency> deep once overflowed the
        // parser's recursive walk - a StackOverflowError that escaped the sweep's per-blob IOException handling, killed
        // the pass and was never negative-cached, so every hourly rebuild re-crashed on the same blob forever (and
        // 500'd /api/sbom for it). The JDK's element-depth limit is lifted so the deep tree builds and the walk runs
        // (production may run it unbounded); the sweep must still index the good artifact, skip the crafted one, and
        // negative-cache it so the next pass is clean.
        publish(APP1_COORD, List.<String[]>of(CORE));           // a normal artifact the sweep must still index
        store.writeBlob(new ByteArrayInputStream(deeplyNestedXmlBomJar()));

        String previous = System.getProperty(MAX_ELEMENT_DEPTH);
        System.setProperty(MAX_ELEMENT_DEPTH, "0");             // let the DOM build the deep tree the walk descends
        try {
            index.rebuild();                                    // must not throw: one crafted blob cannot wedge the sweep
        } finally {
            if (previous == null) {
                System.clearProperty(MAX_ELEMENT_DEPTH);
            } else {
                System.setProperty(MAX_ELEMENT_DEPTH, previous);
            }
        }

        assertThat(index.dependents(LOG4J_CORE)).as("the good artifact is still indexed").containsExactly(APP1);
        assertThat(store.list("dependents/nosbom"))
                .as("the crafted blob carries no usable graph, so it is negative-cached and the next pass skips it")
                .hasSize(1);
    }

    @Test
    void a_non_jar_and_an_sbom_less_jar_are_skipped_rather_than_derailing_the_sweep() throws IOException {
        store.writeBlob(new ByteArrayInputStream("not an archive".getBytes(StandardCharsets.UTF_8)));
        store.writeBlob(new ByteArrayInputStream(sbomLessJar()));

        int shards = index.rebuild();

        assertThat(shards).isZero();
        assertThat(index.coordinates()).isEmpty();
    }

    @Test
    void a_blob_without_an_sbom_is_not_re_decompressed_on_the_next_pass() throws IOException {
        store.writeBlob(new ByteArrayInputStream(sbomLessJar()));
        index.rebuild();

        String hash = store.list("blobs").getFirst();
        assertThat(store.readVersioned("dependents/nosbom/" + hash))
                .as("the blob is cached as carrying no SBOM - an immutable blob's SBOM-lessness is permanent")
                .isPresent();

        // A second pass over a store that throws (unchecked, so a real open WOULD derail the rebuild - a bad-blob
        // IOException is swallowed, so the crash must not look like one) if that blob is opened still succeeds: the
        // negative cache means the SBOM-less blob is never re-decompressed.
        ArtifactStore refusing = new OpenRefusingStore(store, "blobs/" + hash);
        new DependentsIndex(refusing).rebuild();

        assertThat(new DependentsIndex(store).coordinates())
                .as("still an authoritative empty index, and the blob was not re-opened").isEmpty();
    }

    @Test
    void a_read_that_fails_mid_archive_is_not_negative_cached_while_a_fully_read_sbom_less_jar_is() throws IOException {
        // A blob that DOES carry an SBOM, but whose stream is cut part way through (a socket reset mid-jar): the
        // interrupted read must NOT be recorded as an authoritative "no SBOM", or one transient failure permanently
        // drops this artifact's reverse edges from every blast-radius / dependents answer with no repair - a swept
        // IOException read as an empty graph would be negative-cached forever. It is retried next pass.
        // A large incompressible entry sits AHEAD of the SBOM, so a stream cut inside it fails the read part way
        // through - after the manifest, before the SBOM entry is ever reached - exactly a socket reset mid-jar.
        store.writeBlob(new ByteArrayInputStream(
                jarWithLeadingFiller(bom(APP1_COORD, List.of(CORE, API)), 40_000)));
        String withSbom = store.list("blobs").getFirst();

        // ...and a genuinely SBOM-less jar, read cleanly to its end: the legitimate negative that MUST still be cached,
        // so a real SBOM-less artifact is not re-decompressed every pass. The two outcomes are told apart by whether
        // the archive was fully consumed, not by an empty result alone.
        store.writeBlob(new ByteArrayInputStream(sbomLessJar()));
        String sbomLess = store.list("blobs").stream().filter(hash -> !hash.equals(withSbom)).findFirst().orElseThrow();

        ArtifactStore resetting = new MidArchiveResetStore(store, "blobs/" + withSbom);
        new DependentsIndex(resetting).rebuild();

        assertThat(store.readVersioned("dependents/nosbom/" + withSbom))
                .as("a read that failed part way is transient - retried next pass, never cached as carrying no SBOM")
                .isEmpty();
        assertThat(store.readVersioned("dependents/nosbom/" + sbomLess))
                .as("a jar read cleanly to its end with no SBOM is a permanent negative - still cached").isPresent();

        // Retryable, not lost: a later clean pass (the stream is fine now) indexes the artifact's reverse edges, which
        // a permanent negative-cache entry would have dropped forever.
        new DependentsIndex(store).rebuild();
        assertThat(new DependentsIndex(store).dependents(LOG4J_CORE))
                .as("the SBOM-carrying artifact is indexed on the retry pass, not silently dropped").containsExactly(APP1);
    }

    @Test
    void the_walk_less_rebuild_pages_the_blob_namespace_rather_than_snapshotting_it_with_list() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));

        // A walk-less rebuild must page the blob namespace through the ordered page() primitive (which a native-paging
        // object store answers without materialising every key), never a whole-namespace list("blobs") snapshot. A
        // store that refuses list("blobs") - but answers page() (delegated, so it lists the real backend, not this
        // spy) - proves the rebuild pages: it still builds the full index.
        ArtifactStore paging = new BlobListRefusingStore(store);
        new DependentsIndex(paging).rebuild();

        assertThat(new DependentsIndex(store).dependents(LOG4J_CORE)).containsExactly(APP1);
    }

    @Test
    void the_scheduled_task_rebuilds_the_index_for_a_repository() throws IOException {
        publish(APP1_COORD, List.<String[]>of(CORE));

        new DependentsIndexTask(Duration.ofHours(1)).repository(context(store));

        assertThat(new DependentsIndex(store).dependents(LOG4J_CORE)).containsExactly(APP1);
    }

    @Test
    void the_pass_is_discovered_gated_and_cooperative_exactly_when_a_walk_is_installed() {
        UnaryOperator<String> enabled = key -> "dependents-index".equals(key) ? "true" : null;
        List<MaintenanceTask> tasks = MaintenanceTaskProvider.resolve(enabled);
        MaintenanceTask task = tasks.stream()
                .filter(candidate -> candidate.name().equals("dependents")).findFirst().orElseThrow();
        assertThat(task.exclusion())
                .as("the store walk is on this graph, so replicas cooperate on the shared pass - no whole-pass lease")
                .isEqualTo(MaintenanceTask.Exclusion.WALK_CLAIM);
        assertThat(task.interval()).isEqualTo(Duration.ofHours(1));         // the PT1H default

        assertThat(new DependentsIndexTask(Duration.ofHours(1)).exclusion())
                .as("walk-less the whole recompute mutates shared state, so it holds the single-writer lease")
                .isEqualTo(MaintenanceTask.Exclusion.LEASE);

        assertThat(MaintenanceTaskProvider.resolve(key -> "dependents-index".equals(key) ? "false" : null))
                .as("the dial still switches it off")
                .extracting(MaintenanceTask::name).doesNotContain("dependents");
        assertThat(MaintenanceTaskProvider.resolve(key -> null))
                .as("and unset means on, as it does for the search index it mirrors")
                .extracting(MaintenanceTask::name).contains("dependents");
        assertThat(MaintenanceTaskProvider.installed()).contains("dependents");
    }

    @Test
    void the_query_seam_is_discovered_and_reads_the_same_index() throws IOException {
        publish(APP1_COORD, List.of(CORE, API));
        index.rebuild();

        DependentsQueryProvider provider = DependentsQueryProvider.installed()
                .orElseThrow(() -> new AssertionError("the query provider is not discovered"));
        DependentsQuery query = provider.over(store);

        assertThat(query.dependents(LOG4J_CORE)).containsExactly(APP1);
        assertThat(query.dependents(LOG4J_API)).containsExactly(APP1);          // the transitive tree, through the SPI
        assertThat(query.coordinates()).containsExactly(LOG4J_API, LOG4J_CORE);
    }

    @Test
    void the_settings_surface_only_the_installed_dials() {
        List<String> keys = SettingsContributor.all().stream().map(Setting::key).toList();

        assertThat(keys).contains("dependents-index", "dependents-interval");
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

    /** A valid SBOM-carrying jar with a large, incompressible entry written AHEAD of the SBOM, so a stream cut inside
     *  that leading entry (see {@link MidArchiveResetStore}) fails the read part way through - past the manifest, before
     *  the SBOM entry is reached - the "socket reset mid-jar on a blob that DOES carry an SBOM" the negative cache must
     *  not record as an authoritative "no SBOM". The filler is random bytes so it does not deflate away, keeping its
     *  streamed length predictable and well past any early truncation point. */
    private static byte[] jarWithLeadingFiller(String bom, int fillerBytes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", "META-INF/sbom/app.cdx.json");
        byte[] filler = new byte[fillerBytes];
        new Random(1).nextBytes(filler);                        // incompressible, so the stored entry stays this large
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("data/filler.bin"));  // read (skipped) before the SBOM - where the reset lands
            jar.write(filler);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
            jar.write(bom.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** A jar carrying a crafted CycloneDX XML SBOM whose dependency graph nests far deeper than the parser walks -
     *  the stack-overflow vector the sweep must survive by refusing it as an empty graph. */
    private static byte[] deeplyNestedXmlBomJar() throws IOException {
        StringBuilder xml = new StringBuilder("<bom xmlns=\"http://cyclonedx.org/schema/bom/1.6\"><dependencies>");
        for (int level = 0; level < PATHOLOGICAL_NESTING; level++) {
            xml.append("<dependency ref=\"pkg:maven/com.example/d").append(level).append("@1.0.0\">");
        }
        xml.append("</dependency>".repeat(PATHOLOGICAL_NESTING)).append("</dependencies></bom>");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", "META-INF/sbom/app.cdx.xml");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.xml"));
            jar.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] sbomLessJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("com/example/App.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** The URL-encoding the index applies to every shard token (a coordinate that itself holds a space or newline). */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The shard object key a coordinate lands in - the first byte of its SHA-256 - so a test can write straight to the
     *  object {@code dependents(coordinate)} reads, mirroring the index's own sharding. */
    private static String shardKey(String coordinate) throws Exception {
        // The shard byte is taken over the NEUTRAL spelling, so a stored purl and the group:name:version a
        // report line keys on land in the same object - mirrored here through the SPI's own neutralise, never a
        // second copy of the mapping.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(DependentsQuery.neutralise(coordinate).getBytes(StandardCharsets.UTF_8));
        return "dependents/" + HexFormat.of().formatHex(digest, 0, 1);
    }

    /** Delegates everything to a real store; a subclass observes or fails single calls. Overrides {@code page} to the
     *  delegate so the default paging lists the real backend, never the (possibly refusing) subclass's own list. */
    private abstract static class ForwardingStore extends ForwardingArtifactStore {

        ForwardingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

    }

    /** Throws an unchecked exception if the named blob is opened - so a rebuild that opens it fails loudly, and one
     *  that never opens it (the negative cache did its job) passes. */
    private static final class OpenRefusingStore extends ForwardingStore {

        private final String refused;

        private OpenRefusingStore(ArtifactStore delegate, String refused) {
            super(delegate);
            this.refused = refused;
        }

        @Override
        public InputStream open(String key) throws IOException {
            if (key.equals(refused)) {
                throw new IllegalStateException("the SBOM-less blob was re-opened: " + key);
            }
            return super.open(key);
        }
    }

    /** Opens the named blob as a stream that yields its first half and then throws - a socket reset part way through
     *  an otherwise-valid, SBOM-carrying jar. The manifest (written first, tiny) is read fine, so extraction starts
     *  and fails mid-archive, exactly the transient failure that must not be recorded as an authoritative negative. */
    private static final class MidArchiveResetStore extends ForwardingStore {

        private final String target;

        private MidArchiveResetStore(ArtifactStore delegate, String target) {
            super(delegate);
            this.target = target;
        }

        @Override
        public InputStream open(String key) throws IOException {
            if (!key.equals(target)) {
                return super.open(key);
            }
            byte[] full;
            try (InputStream in = super.open(key)) {
                full = in.readAllBytes();
            }
            return new FailingAfter(full, full.length / 2);     // cut mid-jar, past the manifest, before the SBOM entry
        }
    }

    /** Delivers exactly {@code limit} bytes of {@code data} and then throws on any further read - a stream reset. */
    private static final class FailingAfter extends InputStream {

        private final byte[] data;
        private final int limit;
        private int pos;

        private FailingAfter(byte[] data, int limit) {
            this.data = data;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (pos >= limit) {
                throw new IOException("simulated socket reset mid-jar");
            }
            return data[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (pos >= limit) {
                throw new IOException("simulated socket reset mid-jar");
            }
            int n = Math.min(len, limit - pos);
            System.arraycopy(data, pos, buffer, off, n);
            pos += n;
            return n;
        }
    }

    /** Refuses a whole-namespace {@code list("blobs")} snapshot; {@code page} still works (it delegates, so it lists
     *  the real backend, not this spy), so only a rebuild that pages the blob namespace passes. */
    private static final class BlobListRefusingStore extends ForwardingStore {

        private BlobListRefusingStore(ArtifactStore delegate) {
            super(delegate);
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.equals("blobs")) {
                throw new IllegalStateException("the walk-less rebuild must page the blob namespace, not list it");
            }
            return super.list(prefix);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}

    private static RepositoryContext context(ArtifactStore store) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "default";
            }

            @Override
            public String repository() {
                return "app";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> null;
            }

            @Override
            public Instant now() {
                return Instant.parse("2026-07-03T00:00:00Z");
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
