package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.ServableNames.Policy;
import build.jenesis.repository.store.ServableNames.State;
import build.jenesis.repository.store.Withheld;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The servable-name enumeration seam: {@link ServableNames} must discriminate the tri-state
 * (servable / withheld / blob-gone / unpublished) both {@code Publication.located} conflates, across both the
 * {@code publish/}-namespace face ({@link ServableNames#state}) and the {@code blobs/}-namespace marker face
 * ({@link ServableNames#keyState}); it must answer the {@link Policy#HIDE_WITHHELD} membership question with
 * <em>zero</em> blob-stat I/O (so a fake-hash member keeps listing and a hot listing pays no download-shaped cost);
 * it must contain a hostile / unresolvable name (fail-closed skip, never a 500); its version-folder face must hide a
 * held version through the {@code /quarantine} review-pointer convention while keeping a fake-hash/no-blob or empty
 * folder; and its blobs-namespace face must agree bit-for-bit with the hand-derived {@code Blobs.read} discrimination.
 * The seam is what serve and enumeration now share, so {@code located} empty must be exactly {@code state != SERVABLE}.
 */
class ServableNamesTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_GONE = "c".repeat(64);

    // ---- publish/-namespace tri-state (state) ------------------------------------------------------------------

    @Test
    void state_discriminates_the_publish_namespace_tri_state_the_serve_path_conflates() throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("publish/maven/g/a/1/gone.jar", HASH_GONE);          // pointer, no blob
        store.pointer("publish/maven/g/a/1/held.jar", HASH_B);
        store.blob(HASH_B);

        Withholding chain = new Withholding("/maven/g/a/1/held.jar");
        ServableNames names = new ServableNames(store, new Publication(store, List.of(chain)));

        assertThat(names.state("/maven/g/a/1/served.jar")).isEqualTo(State.SERVABLE);
        assertThat(names.state("/maven/g/a/1/held.jar")).isEqualTo(State.WITHHELD);
        assertThat(names.state("/maven/g/a/1/gone.jar")).isEqualTo(State.BLOB_GONE);
        assertThat(names.state("/maven/g/a/1/never.jar")).isEqualTo(State.UNPUBLISHED);
    }

    @Test
    void a_checksum_sidecar_is_held_by_the_hold_on_the_artifact_it_describes() throws IOException {
        // Found by the ecosystem matrix's maven ADMIN_VIEW row. A gate quarantines the artifact it can screen - the
        // jar and the POM - and the checksums the publisher uploaded beside them are unclaimed content no inspector
        // has an opinion about, so they were accepted, pointed at, and served while their subject answered 404. What
        // leaked is not a hint: jenesisdemo-2.0.jar.sha1 IS the digest of the withheld bytes, and the folder listing
        // that carries it publishes the held version's existence.
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/2/held.jar", HASH_B);
        store.blob(HASH_B);
        store.pointer("publish/maven/g/a/2/held.jar.sha1", HASH_A);
        store.blob(HASH_A);

        ServableNames names = new ServableNames(store,
                new Publication(store, List.of(new Withholding("/maven/g/a/2/held.jar"))));

        assertThat(names.state("/maven/g/a/2/held.jar")).isEqualTo(State.WITHHELD);
        assertThat(names.state("/maven/g/a/2/held.jar.sha1"))
                .as("the sidecar of a held artifact is withheld, not servable")
                .isEqualTo(State.WITHHELD);
        assertThat(names.disclosable("/maven/g/a/2/held.jar.sha1", Policy.HIDE_WITHHELD))
                .as("and it is hidden from enumeration by the same rule, so a listing cannot disagree with a GET")
                .isFalse();
    }

    @Test
    void a_sidecar_is_held_by_a_marker_on_its_subject_too_not_only_by_the_chain() throws IOException {
        // The retroactive half: a KEV sweep marks the CONTENT of a published jar. The sidecar's own bytes carry no
        // marker - the marker is content-addressed and the checksum is different content - so without the subject
        // read the sweep would hold the jar and leave its digest served.
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/2/swept.jar", HASH_B);
        store.blob(HASH_B);
        Withheld.mark(store, HASH_B);
        store.pointer("publish/maven/g/a/2/swept.jar.md5", HASH_A);
        store.blob(HASH_A);

        ServableNames names = new ServableNames(store);

        assertThat(names.state("/maven/g/a/2/swept.jar")).isEqualTo(State.WITHHELD);
        assertThat(names.state("/maven/g/a/2/swept.jar.md5")).isEqualTo(State.WITHHELD);
        assertThat(names.disclosable("/maven/g/a/2/swept.jar.md5", Policy.HIDE_WITHHELD)).isFalse();
    }

    @Test
    void a_sidecar_whose_subject_is_servable_serves_and_one_with_no_subject_is_untouched() throws IOException {
        // The falsification leg: without it every assertion above would also hold for a seam that simply refused
        // every path ending in a checksum suffix.
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/fine.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("publish/maven/g/a/1/fine.jar.sha1", HASH_B);
        store.blob(HASH_B);
        // A sidecar name whose subject was never published - a detached checksum, which is a servable file in its
        // own right and must not be hidden by a subject that does not exist.
        store.pointer("publish/raw/notes.txt.md5", HASH_B);

        ServableNames names = new ServableNames(store);

        assertThat(names.state("/maven/g/a/1/fine.jar.sha1")).isEqualTo(State.SERVABLE);
        assertThat(names.disclosable("/maven/g/a/1/fine.jar.sha1", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(names.disclosable("/raw/notes.txt.md5", Policy.HIDE_WITHHELD)).isTrue();
        // A bare suffix is a filename, not a sidecar of the empty path.
        assertThat(names.disclosable("/raw/.sha1", Policy.HIDE_WITHHELD)).isTrue();
    }

    @Test
    void located_is_empty_exactly_when_state_is_not_servable_so_serve_and_enumeration_share_one_truth()
            throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("publish/maven/g/a/1/gone.jar", HASH_GONE);
        store.pointer("publish/maven/g/a/1/held.jar", HASH_B);
        store.blob(HASH_B);

        Withholding chain = new Withholding("/maven/g/a/1/held.jar");
        Publication publication = new Publication(store, List.of(chain));
        ServableNames names = new ServableNames(store, publication);

        for (String path : List.of("/maven/g/a/1/served.jar", "/maven/g/a/1/gone.jar",
                "/maven/g/a/1/held.jar", "/maven/g/a/1/never.jar")) {
            boolean servable = names.state(path) == State.SERVABLE;
            assertThat(publication.located(path).isPresent())
                    .as("located(%s) present must equal state==SERVABLE", path)
                    .isEqualTo(servable);
        }
        assertThat(publication.located("/maven/g/a/1/served.jar")).contains("blobs/" + HASH_A);
    }

    @Test
    void a_marker_on_the_hash_a_publish_pointer_names_withholds_it_with_no_quarantine_pointer_anywhere()
            throws IOException {
        //. The publish/ face used to consult only the interceptor chain, so the content half of a hold - the
        // withheld/<hash> marker, keyed by content precisely so ONE hold retracts the bytes wherever served - reached
        // only the blobs/ face. Any publish/-namespace alias the hold writer's path enumeration did not name therefore
        // kept serving held bytes; the Maven cross-publish's /module/<name>/<name>.jar "latest" view is the driven
        // case, since it belongs to no single version and no ArtifactLayout.paths overload reports it.
        MapStore store = new MapStore();
        store.pointer("publish/module/test.widget/test.widget.jar", HASH_A);   // no /quarantine pointer at this alias
        store.blob(HASH_A);
        Withheld.mark(store, HASH_A);
        ServableNames names = new ServableNames(store);   // and no interceptor: the marker is the whole hold here

        assertThat(names.state("/module/test.widget/test.widget.jar")).isEqualTo(State.WITHHELD);
        assertThat(names.disclosable("/module/test.widget/test.widget.jar", Policy.HIDE_WITHHELD))
                .as("the listing must agree with the download").isFalse();
        assertThat(names.disclosable("/module/test.widget/test.widget.jar", Policy.HIDE_WITHHELD_AND_GONE)).isFalse();
        assertThat(new Publication(store).located("/module/test.widget/test.widget.jar")).isEmpty();
    }

    @Test
    void a_withheld_publish_path_whose_blob_was_reclaimed_reads_withheld_not_blob_gone() throws IOException {
        // The marker probe sits BEFORE the blob stat on this face for the same reason it does on the blobs face: a
        // path that is both held and whose blob a collector has since reclaimed must not read as a merely torn
        // pointer, or a reconcile consumer repairs it back into a served one.
        MapStore store = new MapStore();
        store.pointer("publish/module/test.widget/test.widget.jar", HASH_GONE);   // marker, and no blob
        Withheld.mark(store, HASH_GONE);
        ServableNames names = new ServableNames(store);

        assertThat(names.state("/module/test.widget/test.widget.jar")).isEqualTo(State.WITHHELD);
    }

    @Test
    void an_unmarked_publish_path_is_untouched_by_the_marker_probe() throws IOException {
        // The control: the probe can only ever hide more. A pointer naming a hash no marker covers - and a membership
        // row whose recorded hash names no stored blob at all - answer exactly as they did before.
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH_A);
        store.blob(HASH_A);
        Withheld.mark(store, HASH_B);                                      // a hold on OTHER bytes
        store.pointer("publish/maven/g/a/1/fake.jar", HASH_GONE);          // recorded with a hash no blob backs
        ServableNames names = new ServableNames(store);

        assertThat(names.state("/maven/g/a/1/a-1.jar")).isEqualTo(State.SERVABLE);
        assertThat(names.disclosable("/maven/g/a/1/a-1.jar", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(names.disclosable("/maven/g/a/1/fake.jar", Policy.HIDE_WITHHELD))
                .as("a fake-hash membership row still lists - no marker is keyed by it").isTrue();
        assertThat(names.disclosable("/maven/g/a/1/never.jar", Policy.HIDE_WITHHELD))
                .as("and an unpublished name has nothing held to hide").isTrue();
    }

    // ---- blobs/-namespace tri-state (keyState) and Blobs.read parity -------------------------------------------

    @Test
    void keyState_matches_the_hand_written_blobs_read_shaped_discrimination() throws IOException {
        MapStore store = new MapStore();
        store.pointer("npm/pkg/tarballs/served.tgz", HASH_A);
        store.blob(HASH_A);
        store.pointer("npm/pkg/tarballs/held.tgz", HASH_B);
        store.blob(HASH_B);
        Withheld.mark(store, HASH_B);                                       // the withheld/<hash> marker
        store.pointer("npm/pkg/tarballs/gone.tgz", HASH_GONE);             // pointer, no blob, no marker

        ServableNames names = new ServableNames(store);

        // A hand-written Blobs.read-shaped expectation: pointer absent -> UNPUBLISHED; marker -> WITHHELD;
        // blob missing -> BLOB_GONE; else SERVABLE. keyState must agree with it, key for key.
        assertThat(names.keyState("npm/pkg/tarballs/served.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/served.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/held.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/held.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/gone.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/gone.tgz"));
        assertThat(names.keyState("npm/pkg/tarballs/absent.tgz")).isEqualTo(expected(store, "npm/pkg/tarballs/absent.tgz"));

        // And the concrete states, so the parity function above is not the only witness.
        assertThat(names.keyState("npm/pkg/tarballs/served.tgz")).isEqualTo(State.SERVABLE);
        assertThat(names.keyState("npm/pkg/tarballs/held.tgz")).isEqualTo(State.WITHHELD);
        assertThat(names.keyState("npm/pkg/tarballs/gone.tgz")).isEqualTo(State.BLOB_GONE);
        assertThat(names.keyState("npm/pkg/tarballs/absent.tgz")).isEqualTo(State.UNPUBLISHED);
    }

    /** The {@code Blobs.read}/{@code Blobs.size} discrimination, hand-rolled independently of the seam, so
     *  {@link ServableNames#keyState} is checked against a second implementation rather than against itself. */
    private static State expected(ArtifactStore store, String pointerKey) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
        if (pointer.isEmpty()) {
            return State.UNPUBLISHED;
        }
        String hash = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        if (store.readVersioned("withheld/" + hash).isPresent()) {
            return State.WITHHELD;
        }
        return store.exists("blobs/" + hash) ? State.SERVABLE : State.BLOB_GONE;
    }

    @Test
    void withheldHash_reads_the_marker_convention() throws IOException {
        MapStore store = new MapStore();
        store.blob(HASH_A);
        store.blob(HASH_B);
        Withheld.mark(store, HASH_B);
        ServableNames names = new ServableNames(store);

        assertThat(names.withheldHash(HASH_A)).as("no marker -> servable").isFalse();
        assertThat(names.withheldHash(HASH_B)).as("marker present -> withheld").isTrue();
    }

    // ---- HIDE_WITHHELD does zero blob-stat I/O -----------------------------------------------------------------

    @Test
    void hide_withheld_membership_policy_stats_no_blob_while_serve_parity_does() throws IOException {
        SpyStore store = new SpyStore();
        store.pointer("publish/maven/g/a/1/served.jar", HASH_A);
        store.blob(HASH_A);
        store.pointer("npm/pkg/tarballs/served.tgz", HASH_A);

        Withholding chain = new Withholding("/maven/g/a/1/withheld.jar");
        ServableNames names = new ServableNames(store, new Publication(store, List.of(chain)));

        // publish-namespace membership: chain probe plus the pointer/marker read (made this face read the
        // content half of a hold too, exactly as the blobs-namespace face below always has) - and still no blob stat.
        store.blobStats = 0;
        assertThat(names.disclosable("/maven/g/a/1/served.jar", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(names.disclosable("/maven/g/a/1/withheld.jar", Policy.HIDE_WITHHELD)).isFalse();
        assertThat(store.blobStats).as("HIDE_WITHHELD on a publish path must stat no blobs/ object").isZero();

        // blobs-namespace membership: pointer + marker read, still no blob stat.
        store.blobStats = 0;
        assertThat(names.disclosableKey("npm/pkg/tarballs/served.tgz", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(store.blobStats).as("HIDE_WITHHELD on a blobs key must stat no blobs/ object").isZero();

        // The spy actually counts: serve-parity DOES stat the blob, proving the zero above is load-bearing.
        store.blobStats = 0;
        assertThat(names.disclosable("/maven/g/a/1/served.jar", Policy.HIDE_WITHHELD_AND_GONE)).isTrue();
        assertThat(store.blobStats).as("HIDE_WITHHELD_AND_GONE is serve-parity and stats the blob").isPositive();
    }

    // ---- hostile-name containment (fail-closed skip, never a 500) ----------------------------------------------

    @Test
    void a_hostile_unresolvable_name_is_skipped_not_thrown() {
        HostileStore store = new HostileStore();
        ServableNames names = new ServableNames(store);

        assertThatCode(() -> {
            assertThat(names.state("/\uD800bad")).isEqualTo(State.WITHHELD);
            assertThat(names.disclosable("/\uD800bad", Policy.HIDE_WITHHELD_AND_GONE)).isFalse();
            assertThat(names.keyState("npm/\uD800bad")).isEqualTo(State.WITHHELD);
            assertThat(names.disclosableKey("npm/\uD800bad", Policy.HIDE_WITHHELD_AND_GONE)).isFalse();
            assertThat(names.disclosableVersionFolder("/maven/\uD800bad")).isFalse();
            assertThat(names.withheldHash("\uD800bad")).isTrue();
        }).as("a name a store backend cannot resolve must skip (undisclosed), never throw out of the seam")
                .doesNotThrowAnyException();
    }

    // The listing-level containment ("one hostile name does not 500 the page", "the quarantine root child is
    // suppressed, containers forward unconditionally") is asserted against the screened ENUMERATION that owns those
    // rules now - build.jenesis.repository.walk.ScreenedNames, exercised by ScreenedNamesTest in test/walk. This suite
    // keeps the per-name seam behaviour those rules compose.

    // ---- the pointer dialect: withheld/<hash> is keyed by the bare hex ------------------------------------------

    @Test
    void a_digest_qualified_pointer_body_is_screened_against_the_bare_hex_marker() throws IOException {
        // OCI writes its tag pointers in the Distribution display form (sha256:<hex>) while the withhold marker is -
        // and must stay - keyed by the bare content hash. Probing withheld/sha256:<hex> matched nothing, so the seam's
        // blobs-namespace face failed OPEN for exactly the dialect a held image is disclosed through. The seam now
        // normalises a pointer body to its content hash, so both dialects screen identically.
        MapStore store = new MapStore();
        store.pointer("oci/library/app/tags/1.0", "sha256:" + HASH_A);
        store.pointer("npm/pkg/tarballs/pkg-1.0.tgz", HASH_A);
        store.blob(HASH_A);
        store.objects.put(Withheld.ROOT + HASH_A, new byte[0]);          // the hold, keyed by the bare hex
        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableKey("oci/library/app/tags/1.0", Policy.HIDE_WITHHELD))
                .as("a sha256:-qualified pointer body must resolve to the bare hex the marker is keyed by").isFalse();
        assertThat(names.keyState("oci/library/app/tags/1.0")).isEqualTo(State.WITHHELD);
        assertThat(names.disclosableKey("npm/pkg/tarballs/pkg-1.0.tgz", Policy.HIDE_WITHHELD))
                .as("the bare-hex dialect is unchanged").isFalse();
    }

    @Test
    void a_digest_qualified_pointer_to_an_unheld_blob_still_serves_and_states_its_blob() throws IOException {
        // The control: normalising must only ever hide MORE. With no marker the qualified pointer discloses exactly as
        // the bare one does, and serve-parity resolves the blob it names rather than reporting it gone.
        MapStore store = new MapStore();
        store.pointer("oci/library/app/tags/2.0", "sha256:" + HASH_B);
        store.blob(HASH_B);
        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableKey("oci/library/app/tags/2.0", Policy.HIDE_WITHHELD)).isTrue();
        assertThat(names.keyState("oci/library/app/tags/2.0"))
                .as("the qualified body resolves to blobs/<hex>, so serve-parity is SERVABLE - not BLOB_GONE")
                .isEqualTo(State.SERVABLE);
    }

    // ---- disclosableVersionFolder ------------------------------------------------------------------------------

    @Test
    void a_version_folder_held_through_the_quarantine_pointer_convention_is_hidden() throws IOException {
        MapStore store = new MapStore();
        // A held version: the gate diverted its served path to publish/quarantine<path>.
        store.pointer("publish/quarantine/maven/g/a/1/a-1.jar", HASH_A);
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH_A);
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableVersionFolder("/maven/g/a/1"))
                .as("a version with a /quarantine review pointer must not be listed").isFalse();
    }

    /**
     * A byte-identical sibling coordinate leaves the listing too - the case the review-pointer leg cannot see.
     *
     * <p>The {@code /quarantine} leg above screens every hold a writer places today, because each retroactive sweep
     * links a review pointer beside the marker for every served path that carries a {@code publish/} pointer. A
     * sibling coordinate publishing the <em>same bytes</em> gets neither: no review pointer of its own, no
     * interceptor withhold. But the marker is keyed by content, deliberately, so its download already 404s - and its
     * version name kept listing in {@code maven-metadata.xml}. That is the listing-versus-download disagreement
     * this class exists to prevent, and the one face of it that had not been closed.
     */
    @Test
    void a_byte_identical_sibling_of_a_held_version_stops_listing_too() throws IOException {
        MapStore store = new MapStore();
        // g:a:1.0 is held by a content marker; the sweep linked its review pointer.
        store.pointer("publish/maven/g/a/1/a-1.jar", HASH_A);
        store.pointer("publish/quarantine/maven/g/a/1/a-1.jar", HASH_A);
        // g:b:1.0 published the same bytes: same hash, no review pointer, no chain withhold.
        store.pointer("publish/maven/g/b/1/b-1.jar", HASH_A);
        store.blob(HASH_A);
        Withheld.mark(store, HASH_A);

        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableVersionFolder("/maven/g/a/1"))
                .as("the held version itself, caught by the review-pointer leg as it always was").isFalse();
        assertThat(names.disclosableVersionFolder("/maven/g/b/1"))
                .as("and its byte-identical sibling, whose download already 404s on the content marker - listing it "
                        + "is the disagreement this class exists to prevent")
                .isFalse();
        assertThat(names.state("/maven/g/b/1/b-1.jar"))
                .as("the sibling's own serve read already agreed; only the folder face disagreed")
                .isEqualTo(State.WITHHELD);
    }

    /** A version whose bytes carry no marker still lists - the fix must not hide everything that shares a folder
     *  shape with something held. */
    @Test
    void an_unmarked_version_still_lists() throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/c/1/c-1.jar", HASH_B);
        store.blob(HASH_B);

        assertThat(new ServableNames(store).disclosableVersionFolder("/maven/g/c/1"))
                .as("nothing holds it, so it lists").isTrue();
    }

    @Test
    void a_fake_hash_no_blob_version_folder_keeps_listing_because_no_blob_is_ever_stated() throws IOException {
        MapStore store = new MapStore();
        // A version linked to a hash whose blob was never stored (the maven fake-hash test shape): no quarantine.
        store.pointer("publish/maven/g/a/2/a-2.jar", HASH_GONE);
        ServableNames names = new ServableNames(store);        // empty chain

        assertThat(names.disclosableVersionFolder("/maven/g/a/2"))
                .as("a fake-hash / no-blob version must keep listing (membership, not serve-parity)").isTrue();
    }

    @Test
    void an_empty_chain_version_folder_lists_and_a_chain_held_leaf_hides_it() throws IOException {
        MapStore store = new MapStore();
        store.pointer("publish/maven/g/a/3/a-3.jar", HASH_A);
        store.blob(HASH_A);

        ServableNames open = new ServableNames(store);         // empty chain, no quarantine
        assertThat(open.disclosableVersionFolder("/maven/g/a/3")).as("empty chain -> folder lists").isTrue();

        Withholding chain = new Withholding("/maven/g/a/3/a-3.jar");
        ServableNames held = new ServableNames(store, new Publication(store, List.of(chain)));
        assertThat(held.disclosableVersionFolder("/maven/g/a/3"))
                .as("the chain withholding a leaf hides the whole version folder").isFalse();
    }

    @Test
    void a_chain_withheld_leaf_beyond_the_old_32_cap_is_now_screened_by_the_raised_cap() throws IOException {
        // FIX 3: the chain leg formerly probed only the first 32 leaves and failed OPEN past that - a folder with a
        // withheld leaf sitting beyond leaf 32 leaked its version name. The cap is raised well above any legitimate
        // version folder, so this 40-leaf folder is probed in full and the held leaf at sorted index 35 is now found.
        MapStore store = new MapStore();
        for (int leaf = 0; leaf < 40; leaf++) {
            store.pointer(String.format("publish/maven/g/a/1/leaf-%03d.jar", leaf), HASH_A);
        }
        store.blob(HASH_A);
        Withholding chain = new Withholding("/maven/g/a/1/leaf-035.jar");   // sorts at index 35 - past the old 32 cap
        ServableNames names = new ServableNames(store, new Publication(store, List.of(chain)));

        assertThat(names.disclosableVersionFolder("/maven/g/a/1"))
                .as("a withheld leaf beyond the old 32-probe prefix is now caught (was fail-open leak)").isFalse();
    }

    @Test
    void a_normal_folder_wider_than_the_old_cap_but_within_the_raised_cap_still_discloses() throws IOException {
        // The control: a folder wider than the old 32 cap but within the raised bound, with NO withheld leaf, is
        // probed in full and still discloses - the raise does not wrongly hide a legitimately large version folder.
        MapStore store = new MapStore();
        for (int leaf = 0; leaf < 40; leaf++) {
            store.pointer(String.format("publish/maven/g/a/2/leaf-%03d.jar", leaf), HASH_A);
        }
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);   // empty chain, no quarantine

        assertThat(names.disclosableVersionFolder("/maven/g/a/2"))
                .as("a normal >old-cap folder within the raised bound still discloses").isTrue();
    }

    @Test
    void a_folder_wider_than_the_raised_cap_fails_closed() throws IOException {
        // FIX 3: past the raised probe bound the folder fails CLOSED rather than fail-open - a pathologically wide
        // folder cannot be probed exhaustively without unbounding the chain fan-out, so it is screened even with no
        // withheld leaf and the free (empty) chain. (PROBE_CAP is 512; 700 leaves exceeds it.)
        MapStore store = new MapStore();
        for (int leaf = 0; leaf < 700; leaf++) {
            store.pointer(String.format("publish/maven/g/a/9/leaf-%04d.jar", leaf), HASH_A);
        }
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);   // empty chain, no quarantine, no withheld leaf

        assertThat(names.disclosableVersionFolder("/maven/g/a/9"))
                .as("a folder wider than the raised probe bound is screened (fail-closed past the cap)").isFalse();
    }


    @Test
    void a_folder_wider_than_the_cap_is_rejected_without_being_materialised() throws IOException {
        // The sibling above proves the VERDICT past the cap; this proves the COST of reaching it, which is a separate
        // claim and was the one that did not hold. The bound read the folder whole and counted it afterwards, so the
        // pathologically wide folder the cap exists for was the one folder guaranteed to be materialised in full - a
        // million-leaf folder became a million strings and was then rejected. Against a store that pages natively
        // (every shipped backend does; MapStore alone inherits the materialising fallback) the leaf names are now
        // taken one past the cap, so the read is bounded whatever the folder holds.
        PagingStore store = new PagingStore();
        for (int leaf = 0; leaf < 5000; leaf++) {
            store.pointer(String.format("publish/maven/g/a/9/leaf-%04d.jar", leaf), HASH_A);
        }
        store.blob(HASH_A);
        ServableNames names = new ServableNames(store);

        assertThat(names.disclosableVersionFolder("/maven/g/a/9"))
                .as("still fail-closed past the cap - the verdict is unchanged").isFalse();
        assertThat(store.listedWhole)
                .as("and the wide folder was never listed whole to decide it").doesNotContain("publish/maven/g/a/9");
        assertThat(store.namesServed)
                .as("no more names read than the cap needs to know it was exceeded")
                .isLessThanOrEqualTo(513);
    }

    // ---- doubles -----------------------------------------------------------------------------------------------

    /** A {@link MapStore} that pages natively and records what was asked of it - the shape every shipped backend has
     *  and the plain map double does not. It matters here: {@code MapStore} inherits {@code page}'s materialising
     *  fallback, so against it a bounded read and an unbounded one are indistinguishable, and a test written on it
     *  would pass with the defect present. */
    private static final class PagingStore extends MapStore {

        final List<String> listedWhole = new ArrayList<>();

        int namesServed;

        @Override
        public List<String> list(String prefix) {
            listedWhole.add(prefix);
            List<String> all = super.list(prefix);
            namesServed += all.size();
            return all;
        }

        @Override
        public void page(String prefix, String startAfter, int limit, java.util.function.Consumer<String> consumer) {
            int served = 0;
            for (String name : super.list(prefix)) {
                if (served == limit) {
                    return;
                }
                if (startAfter == null || startAfter.isEmpty() || name.compareTo(startAfter) > 0) {
                    namesServed++;
                    served++;
                    consumer.accept(name);
                }
            }
        }
    }

    /** An interceptor that withholds exactly the request paths it is constructed with - the free chain is empty, so
     *  this is how a test drives the WITHHELD leg without a downstream compliance gate on the module path. */
    private static final class Withholding implements PublishInterceptor {

        private final Set<String> held;

        private Withholding(String... paths) {
            this.held = Set.of(paths);
        }

        @Override
        public boolean withheld(String path, ArtifactStore store) {
            return held.contains(path);
        }
    }

    /** An in-memory {@link ArtifactStore} over a flat key map: pointers carry their hash as content, blobs and
     *  {@code withheld/} markers are presence-only, and {@link #list} derives immediate children from the key set. */
    private static class MapStore implements ArtifactStore {

        final Map<String, byte[]> objects = new LinkedHashMap<>();

        void pointer(String key, String hash) {
            objects.put(key, hash.getBytes(StandardCharsets.UTF_8));
        }

        void blob(String hash) {
            objects.put("blobs/" + hash, new byte[0]);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public long size(String key) {
            byte[] value = objects.get(key);
            return value == null ? -1L : value.length;
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            byte[] value = objects.get(key);
            return value == null ? Optional.empty() : Optional.of(new Versioned(value, value));
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            objects.put(key, content);
            return true;
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            objects.put(key, in.readAllBytes());
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<String> list(String prefix) {
            String base = prefix.endsWith("/") ? prefix : prefix + "/";
            Set<String> children = new TreeSet<>();
            for (String key : objects.keySet()) {
                if (key.startsWith(base)) {
                    int slash = key.indexOf('/', base.length());
                    children.add(slash < 0 ? key.substring(base.length()) : key.substring(base.length(), slash));
                }
            }
            return new ArrayList<>(children);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}

    /** A {@link MapStore} that counts every {@code exists}/{@code size} probe of a {@code blobs/} object, so a test can
     *  assert {@link Policy#HIDE_WITHHELD} touched no blob at all. */
    private static final class SpyStore extends MapStore {

        int blobStats;

        @Override
        public boolean exists(String key) {
            if (key.startsWith("blobs/")) {
                blobStats++;
            }
            return super.exists(key);
        }

        @Override
        public long size(String key) {
            if (key.startsWith("blobs/")) {
                blobStats++;
            }
            return super.size(key);
        }
    }

    /** A store backend that cannot resolve a key, exactly as {@code FilesystemArtifactStore.resolve} throws an
     *  {@link java.nio.file.InvalidPathException} on an encoding-hostile name - every probe throws, so the test proves
     *  the seam contains the {@link RuntimeException} rather than letting it escape. */
    private static final class HostileStore extends MapStore {

        private static RuntimeException unresolvable(String key) {
            return new java.nio.file.InvalidPathException(key, "unmappable character");
        }

        @Override
        public boolean exists(String key) {
            throw unresolvable(key);
        }

        @Override
        public long size(String key) {
            throw unresolvable(key);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            throw unresolvable(key);
        }

        @Override
        public List<String> list(String prefix) {
            throw unresolvable(prefix);
        }
    }
}
