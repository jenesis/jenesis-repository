package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.osv.OsvMirrorSource;
import build.jenesis.repository.feed.FeedResponse;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The OSV mirror over a recorded export and a real filesystem store: an ecosystem nobody selects is never drawn, one
 * that is selected is drawn whole from its archive and answers every version locally - by the versions a record lists
 * and by its ranges in the ecosystem's own order, never by a withdrawn record - and an ecosystem not yet drawn is an
 * outage rather than clean. Between builds the change list keeps the copy current, moving a record off a package it no
 * longer names; a rebuild replaces the copy whole and deletes the one before; and a draw that fails keeps the copy
 * before serving.
 */
class OsvMirrorSourceTest {

    private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    @TempDir
    Path elsewhere;

    private ArtifactStore space;
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    /** The export: each ecosystem's records by id, and its change list's lines, newest first. */
    private final Map<String, Map<String, String>> records = new HashMap<>();
    private final Map<String, List<String>> lists = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private boolean archivesFail;

    @BeforeEach
    void setUp() {
        space = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("signals")
                .scope("osv-mirror");
        record("npm", "GHSA-lodash", "2026-10-01T00:00:00Z", """
                {"id":"GHSA-lodash","summary":"Prototype pollution","aliases":["CVE-2026-0001"],
                 "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}],
                 "affected":[{"package":{"ecosystem":"npm","name":"lodash"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"4.17.21"}]}]}]}""");
        record("npm", "MAL-evil", "2026-10-02T00:00:00Z", """
                {"id":"MAL-evil","summary":"Malicious code in evil",
                 "affected":[{"package":{"ecosystem":"npm","name":"evil"},"versions":["1.0.0"]}]}""");
        record("npm", "GHSA-gone", "2026-10-03T00:00:00Z", """
                {"id":"GHSA-gone","withdrawn":"2026-10-03T00:00:00Z",
                 "affected":[{"package":{"ecosystem":"npm","name":"lodash"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}""");
        record("npm", "GHSA-pad", "2026-10-04T00:00:00Z", """
                {"id":"GHSA-pad","affected":[{"package":{"ecosystem":"npm","name":"left-pad"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"1.0.0"},{"fixed":"1.3.0"}]}]}]}""");
        record("Maven", "GHSA-maven", "2026-10-01T00:00:00Z", """
                {"id":"GHSA-maven","affected":[{"package":{"ecosystem":"Maven","name":"org.acme:lib"},
                   "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.0"},{"last_affected":"2.3"}]}]}]}""");
        record("PyPI", "PYSEC-1", "2026-10-01T00:00:00Z", """
                {"id":"PYSEC-1","affected":[{"package":{"ecosystem":"PyPI","name":"Foo_Bar"},"versions":["1.0"]}]}""");
    }

