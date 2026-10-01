package build.jenesis.repository.search.lucene.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.search.SearchQuery;
import build.jenesis.repository.search.SearchQueryProvider;
import build.jenesis.repository.search.lucene.LuceneSearchQueryProvider;
import build.jenesis.repository.search.lucene.SearchIndexTask;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.inventory.LicenseInventory;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded Lucene search index over a real filesystem store, no network and no framework: a sweep builds an index
 * from seeded pointer metadata, a query finds coordinates by segment and prefix, the snapshot round-trips through the
 * store, the compare-and-set cutover races safely, the volatile-swap reader picks up a new generation and reports no
 * usable index before the first sweep and on a format mismatch (so the caller answers by name), and one
 * tenant's query never sees another's. No artifact blob is ever opened.
 */
class SearchIndexTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store(String tenant, String repository) {
        return ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope(tenant).scope(repository);
    }

    private void publish(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        String path = "/" + ecosystem + "/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record(ecosystem, coordinate, version, false, NOW);
    }

    private void sweep(ArtifactStore store) throws IOException {
        new SearchIndexTask(INTERVAL).repository(context(store));
    }

    /** A query with an always-refresh reader, so a new generation is observed the moment a sweep publishes it. */
    private SearchQuery query(ArtifactStore store, String scope) {
        return new LuceneSearchQueryProvider(Duration.ZERO).over(store, scope);
    }

    @Test
    void a_sweep_builds_an_index_a_query_finds_seeded_coordinates() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "com.google.guava:guava", "33.0.0-jre");
        publish(store, "npm", "@angular/core", "17.1.0");
        publish(store, "pypi", "numpy", "2.0.1");

        sweep(store);
        SearchQuery query = query(store, "default/app");

        assertThat(hits(query, "guava")).containsExactly("com.google.guava:guava:33.0.0-jre");
        assertThat(hits(query, "angular")).containsExactly("@angular/core:17.1.0");   // the @-prefixed segment splits
        assertThat(hits(query, "num")).containsExactly("numpy:2.0.1");                // a prefix within a segment
        assertThat(hits(query, "")).as("empty query returns every indexed coordinate, sorted")
                .containsExactly("@angular/core:17.1.0", "com.google.guava:guava:33.0.0-jre", "numpy:2.0.1");
    }

    @Test
    void a_coordinate_name_answers_that_coordinates_versions_before_strangers_sharing_a_version_token() throws IOException {
        // "lib-8" is a package name; "lib-0" at version 1.8.0 shares the token "8" through its version. The name query
        // answers the named package's versions and nothing else; a query no name matches (a version) still falls
        // through to the whole text, so a version remains findable.
        ArtifactStore store = store("default", "app");
        publish(store, "npm", "lib-8", "1.0.0");
        publish(store, "npm", "lib-8", "1.1.0");
        publish(store, "npm", "lib-0", "1.8.0");
        sweep(store);
        SearchQuery query = query(store, "default/app");

        assertThat(hits(query, "lib-8")).containsExactly("lib-8:1.0.0", "lib-8:1.1.0");
        assertThat(hits(query, "1.8.0")).as("a version query matches the whole text").contains("lib-0:1.8.0");
        assertThat(hits(query, "lib")).containsExactly("lib-0:1.8.0", "lib-8:1.0.0", "lib-8:1.1.0");
    }

    @Test
    void the_snapshot_round_trips_through_the_store() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.2.3");
        sweep(store);

        // A fresh provider (no in-heap carry-over) loads the index purely from the stored snapshot + manifest.
        SearchQuery reloaded = new LuceneSearchQueryProvider(Duration.ZERO).over(store, "default/app");
        assertThat(hits(reloaded, "example")).containsExactly("org.example:lib:1.2.3");
        assertThat(store.readVersioned("index/search/current")).isPresent();
        assertThat(store.exists("index/search/1.manifest")).isTrue();
    }

    @Test
    void the_reader_picks_up_a_new_generation_and_holds_an_unchanged_one() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:one", "1.0");
        sweep(store);
        SearchQuery query = query(store, "default/app");
        assertThat(hits(query, "example")).containsExactly("org.example:one:1.0");
        assertThat(hits(query, "example")).as("an unchanged generation still serves consistently")
                .containsExactly("org.example:one:1.0");

        publish(store, "maven", "org.example:two", "2.0");
        sweep(store);                                                       // a new generation
        assertThat(hits(query, "example")).as("the reader token-compares and swaps in the new generation")
                .containsExactly("org.example:one:1.0", "org.example:two:2.0");
    }

    @Test
    void superseded_snapshots_are_garbage_collected() throws IOException {
        // The index/search key-space must stop growing: each sweep writes a new generation snapshot, and keeps only
        // the current generation and the one just replaced (so an in-flight reader finishes streaming) - everything
        // older is deleted, or the space grows by one snapshot per sweep forever.
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        for (int pass = 0; pass < 4; pass++) {
            sweep(store);
        }

        assertThat(store.exists("index/search/4.manifest")).as("current generation").isTrue();
        assertThat(store.exists("index/search/3.manifest")).as("the one just replaced, for in-flight readers").isTrue();
        assertThat(store.exists("index/search/2.manifest")).as("superseded snapshot reclaimed").isFalse();
        assertThat(store.exists("index/search/1.manifest")).isFalse();
        assertThat(store.list("index/search"))
                .as("the key-space holds exactly the manifest and the two retained generations - superseded "
                        + "snapshots are GC'd (the space does not grow per sweep)")
                .containsExactlyInAnyOrder("current", "3.manifest", "4.manifest", "segments");
    }

    @Test
    void the_cutover_races_two_sweeps_over_a_committed_generation() throws IOException, InterruptedException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        sweep(store);                                                       // establish generation 1: a COMMITTED manifest
        Optional<ArtifactStore.Versioned> generationOne = store.readVersioned("index/search/current");
        assertThat(generationOne).as("generation 1 is committed before the race").isPresent();
        Object generationOneToken = generationOne.get().token();

        // A second coordinate published before the race, so the gen1 -> gen2 rebuild carries new content over.
        publish(store, "maven", "org.example:two", "2.0");

        // Race two full rebuilds that BOTH read the committed generation-1 manifest and its version token, so both
        // attempt the gen1 -> gen2 cutover as an If-Match compare-and-set against the SAME token - the real cross-node
        // cutover, NOT the create-only (ifNoneMatch:*) put that a race from an empty store would both take on
        // bootstrap. The store's token strictly advances on the winning write, so exactly one putManifest wins the
        // compare-and-set; the loser observes putManifest==false, drops its orphan generation, and returns without
        // double-committing. (search-incremental=false pins every sweep to the full rebuild that owns this cutover.)
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Runnable pass = () -> {
            try {
                barrier.await();
                sweep(store);
            } catch (Throwable t) {
                failures.add(t);
            }
        };
        Thread a = new Thread(pass);
        Thread b = new Thread(pass);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(failures)
                .as("both sweeps completed; the If-Match ETag compare-and-set serialized the gen1 -> gen2 cutover, so "
                        + "the loser hit the putManifest==false backstop instead of throwing")
                .isEmpty();
        // The cutover ADVANCED past gen1 - not a stalled gen1, not a second bootstrap create. Deliberately not
        // "exactly generation 2": generation 3 is a documented outcome of this very race, not a corruption. The
        // loser's putManifest compare-and-set fails, SearchIndexTask drops its orphan snapshot and returns false
        // precisely so the caller full-rebuilds, and a rebuild that lands after the winner's cutover commits the
        // next generation. Pinning the literal made this assert one interleaving of a race it provokes on purpose,
        // and it failed under load having proven every invariant it is actually about.
        Optional<ArtifactStore.Versioned> after = store.readVersioned("index/search/current");
        assertThat(after).isPresent();
        String manifest = new String(after.get().content(), StandardCharsets.UTF_8);
        int generation = generationOf(manifest);
        assertThat(generation)
                .as("the winner cut the manifest over past generation 1; 2 is the common outcome and 3 is the "
                        + "documented one when the loser's full rebuild lands after the winner's cutover: %s",
                        manifest)
                .isGreaterThanOrEqualTo(2);
        assertThat(after.get().token()).as("the winning If-Match write advanced the manifest token past gen1's")
                .isNotEqualTo(generationOneToken);
        assertThat(store.exists("index/search/" + generation + ".manifest"))
                .as("the snapshot the winning cutover committed, whichever generation it reached").isTrue();
        // The always-refresh reader token-compares and swaps in the winning generation, serving both coordinates.
        assertThat(hits(query(store, "default/app"), "example"))
                .as("the reader swaps in the committed generation 2")
                .containsExactly("org.example:lib:1.0", "org.example:two:2.0");
    }

    @Test
    void an_unbuilt_index_reports_no_usable_index_so_the_caller_answers_by_name() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        // No sweep yet: the manifest is absent, so the reader answers an EMPTY Optional (the caller answers by name),
        // which is a different answer from a present-but-empty page (the index is usable and nothing matched). The two
        // Clause 3 forbids answering the first as null.
        assertThat(query(store, "default/app").search("example", null, SearchQuery.MAX_PAGE))
                .as("no usable index is an absent page, never an empty one").isEmpty();
        assertThat(hits(query(store, "default/app"), "example")).isNull();

        sweep(store);
        assertThat(query(store, "default/app").search("nothing-matches-this", null, SearchQuery.MAX_PAGE))
                .as("a usable index that matches nothing is a PRESENT page with no rows - the opposite answer")
                .hasValueSatisfying(page -> assertThat(page.hits()).isEmpty());
    }

    @Test
    void a_page_past_the_limit_carries_a_cursor_that_reaches_the_rest_and_never_a_silently_short_list()
            throws IOException {
        ArtifactStore store = store("default", "app");
        for (int index = 0; index < 7; index++) {
            publish(store, "maven", "org.example:lib" + index, "1.0");
        }
        sweep(store);
        SearchQuery query = query(store, "default/app");

        // The bound the caller named is honoured AND its outcome is visible: three rows and a cursor, never three
        // rows that read like the whole match set - a search clamped at the implementation's own maximum that says nothing
        // would do exactly that.
        SearchQuery.Hits first = query.search("", null, 3).orElseThrow();
        assertThat(first.hits()).hasSize(3);
        assertThat(first.truncated()).as("the index proved more matches remain past this page").isTrue();

        List<String> walked = new ArrayList<>(first.hits().stream().map(SearchQuery.Hit::display).toList());
        SearchQuery.Hits page = first;
        while (page.truncated()) {
            page = query.search("", page.nextCursor().orElseThrow(), 3).orElseThrow();
            walked.addAll(page.hits().stream().map(SearchQuery.Hit::display).toList());
        }
        assertThat(walked).as("paging the cursor reaches every match exactly once, in display order")
                .containsExactlyElementsOf(hits(query, ""))
                .doesNotHaveDuplicates()
                .hasSize(7);
        assertThat(page.nextCursor()).as("the last page names no continuation").isEmpty();
    }

    @Test
    void a_format_mismatch_reports_no_results_and_the_next_sweep_rebuilds() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        // A manifest from a future index format this reader cannot open.
        byte[] future = ("jenesis-search 1\ngeneration 7\nformat 999\ndocuments 1\nchecksum x\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(store.writeVersioned("index/search/current", future, null)).isTrue();

        assertThat(query(store, "default/app").search("example", null, SearchQuery.MAX_PAGE))
                .as("an unreadable format reports no usable index, not a stale result - an absent page, not an empty one")
                .isEmpty();

        sweep(store);                                                      // discards and rebuilds in the current format
        assertThat(hits(query(store, "default/app"), "example")).containsExactly("org.example:lib:1.0");
    }

    @Test
    void a_hostile_query_with_thousands_of_terms_stays_bounded_and_never_500s() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "com.google.guava:guava", "33.0.0-jre");
        sweep(store);
        SearchQuery query = query(store, "default/app");

        // ~2000 separator-split segments once overflowed Lucene's default 1024-clause ceiling (TooManyClauses, a
        // RuntimeException the search endpoint surfaced as a 500); the per-query clause cap keeps it bounded, so the
        // query returns a normal (here empty) result rather than throwing.
        String hostile = IntStream.range(0, 2000).mapToObj(segment -> "seg" + segment)
                .collect(Collectors.joining(" "));
        assertThat(hits(query, hostile)).as("a bounded query over a built index, never a 500").isNotNull();
    }

    @Test
    void a_corrupt_manifest_reports_no_results_and_the_next_sweep_rebuilds() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:lib", "1.0");
        // A manifest whose generation value is garbled (bit-rot or a hand-edit) - Integer.parseInt once threw out of
        // parse(), which the Lease-guarded sweep also calls, so the index would never self-heal.
        byte[] corrupt = ("jenesis-search 1\ngeneration garbage\nformat 1\ndocuments 1\nchecksum x\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(store.writeVersioned("index/search/current", corrupt, null)).isTrue();

        assertThat(hits(query(store, "default/app"), "example"))
                .as("a corrupt manifest reports no usable index, not a 500").isNull();

        sweep(store);                                                      // the Lease-guarded sweep rebuilds
        assertThat(hits(query(store, "default/app"), "example")).containsExactly("org.example:lib:1.0");
    }

    @Test
    void one_tenants_query_never_sees_anothers() throws IOException {
        ArtifactStore alpha = store("alpha", "app");
        ArtifactStore beta = store("beta", "app");
        publish(alpha, "maven", "com.alpha:only", "1.0");
        publish(beta, "maven", "com.beta:only", "1.0");
        sweep(alpha);
        sweep(beta);

        assertThat(hits(query(alpha, "alpha/app"), "only")).containsExactly("com.alpha:only:1.0");
        assertThat(hits(query(beta, "beta/app"), "only")).containsExactly("com.beta:only:1.0");
        assertThat(hits(query(alpha, "alpha/app"), "beta")).isEmpty();    // built, but no beta coordinate present
    }

    @Test
    void the_pass_is_installed_discovered_and_single_writer_and_decides_per_repository() {
        MaintenanceTask task = MaintenanceTaskProvider.resolve(key -> null).stream()
                .filter(candidate -> candidate.name().equals("search-index")).findFirst().orElseThrow();
        assertThat(task.exclusion()).as("Lucene wants one writer, so it holds the single-writer lease")
                .isEqualTo(MaintenanceTask.Exclusion.LEASE);
        assertThat(task.interval()).isEqualTo(INTERVAL);

        assertThat(MaintenanceTaskProvider.installed()).contains("search-index");
        assertThat(SearchQueryProvider.installed()).as("the query provider is discovered too").isPresent();
    }

    @Test
    void the_settings_surface_the_index_dials_and_not_the_retired_ones() {
        List<String> keys = SettingsContributor.all().stream().map(Setting::key).toList();

        assertThat(keys).contains("search-index-interval", "search-reconcile-interval");
        assertThat(keys).as("whether a repository has an index is its full-text-search setting, and the reconcile "
                        + "is due by time: neither retired dial is declared")
                .doesNotContain("search-index", "search-reconcile-passes");
    }

    /** Publish under the {@code fake} ecosystem the {@link FakeLicensedFormat}/{@link FakeLicensedInspector} claim, so
     *  a release with no licenses section can be backfilled from its stored metadata through the discovered inspector. */
    private void publishFake(ArtifactStore store, String coordinate, String version) throws IOException {
        String path = "/fake/" + coordinate + "/" + version + "/artifact";
        String hash = store.writeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(path, hash);
        new StoreRepositoryInventory(store).record("fake", coordinate, version, false, NOW);
    }

    private void licenseSidecar(ArtifactStore store, String ecosystem, String coordinate, String version,
                                LicenseInventory.Declared... declared) throws IOException {
        new LicenseInventory(store).record(ecosystem, coordinate, version, List.of(declared));
    }

    private static LicenseInventory.Declared named(String name) {
        return new LicenseInventory.Declared(name, null);
    }

    @Test
    void the_license_sidecar_round_trips_every_declared_shape() throws IOException {
        ArtifactStore store = store("default", "app");
        LicenseInventory inventory = new LicenseInventory(store);
        List<LicenseInventory.Declared> declared = List.of(
                new LicenseInventory.Declared("Apache License, Version 2.0", null),      // a name, no URL
                new LicenseInventory.Declared(null, "https://opensource.org/licenses/MIT"),   // a URL, no name
                new LicenseInventory.Declared("MIT", "https://opensource.org/licenses/MIT")); // both
        inventory.record("maven", "org.example:lib", "1.0", declared);

        assertThat(inventory.read("maven", "org.example:lib", "1.0")).contains(declared);
        assertThat(inventory.read("maven", "org.example:lib", "1.0"))
                .as("a present-but-empty sidecar is distinct from an absent one").isNotEmpty();
        inventory.record("maven", "org.example:bare", "1.0", List.of());
        assertThat(inventory.read("maven", "org.example:bare", "1.0")).contains(List.of());
        assertThat(inventory.read("maven", "org.example:absent", "1.0")).isEmpty();
    }

    @Test
    void license_fields_round_trip_the_snapshot_and_query_by_spdx_and_category() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:mit", "1.0");
        licenseSidecar(store, "maven", "org.example:mit", "1.0", named("MIT License"));
        sweep(store);

        // A fresh provider loads the license fields purely from the stored snapshot - a pure round-trip.
        SearchQuery reloaded = new LuceneSearchQueryProvider(Duration.ZERO).over(store, "default/app");
        assertThat(hits(reloaded, "license:MIT")).containsExactly("org.example:mit:1.0");
        assertThat(hits(reloaded, "category:permissive")).containsExactly("org.example:mit:1.0");
        assertThat(hits(reloaded, "license:GPL")).as("a license the artifact does not carry matches nothing")
                .isEmpty();
        assertThat(hits(reloaded, "category:permissive mit")).as("a license filter narrows a free-text query")
                .containsExactly("org.example:mit:1.0");
    }

    @Test
    void a_present_but_empty_filter_matches_nothing_never_the_whole_repository() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:mit", "1.0");
        licenseSidecar(store, "maven", "org.example:mit", "1.0", named("MIT License"));
        publish(store, "maven", "org.example:gpl", "1.0");
        licenseSidecar(store, "maven", "org.example:gpl", "1.0", named("GNU General Public License, version 3"));
        sweep(store);
        SearchQuery query = query(store, "default/app");

        // A filter token with no value ("license:", "category:") is an explicit but empty filter, not a browse-all.
        // Dropping its clause would leave zero clauses, which degrades to MatchAllDocsQuery - a malformed filter query
        // listing the entire repository. It must match nothing instead.
        assertThat(hits(query, "license:")).as("an empty license filter matches nothing, not everything").isEmpty();
        assertThat(hits(query, "category:")).as("an empty category filter matches nothing, not everything").isEmpty();
        assertThat(hits(query, "license:  ")).as("whitespace after the colon is still an empty filter").isEmpty();

        // A filter that names a value no artifact carries is also empty - never a fall-through to match-all.
        assertThat(hits(query, "category:bogus")).isEmpty();

        // The genuinely empty query still browses everything - the default listing is unaffected.
        assertThat(hits(query, "")).as("a truly empty query still lists every coordinate")
                .containsExactly("org.example:gpl:1.0", "org.example:mit:1.0");
    }

    @Test
    void the_backfill_covers_pre_existing_artifacts_with_no_sidecar() throws IOException {
        ArtifactStore store = store("default", "app");
        publishFake(store, "example-lib", "2.0");                        // published, but no licenses section
        assertThat(new LicenseInventory(store).read("fake", "example-lib", "2.0")).isEmpty();

        sweep(store);
        SearchQuery query = query(store, "default/app");
        assertThat(hits(query, "license:Apache-2.0")).as("the sweep re-derived the license from stored metadata")
                .containsExactly("example-lib:2.0");
        assertThat(hits(query, "category:permissive")).containsExactly("example-lib:2.0");
    }

    @Test
    void an_artifact_that_declares_no_license_lands_in_the_unknown_bucket() throws IOException {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "org.example:bare", "1.0");
        licenseSidecar(store, "maven", "org.example:bare", "1.0");       // inspected, none declared: an empty sidecar
        sweep(store);

        SearchQuery query = query(store, "default/app");
        assertThat(hits(query, "category:unknown")).containsExactly("org.example:bare:1.0");
        assertThat(hits(query, "license:MIT")).isEmpty();
    }

    @Test
    void the_gate_sidecar_is_preferred_over_re_parsing() throws IOException {
        ArtifactStore store = store("default", "app");
        publishFake(store, "example-lib", "3.0");                        // the inspector would derive Apache...
        licenseSidecar(store, "fake", "example-lib", "3.0", named("MIT License"));   // ...but a sidecar says MIT
        sweep(store);

        SearchQuery query = query(store, "default/app");
        assertThat(hits(query, "license:MIT")).as("the sidecar the gate wrote is used verbatim")
                .containsExactly("example-lib:3.0");
        assertThat(hits(query, "license:Apache-2.0")).as("the inspector was not consulted when a sidecar exists")
                .isEmpty();
    }

    @Test
    void one_tenants_licence_filter_never_sees_anothers() throws IOException {
        ArtifactStore alpha = store("alpha", "app");
        ArtifactStore beta = store("beta", "app");
        publish(alpha, "maven", "com.alpha:lib", "1.0");
        licenseSidecar(alpha, "maven", "com.alpha:lib", "1.0", named("MIT License"));
        publish(beta, "maven", "com.beta:lib", "1.0");
        licenseSidecar(beta, "maven", "com.beta:lib", "1.0", named("GNU General Public License, version 3"));
        sweep(alpha);
        sweep(beta);

        assertThat(hits(query(alpha, "alpha/app"), "category:permissive")).containsExactly("com.alpha:lib:1.0");
        assertThat(hits(query(alpha, "alpha/app"), "category:strong-copyleft")).isEmpty();
        assertThat(hits(query(beta, "beta/app"), "license:GPL")).containsExactly("com.beta:lib:1.0");
        assertThat(hits(query(beta, "beta/app"), "license:MIT")).isEmpty();
    }

    private RepositoryContext context(ArtifactStore store) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "test";
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
                // This suite is the full-rebuild / reader-swap / GC machinery (the task keeps it for
                // bootstrap, the format bump and the periodic reconcile). Pin the safety valve so every sweep is a full
                // rebuild - the behaviour this suite was written against; the O(delta) incremental steady state has its
                // own suite (SearchIncrementalTest).
                return key -> switch (key) {
                    case "search-incremental" -> "false";
                    case SearchMode.SETTING -> "true";         // the repository has asked for an index
                    default -> null;
                };
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }

    /** The coordinates one full page of {@code query} matches, or {@code null} when this repository has no usable
     *  index yet - which the SPI now says with an empty {@link Optional} rather than a {@code null} list.
     *  Every assertion below is about the rows, so the page is unwrapped here once. */
    /**
     * A search that finds its index due for a refresh answers from the generation it already holds while the new one
     * loads, rather than waiting for the load to finish.
     *
     * <p>The refresh was {@code synchronized} and ran ON the request that found the window expired, and loading a
     * changed generation fetches every segment file it names from the store - so that request waited for the whole
     * download and every other search queued on the monitor behind it, while a good generation sat in memory. Over a
     * directory that is milliseconds; over an object store it is the index's size in round trips, and it was the
     * Azurite search timeout the fleet's search-claim scenario had been filing under the emulator's throughput.
     *
     * <p>Every other test here builds a fresh reader per query, so each takes the FIRST-load path and none exercises a
     * refresh with a generation already held. This one reuses one reader across two generations and holds the second
     * generation's manifest read, so the refresh is provably in flight when the search is asked, and asks it on a
     * thread with a deadline: the failure there is a hang, not an exception, and a hang has to be turned into a red to
     * be a test at all.
     */
    @Test
    void a_search_during_a_slow_refresh_answers_from_the_generation_it_holds() throws Exception {
        ArtifactStore store = store("default", "app");
        publish(store, "maven", "com.google.guava:guava", "33.0.0-jre");
        sweep(store);

        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FaultInjectingStore slow = FaultInjectingStore.wrap(store);
        slow.tracing((op, key) -> {
            if (op == FaultInjectingStore.Op.READ_VERSIONED && key != null && key.endsWith("index/search/current")
                    && armed.compareAndSet(true, false)) {
                reached.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        SearchQuery query = new LuceneSearchQueryProvider(Duration.ZERO).over(slow, "default/app");
        assertThat(hits(query, "")).as("the first generation is loaded and held")
                .containsExactly("com.google.guava:guava:33.0.0-jre");

        publish(store, "npm", "@angular/core", "17.1.0");
        sweep(store);                                    // a second generation is committed, and the refresh is due
        armed.set(true);
        ExecutorService asker = Executors.newSingleThreadExecutor();
        try {
            Future<List<String>> during = asker.submit(() -> hits(query, ""));
            assertThat(reached.await(30, TimeUnit.SECONDS))
                    .as("vacuity guard: the refresh reached the store and is held there, so the search below is "
                            + "asked while a load is genuinely in flight")
                    .isTrue();
            assertThat(during.get(30, TimeUnit.SECONDS))
                    .as("answered from the generation already held, while the new one loads - not after it")
                    .containsExactly("com.google.guava:guava:33.0.0-jre");
        } finally {
            release.countDown();
            asker.shutdownNow();
        }

        Instant deadline = Instant.now().plusSeconds(30);
        List<String> after = hits(query, "");
        while (!after.contains("@angular/core:17.1.0") && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            after = hits(query, "");
        }
        assertThat(after).as("once the load lands, the new generation is what is served")
                .contains("@angular/core:17.1.0");
    }

    private static List<String> hits(SearchQuery query, String text) throws IOException {
        return query.search(text, null, SearchQuery.MAX_PAGE)
                .map(page -> page.hits().stream().map(SearchQuery.Hit::display).toList()).orElse(null);
    }


    /** The generation a committed manifest names. Read rather than matched, so the assertion can be an ordering. */
    private static int generationOf(String manifest) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("generation (\\d+)").matcher(manifest);
        if (!matcher.find()) {
            throw new AssertionError("no generation line in the committed manifest: " + manifest);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
