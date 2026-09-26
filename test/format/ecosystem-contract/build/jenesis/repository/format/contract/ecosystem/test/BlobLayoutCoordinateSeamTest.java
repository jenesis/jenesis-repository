package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blobs-namespace half of the format kit's coordinate-seam property, over the interface those formats actually
 * have.
 *
 * <p>{@code FormatContract.COORDINATE_TRAVERSAL_REFUSED} is stated over {@code ArtifactLayout} - the
 * {@code publish/}-namespace coordinate mapping Maven and the Jenesis module layout carry - and fixed a real
 * escape there with {@link ArtifactLayout#addressable}, on the reasoning that a coordinate arrives from a published
 * name, an advisory feed or a console form and that the paths it composes are handed to eviction, <em>which deletes
 * under them</em>. An ecosystem format serves out of the shared {@code blobs/} namespace instead, so its
 * coordinate mapping is {@link BlobLayout}, whose {@code blobKeys} is literally the list of pointer keys a retention
 * eviction deletes - and {@code ArtifactStore.delete} is not screened, only writes are. The property therefore has to
 * be proven <em>here</em>, or it is proven for the four {@code publish/} layouts and for none of the fourteen
 * that serve from {@code blobs/}.
 *
 * <p>Two halves, deliberately separated:
 * <ul>
 *   <li><b>The screen, over every discovered layout.</b> {@link #no_layout_composes_a_traversal_shaped_pointer_key()}
 *       drives all fourteen with the same hostile coordinate parts, so a layout that never grew the guard fails by
 *       name - including the capability-only {@code OciBlobLayout}, which serves nothing and therefore carries no
 *       serve-side fixture.</li>
 *   <li><b>The round trip, over the fixtured thirteen.</b> A screen is only meaningful if the seam answers at all, so
 *       {@link #a_published_coordinate_round_trips_through_its_layout()} seeds a real version through each format's own
 *       write path and requires {@code blobKeys}, {@code blobHashes} and {@code servedPaths} to resolve it and
 *       {@code describe} to map the served path back to the same pair. A {@code blobKeys} that answered empty would
 *       make every hold a silent no-op while the screen check above stayed green.</li>
 * </ul>
 */
class BlobLayoutCoordinateSeamTest {

    /** The fixtured formats, whose seam can be driven over really published content. */
    private static final List<EcosystemFormatFixture> FIXTURES = EcosystemFormatFixture.all();

    /**
     * The fixtures this suite's seam legs run over: the ones whose pointers live in the shared {@code blobs}
     * namespace.
     *
     * <p>A {@code publish/} layout has no pointer key to describe - a coordinate maps to a request-path folder and
     * a hold retracts by unpublishing under it - so running these legs over one would be asserting against an
     * emptiness rather than against a mapping. Derived from what each fixture already declares, so it cannot drift
     * from the namespaces the same fixture names.
     */
    private static final List<EcosystemFormatFixture> IN_BLOBS = FIXTURES.stream()
            .filter(fixture -> !fixture.publishesUnderPublish())
            .toList();

    /**
     * The name parts a coordinate or a version must not be able to smuggle into a composed pointer key. Deliberately
     * <em>not</em> the kit's list verbatim: a plain {@code a/b} is legitimate in this namespace (an npm
     * {@code @scope/name}, a Go module path, an RPM {@code <repo>/<name>}), which is exactly why the shared screen
     * judges a coordinate part by part rather than as a whole. Every entry here carries a part that is not a single
     * addressable segment.
     */
    private static final List<String> HOSTILE = List.of(
            "..", ".", "../..", "a/../b", "a/./b", "", "..\\b", "/", "a//b", "a\tb");

    /**
     * The formats whose layouts answer {@link BlobLayout#describePointer} today - a grow-list, so implementing one
     * more only adds a line and dropping one fails.
     *
     * <p><b>Only a fixtured format can be on this list, and that is a real bound rather than an inconvenience.</b>
     * A format on {@link #FIXTURES} has its seam driven over really published content; one that is not would be
     * asserting a parse against a reading of the key-composing code, on the path that rebuilds the rows retention
     * ages by, which is worth less than no claim at all.
     *
     * <p>That bound once excused six formats - apk, helm, winget, terraform, swift, homebrew - on the ground that
     * they had no fixture. They did: each was written for its own per-format contract suite and never added to
     * {@link #FIXTURES}, so this census skipped their layouts rather than failing them, because a layout that does
     * not override {@code describePointer} is invisible to the check rather than unverifiable by it. The six are
     * fixtured here now and claim their own keys, which is what the exemption was always waiting for.
     */
    private static final List<String> CLAIMS = List.of("npm", "rubygems", "nuget", "go", "cargo", "composer",
            "debian", "rpm", "cocoapods", "conda", "conan", "pypi",
            "helm", "terraform", "apk", "winget", "swift", "homebrew");

    /**
     * The layouts that answer {@code describePointer} and are proven somewhere other than this suite, with where.
     * A reason rather than a name, because a bare exemption list is how a gap becomes permanent.
     */
    private static final Map<String, String> VERIFIED_ELSEWHERE = Map.of(
            "oci-layout", "capability-only: it serves nothing, so it carries no serve-side fixture here by "
                    + "construction. Its pointer decoding is the tagPointer grammar, driven end to end by "
                    + "OciInventoryBackfillConsumerTest over a really pushed image and held to the thirteen "
                    + "properties of the walk-consumer contract kit through OciInventoryBackfillFixture.");

    @TempDir
    Path root;

    /** Finish any derivation this test queued before the {@code @TempDir} is deleted. A conda publish hands the
     *  {@code repodata.json.bz2} twin to {@code StoredListing.later} to keep it off the request path, and that
     *  background write races JUnit removing the directory - surfacing as {@code Failed to close extension
     *  context}, nowhere near the cause. Microseconds when nothing is pending. */
    @AfterEach
    void settleDeferredDerivations() {
        StoredListing.settle();
    }

    /**
     * Every format whose pointers live in the shared {@code blobs} namespace maps its coordinates to them.
     *
     * <p>It used to say <em>every format here</em>, and the premise was carried in the message rather than
     * checked - true only because every one of them served from {@code blobs}. Ivy is the first that does
     * not: it is a {@code publish/} layout like Maven and Jenesis, where a coordinate maps to a request-path
     * folder and a hold retracts by unpublishing under it. Demanding a {@code BlobLayout} of it would be demanding
     * a mapping whose only honest answer is empty, which is how an exemption list starts.
     */
    @Test
    void every_format_whose_pointers_live_in_blobs_declares_the_seam() {
        List<RepositoryFormat> formats = discovered();
        assertThat(formats).as("the runtime graph resolved the formats - the check is not vacuous")
                .isNotEmpty();
        Set<String> published = FIXTURES.stream()
                .filter(EcosystemFormatFixture::publishesUnderPublish)
                .map(EcosystemFormatFixture::format)
                .collect(Collectors.toSet());
        assertThat(formats).filteredOn(format -> !published.contains(format.name()))
                .as("a format serving from the shared blobs/ namespace must map its coordinates to their pointers "
                        + "through BlobLayout - one that does not cannot honour a retroactive hold at all")
                .isNotEmpty()
                .allSatisfy(format -> assertThat(format).isInstanceOf(BlobLayout.class));
    }

    @Test
    void no_layout_composes_a_traversal_shaped_pointer_key() throws IOException {
        WatchingStore store = WatchingStore.over(store("hostile"));
        // Several layouts discover the registry/suite segment their keys carry by LISTING their own root, so against a
        // pristine store their loop body never runs and a missing screen looks compliant. One plausible child per
        // declared root is therefore seeded first, so every layout really reaches its key composition.
        // Only the blobs-namespace layouts: a publish/ layout composes a request-path folder rather than a pointer
        // key, and its hostile-coordinate screen is the kit's COORDINATE_TRAVERSAL_REFUSED leg over paths().
        for (RepositoryFormat seeded : discovered()) {
            if (!(seeded instanceof BlobLayout blobs)) {
                continue;
            }
            for (String blobRoot : blobs.blobRoots()) {
                store.writeVersioned(blobRoot + "/registry/.probe", new byte[]{'1'}, null);
            }
        }
        store.forget();
        List<String> failures = new ArrayList<>();
        for (RepositoryFormat format : discovered()) {
            if (!(format instanceof BlobLayout layout)) {
                continue;
            }
            for (String coordinate : hostileCoordinates()) {
                for (String version : HOSTILE) {
                    screen(format.name(), layout, coordinate, version, store, failures);
                }
                screen(format.name(), layout, coordinate, "1.0.0", store, failures);
            }
            for (String version : HOSTILE) {
                screen(format.name(), layout, "contract-lib", version, store, failures);
            }
        }
        assertThat(failures)
                .as("a blobs-namespace layout composed a pointer key or served path out of a coordinate that is not "
                        + "addressable. These keys are handed to eviction, which DELETES them, and delete is not "
                        + "screened by ArtifactStore.key - only writes are - so a coordinate that is not addressable "
                        + "must map NOWHERE (an empty list, the answer BlobLayout already documents for a coordinate "
                        + "that maps to no live pointer). BlobLayout.addressable is the shared screen; it delegates to "
                        + "ArtifactLayout.addressable per '/'-separated part.%n%s",
                        String.join(System.lineSeparator(), failures))
                .isEmpty();
    }

    /**
     * The backwards direction, over every fixtured format at once: a pointer this layout wrote for a published
     * coordinate must describe back to <em>that</em> coordinate, or not be claimed at all.
     *
     * <p>{@link BlobLayout#describePointer} is what rebuilds a release's {@code published/} row when the accept path
     * lost it - the row a retroactive advisory or licence sweep enumerates by. It defaults to empty, and empty here
     * is a pass: a format that has not implemented it is simply not repaired, which is exactly as repairable as it
     * was before the seam existed. What must never happen is the other outcome. A layout that answers the
     * <em>wrong</em> coordinate writes a row against a release that was never published, and retention then ages
     * artifacts by it - so the property is stated as "answer nothing, or answer correctly", and it grows teeth for
     * each format on the day that format implements the clause rather than needing a test written with it.
     *
     * <p>It is deliberately driven off {@code blobKeys}: those are the keys this layout really wrote for the seeded
     * version, so the round trip is over the format's own output rather than over a key this test invented and might
     * have spelled in a shape the format never produces.
     */
    /**
     * A layout may not answer {@link BlobLayout#describePointer} unless this suite can round-trip it.
     *
     * <p>The round trip below is the check that keeps the clause honest, and it only reaches the layouts that have
     * a fixture here. Six discovered layouts do not - apk, helm, winget, terraform, swift, homebrew - so one of
     * them could implement the clause, get it wrong, and nothing would fire: a wrong answer writes a
     * {@code published/} row against a release that was never published, and retention ages artifacts by it.
     *
     * <p>So the rule is stated where it is decidable. Overriding the default is visible in the class file, and
     * this asserts that whoever overrides it is a format the round trip covers. Implementing the clause for an
     * unfixtured format fails here, naming it, with the remedy: give it a fixture, then add it to
     * {@link #CLAIMS}. The pair is what makes "a new format that gets this wrong trips somewhere" true rather
     * than true-for-the-thirteen.
     */
    @Test
    void no_layout_answers_describePointer_without_a_fixture_that_round_trips_it() throws Exception {
        List<String> fixtured = IN_BLOBS.stream().map(EcosystemFormatFixture::format).toList();
        Set<String> published = FIXTURES.stream()
                .filter(EcosystemFormatFixture::publishesUnderPublish)
                .map(EcosystemFormatFixture::format)
                .collect(Collectors.toSet());
        List<String> unverifiable = new ArrayList<>();
        for (RepositoryFormat format : discovered()) {
            if (published.contains(format.name())) {
                continue;   // a publish/ layout answers no pointer key; the clause is not about it
            }
            Class<?> declaring = format.getClass()
                    .getMethod("describePointer", String.class)
                    .getDeclaringClass();
            if (declaring != BlobLayout.class
                    && !fixtured.contains(format.name())
                    && !VERIFIED_ELSEWHERE.containsKey(format.name())) {
                unverifiable.add(format.name());
            }
        }
        assertThat(VERIFIED_ELSEWHERE.keySet())
                .as("an exclusion that outlives the layout it excuses stops being read - drop it")
                .allSatisfy(excused -> assertThat(discovered()).anySatisfy(format ->
                        assertThat(format.name()).isEqualTo(excused)));
        assertThat(unverifiable)
                .as("these layouts name coordinates for their own pointers, and nothing here can check that they "
                        + "name the RIGHT ones - the row they rebuild is what a retroactive sweep enumerates and "
                        + "what retention ages by. Give each one an EcosystemFormatFixture so the round trip can "
                        + "drive it over really published content, then add it to CLAIMS")
                .isEmpty();
    }

    @Test
    void a_pointer_a_layout_wrote_describes_back_to_the_coordinate_it_wrote_it_for() throws IOException {
        List<String> claimed = new ArrayList<>();
        for (EcosystemFormatFixture fixture : IN_BLOBS) {
            ArtifactStore store = store("describepointer-" + fixture.format());
            EcosystemFormatFixture.Seeded seeded = fixture.seed(store);
            BlobLayout layout = fixture.layout();

            for (String key : layout.blobKeys(seeded.coordinate(), seeded.version(), store)) {
                Optional<ArtifactDescriptor> named = layout.describePointer(key);
                if (named.isEmpty()) {
                    continue;               // not claimed: no repair for this key, which is a legitimate answer
                }
                claimed.add(fixture.format());
                assertThat(named.get().coordinate())
                        .as("%s: describePointer named the wrong coordinate for %s - the row it rebuilds is what a "
                                + "retroactive sweep enumerates and what retention ages by", fixture.format(), key)
                        .isEqualTo(seeded.coordinate());
                assertThat(named.get().version())
                        .as("%s: describePointer named the wrong version for %s", fixture.format(), key)
                        .isEqualTo(seeded.version());
                assertThat(named.get().ecosystem())
                        .as("%s: describePointer must name the ecosystem the row is keyed by", fixture.format())
                        .isEqualTo(layout.ecosystem());
            }
        }
        // A SUPERSET, not an equality: a format that implements the clause must keep claiming its own keys, and a
        // new one may join without touching this line. Stated because the loop above is vacuous per format - a
        // layout that quietly went back to answering empty would still leave this test green on its neighbours,
        // and "the repair silently stopped covering npm" is precisely the regression nothing else here would show.
        assertThat(claimed).as("a layout that used to name its own pointer keys has stopped, so the releases it "
                        + "repairs are silently unrepairable again")
                .containsAll(CLAIMS);
    }

    @Test
    void the_folders_above_a_pointer_are_not_themselves_claimed_as_pointers() throws IOException {
        // The other half of the clause, and the half a round trip cannot see. describePointer is asked about EVERY
        // key a repair meets under this layout's blob roots - the indexes, the checksums, the folders - and the
        // contract says it must answer empty for all of them: a descriptor naming no coordinate is an absence
        // dressed as a claim, and a row written from one is a row retention then ages by.
        //
        // The probes are derived rather than invented: every proper prefix of a real pointer key, down to the blob
        // root. Those are exactly the keys that exist beside a pointer and are not one, so a layout that answers
        // about them is over-claiming in the direction that actually happens.
        for (EcosystemFormatFixture fixture : IN_BLOBS) {
            ArtifactStore store = store("pointerparent-" + fixture.format());
            EcosystemFormatFixture.Seeded seeded = fixture.seed(store);
            BlobLayout layout = fixture.layout();

            for (String key : layout.blobKeys(seeded.coordinate(), seeded.version(), store)) {
                for (int slash = key.indexOf('/'); slash > 0; slash = key.indexOf('/', slash + 1)) {
                    String parent = key.substring(0, slash);
                    assertThat(layout.describePointer(parent))
                            .as("%s: %s is a folder above the pointer %s, not a per-version pointer - a repair asks "
                                    + "about it and must be told nothing rather than a coordinate-less descriptor",
                                    fixture.format(), parent, key)
                            .isEmpty();
                }
            }
        }
    }

    @Test
    void a_published_coordinate_round_trips_through_its_layout() throws IOException {
        for (EcosystemFormatFixture fixture : IN_BLOBS) {
            ArtifactStore store = store("roundtrip-" + fixture.format());
            EcosystemFormatFixture.Seeded seeded = fixture.seed(store);
            BlobLayout layout = fixture.layout();

            assertThat(layout.blobKeys(seeded.coordinate(), seeded.version(), store))
                    .as("%s: the published coordinate resolves the pointer keys an eviction would delete - an empty "
                            + "answer makes every retroactive hold a silent no-op", fixture.format())
                    .isNotEmpty();
            assertThat(layout.blobHashes(seeded.coordinate(), seeded.version(), store))
                    .as("%s: the published coordinate resolves the content hashes a withhold marks", fixture.format())
                    .isNotEmpty();
            assertThat(layout.servedPaths(seeded.coordinate(), seeded.version(), store))
                    .as("%s: the published coordinate maps back to the request path it serves at", fixture.format())
                    .contains(seeded.servedPath());

            // ... and the publish/-namespace seam the fixture EXCLUDES the kit's coordinate leg over is really inert,
            // over the very store the version was just published into. Every fixture here excludes
            // COORDINATE_TRAVERSAL_REFUSED on the ground that its ArtifactLayout answers nothing; seven of the formats
            // do implement that interface, so the ground is checked rather than restated. A format that later wired
            // paths() for real fails here - the signal to delete its exclusion and let the kit's own leg run, instead
            // of an exclusion quietly outliving its reason while a live coordinate seam goes untested.
            if (fixture.serving() instanceof ArtifactLayout publishLayout) {
                assertThat(publishLayout.paths(seeded.coordinate(), seeded.version(), store))
                        .as("%s: the ArtifactLayout coordinate seam this fixture excludes answers nothing even for a "
                                + "coordinate that IS published - its pointers live in the blobs namespace",
                                fixture.format())
                        .isEmpty();
                assertThat(publishLayout.paths(seeded.coordinate(), seeded.version()))
                        .as("%s: ... and so does its store-free overload", fixture.format())
                        .isEmpty();
            }

            Optional<ArtifactDescriptor> described = layout.describe(seeded.servedPath());
            assertThat(described).as("%s: describe maps the served path back to a coordinate", fixture.format())
                    .isPresent();
            assertThat(described.get().coordinate())
                    .as("%s: describe and servedPaths are inverses - a layout whose two directions disagree marks the "
                            + "wrong bytes when a hold lands", fixture.format())
                    .isEqualTo(seeded.coordinate());
            assertThat(described.get().version()).as("%s: ... and on the version too", fixture.format())
                    .isEqualTo(seeded.version());
        }
    }

    @Test
    void the_shared_screen_refuses_exactly_the_unaddressable_shapes() {
        // The screen itself, so the sweep above cannot pass because the predicate answers false to everything: a real
        // coordinate must survive it, including the multi-segment ones this namespace legitimately carries.
        assertThat(BlobLayout.addressable("contract-lib", "1.0.0")).isTrue();
        assertThat(BlobLayout.addressable("@scope/name", "1.0.0-rc.1")).as("an npm scoped package").isTrue();
        assertThat(BlobLayout.addressable("example.com/module/v2", "v1.0.0+meta")).as("a Go module path").isTrue();
        assertThat(BlobLayout.addressable("contract/contract-lib", "1.0.0-1.x86_64")).as("an RPM NEVRA").isTrue();
        assertThat(BlobLayout.addressable("oci/image", "sha256:" + "a".repeat(64))).as("an OCI digest").isTrue();

        for (String hostile : HOSTILE) {
            assertThat(BlobLayout.addressable(hostile, "1.0.0"))
                    .as("the coordinate '%s' is not addressable", hostile).isFalse();
            assertThat(BlobLayout.addressable("contract-lib", hostile))
                    .as("the version '%s' is not addressable", hostile).isFalse();
            assertThat(BlobLayout.addressable("scope/" + hostile, "1.0.0"))
                    .as("a multi-segment coordinate is judged part by part, so '%s' as its last part is not "
                            + "addressable either", hostile).isFalse();
        }
        assertThat(BlobLayout.addressable(null, "1.0.0")).isFalse();
        assertThat(BlobLayout.addressable("contract-lib", null)).isFalse();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private void screen(String format, BlobLayout layout, String coordinate, String version, WatchingStore store,
                        List<String> failures) {
        List<String> keys;
        List<String> paths;
        store.forget();
        try {
            keys = layout.blobKeys(coordinate, version, store);
            paths = layout.servedPaths(coordinate, version, store);
        } catch (IOException | RuntimeException refused) {
            // A refusal by exception is not the documented answer - BlobLayout's absence sentinel is an empty list -
            // and it would surface as a 500 on the sweep that called it, so it is a failure, not a pass.
            failures.add("  - " + format + ": (coordinate='" + coordinate + "', version='" + version + "') threw "
                    + refused + " where the contract's absence sentinel is an empty list");
            return;
        }
        for (String key : keys) {
            if (!ArtifactStore.traversalFree(key) || key.indexOf('\\') >= 0) {
                failures.add("  - " + format + ": blobKeys(coordinate='" + coordinate + "', version='" + version
                        + "') composed the traversal-shaped pointer key '" + key + "'");
            } else if (layout.blobRoots().stream().noneMatch(
                    blobRoot -> key.equals(blobRoot) || key.startsWith(blobRoot + "/"))) {
                failures.add("  - " + format + ": blobKeys(coordinate='" + coordinate + "', version='" + version
                        + "') composed '" + key + "', outside its own declared blob roots " + layout.blobRoots());
            }
        }
        for (String path : paths) {
            if (!ArtifactStore.traversalFree(path) || path.indexOf('\\') >= 0) {
                failures.add("  - " + format + ": servedPaths(coordinate='" + coordinate + "', version='" + version
                        + "') composed the traversal-shaped request path '" + path + "'");
            }
        }
        // ... and, decisively, WHERE it looked. A layout that pages a traversal-shaped prefix answers an honest empty
        // list against an empty temporary directory and would answer keys an eviction deletes against a populated one -
        // and on an object-store backend that prefix is a literal key namespace rather than a normalising path. So the
        // trace is the assertion, not the return value.
        for (String probed : store.touched()) {
            if (!ArtifactStore.traversalFree(probed) || probed.indexOf('\\') >= 0) {
                failures.add("  - " + format + ": (coordinate='" + coordinate + "', version='" + version
                        + "') probed the traversal-shaped store key '" + probed + "'");
            } else if (!probed.isEmpty() && !probed.equals(SHARED_BLOBS)
                    && !probed.startsWith(SHARED_BLOBS + "/")
                    && layout.blobRoots().stream().noneMatch(
                            blobRoot -> probed.equals(blobRoot) || probed.startsWith(blobRoot + "/"))) {
                failures.add("  - " + format + ": (coordinate='" + coordinate + "', version='" + version
                        + "') probed '" + probed + "', outside its own declared blob roots " + layout.blobRoots());
            }
        }
    }

    /** The shared content-addressed namespace every format resolves a pointer through - not one format's root, and
     *  legitimately reachable from any of them. */
    private static final String SHARED_BLOBS = "blobs";

    /** The hostile parts offered as a whole coordinate and spliced into a multi-segment one, so a layout whose
     *  coordinate legitimately carries a {@code /} is probed in the part that becomes a key segment. */
    private static List<String> hostileCoordinates() {
        List<String> coordinates = new ArrayList<>(HOSTILE);
        for (String hostile : HOSTILE) {
            coordinates.add("scope/" + hostile);
            coordinates.add(hostile + "/name");
        }
        return coordinates;
    }

    private static List<RepositoryFormat> discovered() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