    @Test
    void an_ecosystem_no_repository_selects_is_never_drawn() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of());

        assertThat(mirror.refresh().fetched()).isFalse();
        mirror.drawChanges();

        assertThat(asked).as("nothing selects the mirror, so it draws nothing at all").isEmpty();
    }

    @Test
    void a_lookup_of_an_ecosystem_not_yet_drawn_is_an_outage_never_clean() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("npm"));

        assertThatThrownBy(() -> mirror.advisories("npm", "lodash", "4.17.20"))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("no copy of npm");
        assertThat(mirror.advisories("NotOurs", "thing", "1.0")).as("an ecosystem OSV does not cover").isEmpty();
    }

    @Test
    void a_selected_ecosystem_is_drawn_whole_and_answers_every_version_locally() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("npm"));

        assertThat(mirror.refresh().authoritative()).isTrue();

        assertThat(asked).as("the selected ecosystem's list head and archive, and no other ecosystem's")
                .containsExactly("/npm/modified_id.csv", "/npm/all.zip");
        assertThat(mirror.advisories("npm", "lodash", "4.17.20"))
                .as("inside the range, and never the withdrawn record").singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.id()).isEqualTo("GHSA-lodash");
                    assertThat(advisory.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(advisory.cves()).containsExactly("CVE-2026-0001");
                    assertThat(advisory.fixed()).isEqualTo("4.17.21");
                });
        assertThat(mirror.advisories("npm", "lodash", "4.17.21")).as("the fixed version").isEmpty();
        assertThat(mirror.advisories("npm", "evil", "1.0.0")).extracting(AdvisorySource.Advisory::malicious)
                .containsExactly(true);
        assertThat(mirror.advisories("npm", "evil", "1.0.1")).as("a version the record does not list").isEmpty();
        assertThat(mirror.advisories("npm", "nothing", "1.0.0")).as("a package no record names").isEmpty();
        assertThat(mirror.snapshot()).isPresent();
    }

    @Test
    void ranges_are_read_in_the_ecosystems_own_order_and_names_as_it_treats_them() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("Maven", "PyPI"));
        mirror.refresh();

        assertThat(mirror.advisories("Maven", "org.acme:lib", "2.3")).as("the last affected version").hasSize(1);
        assertThat(mirror.advisories("Maven", "org.acme:lib", "1.10")).as("numbers, not text").hasSize(1);
        assertThat(mirror.advisories("Maven", "org.acme:lib", "2.4")).isEmpty();
        assertThat(mirror.advisories("Maven", "org.acme:lib", "10.0")).isEmpty();
        assertThat(mirror.advisories("Maven", "org.acme:lib", "0.9")).isEmpty();
        assertThat(mirror.advisories("PyPI", "foo-bar", "1.0")).as("PyPI's normalised name").hasSize(1);
    }

    @Test
    void the_change_list_keeps_the_copy_current_between_builds() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("npm"));
        mirror.refresh();
        long before = mirror.changes(0).latest();
        // Fixed earlier than recorded, and moved from left-pad to right-pad.
        record("npm", "GHSA-lodash", "2026-10-05T01:00:00Z", """
                {"id":"GHSA-lodash","affected":[{"package":{"ecosystem":"npm","name":"lodash"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"4.17.20"}]}]}]}""");
        record("npm", "GHSA-pad", "2026-10-05T02:00:00Z", """
                {"id":"GHSA-pad","affected":[{"package":{"ecosystem":"npm","name":"right-pad"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"1.0.0"},{"fixed":"1.3.0"}]}]}]}""");
        asked.clear();

        mirror.drawChanges();

        assertThat(asked).as("the list, and each record named since the position the build recorded")
                .containsExactly("/npm/modified_id.csv", "/npm/GHSA-lodash.json", "/npm/GHSA-pad.json");
        assertThat(mirror.advisories("npm", "lodash", "4.17.20")).isEmpty();
        assertThat(mirror.advisories("npm", "lodash", "4.17.19")).hasSize(1);
        assertThat(mirror.advisories("npm", "left-pad", "1.2.0")).as("taken off the package it left").isEmpty();
        assertThat(mirror.advisories("npm", "right-pad", "1.2.0")).hasSize(1);
        AdvisorySource.ChangeLog log = mirror.changes(before);
        assertThat(log.packages()).extracting(AdvisorySource.Package::ecosystem, AdvisorySource.Package::coordinate)
                .containsExactlyInAnyOrder(tuple("npm", "lodash"), tuple("npm", "left-pad"),
                        tuple("npm", "right-pad"));
        assertThat(log.gap()).isFalse();
    }

    @Test
    void a_rebuild_replaces_the_copy_whole_and_deletes_the_one_before_the_copy_it_replaced() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("npm"));
        mirror.refresh();
        Optional<String> first = mirror.snapshot();
        Set<String> generations = generations();
        records.get("npm").remove("MAL-evil");

        now.set(START.plus(Duration.ofDays(1)));
        mirror.refresh();
        assertThat(mirror.snapshot()).as("inside the rebuild interval nothing is drawn").isEqualTo(first);

        now.set(START.plus(Duration.ofDays(8)));
        mirror.refresh();

        assertThat(mirror.snapshot()).isNotEqualTo(first);
        assertThat(mirror.advisories("npm", "evil", "1.0.0")).as("what the new archive no longer holds").isEmpty();
        Set<String> second = generations();
        assertThat(second).as("the new generation serving, the one it replaced kept for a node still reading it")
                .hasSize(2).containsAll(generations);

        now.set(START.plus(Duration.ofDays(16)));
        mirror.refresh();

        assertThat(generations()).as("the next build deletes the generation two builds back").hasSize(2)
                .doesNotContainAnyElementsOf(generations);
    }

    @Test
    void a_node_holding_the_copy_it_read_still_finds_its_records_after_another_node_rebuilds() throws IOException {
        OsvMirrorSource reader = source();
        reader.mirror(Set.of("npm"));
        reader.refresh();
        assertThat(reader.advisories("npm", "evil", "1.0.0")).as("read once, so its state is held").hasSize(1);

        // Another node rebuilds inside the window the reader holds the state it read for. It reaches the same files
        // through a link, so it is a store of its own identity - a cache of its own - as a second node's is.
        now.set(START.plus(Duration.ofSeconds(10)));
        OsvMirrorSource rebuilder = source(Duration.ofSeconds(5), peer());
        rebuilder.mirror(Set.of("npm"));
        rebuilder.refresh();

        assertThat(reader.advisories("npm", "evil", "1.0.0"))
                .as("the generation the reader still names has not been deleted under it - a missing package "
                        + "document would read as clean").hasSize(1);
    }

    @Test
    void a_draw_that_fails_keeps_the_copy_before_serving() throws IOException {
        OsvMirrorSource mirror = source();
        mirror.mirror(Set.of("npm"));
        mirror.refresh();
        archivesFail = true;
        now.set(START.plus(Duration.ofDays(8)));

        mirror.refresh();

        assertThat(mirror.advisories("npm", "evil", "1.0.0")).hasSize(1);
    }

    private OsvMirrorSource source() {
        return source(Duration.ofDays(7));
    }

    private OsvMirrorSource source(Duration rebuild) {
        return source(rebuild, space);
    }

    /** The mirror's space as another node reaches it: the same files under a root of another name. */
    private ArtifactStore peer() throws IOException {
        Path link = Files.createSymbolicLink(elsewhere.resolve("peer"), root);
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? link.toString() : null).scope("signals")
                .scope("osv-mirror");
    }

    private OsvMirrorSource source(Duration rebuild, ArtifactStore over) {
        return OsvMirrorSource.responding((request, _) -> {
            String path = request.uri().getPath();
            asked.add(path);
            String ecosystem = path.substring(1, path.indexOf('/', 1));
            String file = path.substring(path.indexOf('/', 1) + 1);
            Map<String, String> held = records.getOrDefault(ecosystem, Map.of());
            return switch (file) {
                case "modified_id.csv" -> FeedResponse.of(200, String.join("\n", lists.getOrDefault(ecosystem,
                        List.of())));
                case "all.zip" -> archivesFail ? FeedResponse.of(503, "") : new FeedResponse(200, Map.of(),
                        new ByteArrayInputStream(zip(held)));
                default -> {
                    String id = file.substring(0, file.length() - ".json".length());
                    yield held.containsKey(id) ? FeedResponse.of(200, held.get(id)) : FeedResponse.of(404, "");
                }
            };
        }, () -> over, clock, rebuild);
    }

    /** Add {@code json} as record {@code id} of {@code ecosystem}'s export, modified at {@code modified}, at the head of
     *  its change list. */
    private void record(String ecosystem, String id, String modified, String json) {
        records.computeIfAbsent(ecosystem, _ -> new LinkedHashMap<>()).put(id, json.replace("\n", ""));
        lists.computeIfAbsent(ecosystem, _ -> new ArrayList<>()).addFirst(modified + "," + id);
    }

    private static byte[] zip(Map<String, String> records) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> record : records.entrySet()) {
                zip.putNextEntry(new ZipEntry(record.getKey() + ".json"));
                zip.write(record.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** The generations the npm copy holds objects under. */
    private Set<String> generations() throws IOException {
        Set<String> generations = new TreeSet<>();
        space.scan("npm", "", 1000, listed -> {
            String[] segments = listed.key().split("/");
            if (segments.length > 2) {
                generations.add(segments[1]);
            }
        });
        return generations;
    }
}
