package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.HoldClears;
import build.jenesis.repository.gate.store.HoldLifecycle;
import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.KevHold;
import build.jenesis.repository.gate.store.KevHoldReleaseObserver;
import build.jenesis.repository.gate.LicenseHold;
import build.jenesis.repository.gate.store.LicenseHoldReleaseObserver;
import build.jenesis.repository.gate.QuarantineDispatch;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.HeldBy;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.gate.HeldElsewhere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retroactive-hold lifecycle over a real filesystem store: a KEV or license hold placed on a coordinate is read
 * back, its discovered {@link HoldReleaseObserver} promotes the cleared reasons into a sticky override on release (so a
 * later enforcement sweep never re-holds a human's release - self-heal §5) and consumes the hold record, a single
 * {@link HoldReleaseObserver#released} fan-out reaches every installed observer, a discard drops the record with no
 * override, and a held version is withheld from serving until its hold pointer is cleared.
 */
class HoldLifecycleTest {

    private static final String PATH = "/maven/org/vuln/lib/1.0/lib-1.0.jar";
    private static final String ECOSYSTEM = "Maven";
    private static final String COORD = "org.vuln:lib";
    private static final String VERSION = "1.0";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        // The doubly-scoped tenant/repository space the review surfaces operate over, and a published artifact so the
        // path resolves to its coordinate (the observers key their records by the descriptor's coordinate).
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
        Publication publication = new Publication(store);
        publication.link(PATH, publication.storeBlob(
                new ByteArrayInputStream("artifact".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void a_kev_hold_is_placed_then_released_by_its_observer_into_a_sticky_override() throws IOException {
        KevHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("CVE-2021-44228"));
        assertThat(KevHold.held(store, ECOSYSTEM, COORD, VERSION)).as("the hold is placed").isPresent();
        assertThat(new KevHoldReleaseObserver().holds(store, PATH)).as("the observer sees its own hold").isTrue();

        new KevHoldReleaseObserver().onReleased(store, PATH);

        assertThat(KevHold.overridden(store, ECOSYSTEM, COORD, VERSION))
                .as("the cleared CVE becomes a sticky override the sweep must not re-hold on")
                .contains("CVE-2021-44228");
        assertThat(KevHold.held(store, ECOSYSTEM, COORD, VERSION)).as("the hold record is consumed").isEmpty();
        assertThat(new KevHoldReleaseObserver().holds(store, PATH)).isFalse();
    }

    @Test
    void a_release_with_no_kev_record_writes_no_override() throws IOException {
        // A publish-time hold used to write no holds/kev record, and this kind recovered the CVEs from the quarantine
        // log's reason text by regular expression. The gate writes the record now (the known-exploited finding carries
        // its kind and CVE - ComplianceScreenTest proves that leg), so a release with no record has nothing to
        // promote: the quarantine log's wording is never read, and a CVSS reason's CVE can no longer leak into the
        // KEV override where a later CISA listing would find it already cleared.
        new QuarantineLog(store).record(Instant.parse("2026-07-01T00:00:00Z"), PATH, COORD + ":" + VERSION,
                Verdict.QUARANTINE, List.of("Known-exploited (CISA KEV): CVE-2021-44228", "CVE-2020-1234 (HIGH)"));
        assertThat(KevHold.held(store, ECOSYSTEM, COORD, VERSION)).as("no holds/kev record exists").isEmpty();

        new KevHoldReleaseObserver().onReleased(store, PATH);

        assertThat(KevHold.overridden(store, ECOSYSTEM, COORD, VERSION))
                .as("nothing is reconstructed from log text - the record is the only source of a release's override")
                .isEmpty();
    }

    @Test
    void a_license_hold_is_placed_then_released_by_its_observer() throws IOException {
        LicenseHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("GPL"));
        assertThat(LicenseHold.held(store, ECOSYSTEM, COORD, VERSION)).isPresent();

        new LicenseHoldReleaseObserver().onReleased(store, PATH);

        assertThat(LicenseHold.overridden(store, ECOSYSTEM, COORD, VERSION)).contains("GPL");
        assertThat(LicenseHold.held(store, ECOSYSTEM, COORD, VERSION)).isEmpty();
    }

    @Test
    void a_single_release_fans_out_to_every_installed_observer() throws IOException {
        KevHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("CVE-2021-44228"));
        LicenseHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("GPL"));
        assertThat(HoldReleaseObserver.anyHolds(store, PATH)).isTrue();

        HoldReleaseObserver.released(store, PATH);

        assertThat(KevHold.overridden(store, ECOSYSTEM, COORD, VERSION)).contains("CVE-2021-44228");
        assertThat(LicenseHold.overridden(store, ECOSYSTEM, COORD, VERSION)).contains("GPL");
        assertThat(HoldReleaseObserver.anyHolds(store, PATH)).as("both records consumed").isFalse();
    }

    @Test
    void a_discard_drops_the_hold_record_without_writing_an_override() throws IOException {
        KevHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("CVE-2021-44228"));

        HoldReleaseObserver.discarded(store, PATH);

        assertThat(KevHold.held(store, ECOSYSTEM, COORD, VERSION)).as("the record is reaped").isEmpty();
        assertThat(KevHold.overridden(store, ECOSYSTEM, COORD, VERSION))
                .as("a discard writes no override - no human accepted the finding").isEmpty();
    }

    @Test
    void a_held_version_is_withheld_from_serving_until_the_hold_clears() throws IOException {
        // The withhold read side is store truth: a live /quarantine pointer retracts serving even for an already-linked
        // path. Consulted through the screen the Publication applies on every read.
        Publication served = new Publication(store, List.of(new ComplianceScreen()));
        assertThat(served.located(PATH)).as("served before any hold").isPresent();

        KevHold.hold(store, ECOSYSTEM, COORD, VERSION, Set.of("CVE-2021-44228"));
        served.link("/quarantine" + PATH, served.storeBlob(
                new ByteArrayInputStream("swept body".getBytes(StandardCharsets.UTF_8))));
        assertThat(served.located(PATH)).as("withheld while the hold pointer is live").isEmpty();

        // The release surface clears the pointer after the observer has promoted the override.
        new KevHoldReleaseObserver().onReleased(store, PATH);
        served.unpublish("/quarantine" + PATH);
        assertThat(served.located(PATH)).as("serves again once released").isPresent();
    }

    /**
     *, the asymmetry that used to be fail-open. The {@code reachability} module is genuinely NOT on this test
     * module's graph - {@code test/gate} requires the gate, not {@code security.reachability} - so
     * {@code ReachabilityHoldReleaseObserver} is un-discoverable here exactly as it would be on a deployment that had
     * uninstalled or switched off that compliance module, while the record it left behind survives by design (nothing
     * deletes data on module absence). Before the fix both kind-neutral reads fanned out over the discovered providers
     * alone and therefore answered {@code false}, and both callers consume that answer permissively - so uninstalling
     * a compliance module silently released everything it was holding. The record is authoritative now, so it does not.
     */
    @Test
    void an_uninstalled_kinds_hold_still_holds() throws IOException {
        assertThat(HoldRecords.installedKinds())
                .as("the reachability module really is absent from this graph, or the case proves nothing")
                .containsExactlyInAnyOrder("kev", "license");
        // What that module's enforce sweep left behind before it was uninstalled.
        store.write(HoldRecords.key("reachability", ECOSYSTEM, COORD, VERSION),
                new ByteArrayInputStream("GHSA-0000-0000-0000".getBytes(StandardCharsets.UTF_8)));

        assertThat(HoldReleaseObserver.anyHolds(store, PATH))
                .as("the durable record is what answers 'is this held', not the provider list").isTrue();
        assertThat(HoldReleaseObserver.heldByAnotherKind(store, PATH, "kev"))
                .as("so an automated KEV release still cannot un-retract a hold the absent kind owns").isTrue();
        assertThat(HoldRecords.heldKinds(store, PATH)).containsExactly("reachability");
        assertThat(HoldRecords.orphanedKinds(store, PATH))
                .as("held, and visibly held by a kind nothing installed answers to").containsExactly("reachability");

        // Absence releases nothing: the fan-out reaches every installed hook and none of them owns this record.
        HoldReleaseObserver.released(store, PATH);

        assertThat(HoldReleaseObserver.anyHolds(store, PATH))
                .as("a fan-out over the installed hooks alone must leave an absent kind's hold standing").isTrue();
    }

    /**
     * on the second consumer of the kind-neutral read. {@link HoldClears#holder} is the predicate the
     * {@code withheld/} reconcile backstop lifts a content-addressed marker on, and clause (b) of it is "no
     * {@code holds/} record still covers a claimant". Asked through the provider registry that answer was an absence
     * for an uninstalled kind, so the backstop lifted the very marker that kind's hold depends on and the withheld
     * bytes served again. Asked through the record it is not.
     */
    @Test
    void the_withhold_reconcile_backstop_keeps_an_uninstalled_kinds_marker_standing() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        List<StoreRepositoryInventory.Coordinate> claimants =
                List.of(new StoreRepositoryInventory.Coordinate(ECOSYSTEM, COORD, VERSION));
        assertThat(inventory.paths(ECOSYSTEM, COORD, VERSION))
                .as("the claimant must resolve to a served path, or clause (b) is never reached").contains(PATH);
        assertThat(HoldClears.holder(store, inventory, "deadbeef", claimants))
                .as("with nothing holding it, the marker is provably holderless and the backstop may lift it")
                .isInstanceOf(Known.Absent.class);

        store.write(HoldRecords.key("reachability", ECOSYSTEM, COORD, VERSION),
                new ByteArrayInputStream("GHSA-0000-0000-0000".getBytes(StandardCharsets.UTF_8)));

        assertThat(HoldClears.holder(store, inventory, "deadbeef", claimants))
                .as("an uninstalled kind's record still covers the claimant, so the marker stays - fail closed")
                .isInstanceOf(Known.Present.class);
    }

    /**
     *, first of the four: the cross-alias guard. A {@code withheld/<hash>} marker is content-addressed - one
     * marker withholds the bytes wherever they serve - so before lifting one the release path asks whether any OTHER
     * still-held coordinate needs that hash. The index the holds write ({@link HeldBy}) answers that once it is
     * complete; until the one-time backfill has indexed every review pointer from before it, the answer comes from
     * the descent the backfill makes, which resolves each pointer's path to a coordinate and reads its full hash set
     * - through the owning FORMAT.
     *
     * <p>{@code npm} is a real product ecosystem whose module is genuinely off this test module's graph, so its served
     * path resolves to nothing here exactly as it would on a deployment that removed the module. Read as "that sibling
     * does not need this hash", the silence lifts the marker out from under a coordinate whose {@code /quarantine}
     * pointer is still standing - the held bytes serve again because a module is absent. Unjudgeable keeps it, and
     * keeps the backfill from stamping an index that is missing whatever that pointer holds.
     */
    @Test
    void a_marker_a_sibling_no_installed_format_can_place_might_need_is_never_lifted() throws IOException {
        Publication publication = new Publication(store);
        String held = "/npm/left-pad/-/left-pad-1.0.0.tgz";
        assertThat(new StoreRepositoryInventory(store).describe(held))
                .as("the npm module really is absent from this graph, or the case proves nothing").isEmpty();
        // The sibling's review pointer advertises its own first hash; the marker under consideration is a DIFFERENT
        // one it shares (an ancillary file), so only the full-hash leg could ever answer.
        publication.link("/quarantine" + held, publication.storeBlob(
                new ByteArrayInputStream("the sibling's primary artifact".getBytes(StandardCharsets.UTF_8))));
        String shared = publication.storeBlob(
                new ByteArrayInputStream("a blob both coordinates serve".getBytes(StandardCharsets.UTF_8)));
        Withheld.mark(store, shared);

        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("a live review pointer nothing can place is not an absence of holders - fail closed")
                .isInstanceOf(Known.Unknown.class);
        assertThat(HoldClears.clearReleased(store, shared, Set.of(PATH), "test"))
                .as("so the guarded clear never reaches the lift, and never has to re-mark").isFalse();
        assertThat(Withheld.is(store, shared))
                .as("the byte-identical sibling stays withheld while it is still under review").isTrue();
        assertThat(HeldBy.complete(store))
                .as("an index missing what a pointer nobody can judge holds is never stamped complete").isFalse();
    }

    /**
     * The cross-alias guard reads the index the holds write rather than descending the review queue: the first
     * question stamps an empty repository complete, a hold linked through {@link Publication} is found by its entry
     * and excluded when it is the releasing coordinate's own, an entry whose pointer is gone is no holder, and a
     * review pointer written behind the index's back is not found - which is the contract, since every hold writer
     * links through {@code Publication.link} and nothing else may.
     */
    @Test
    void the_cross_alias_guard_answers_from_the_index_the_holds_write() throws IOException {
        Publication publication = new Publication(store);
        String shared = publication.storeBlob(new ByteArrayInputStream("shared".getBytes(StandardCharsets.UTF_8)));
        Withheld.mark(store, shared);
        assertThat(HeldBy.complete(store)).isFalse();
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("nothing held: no alias").isInstanceOf(Known.Absent.class);
        assertThat(HeldBy.complete(store)).as("the first question backfilled nothing and stamped").isTrue();

        String sibling = "/maven/org/other/lib/2.0/lib-2.0.jar";
        publication.link("/quarantine" + sibling, shared);
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("a hold linked through Publication is indexed and found").isEqualTo(Known.known(sibling));
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH, sibling)))
                .as("the releasing coordinate's own paths are not another alias").isInstanceOf(Known.Absent.class);

        store.delete(Publication.quarantineKey(sibling));   // gone without an unpublish: the entry stands, dead
        assertThat(HeldBy.holders(store, shared, 10)).containsExactly(sibling);
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("an entry whose review pointer is not live is no holder").isInstanceOf(Known.Absent.class);

        String behind = "/maven/org/behind/lib/3.0/lib-3.0.jar";
        store.write(Publication.quarantineKey(behind),
                new ByteArrayInputStream(ServableNames.Pointer.render(shared, 6, true)));
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("a pointer written behind the index's back is not a hold the guard can see once the index is "
                        + "complete - every hold links through Publication").isInstanceOf(Known.Absent.class);
    }

    /**
     * A repository from before the index has review pointers nothing indexed. The first question asked of it is
     * answered by the descent the guard always made, and that descent is the backfill: every pointer it reads is
     * recorded under the hash it names, the repository is stamped, and from then on the index answers.
     */
    @Test
    void a_repository_from_before_the_index_is_backfilled_by_the_first_question() throws IOException {
        Publication publication = new Publication(store);
        String shared = publication.storeBlob(new ByteArrayInputStream("shared".getBytes(StandardCharsets.UTF_8)));
        String legacy = "/maven/org/legacy/lib/1.0/lib-1.0.jar";
        store.write(Publication.quarantineKey(legacy),
                new ByteArrayInputStream(ServableNames.Pointer.render(shared, 6, true)));   // no entry: pre-index
        Withheld.mark(store, shared);
        assertThat(HeldBy.holders(store, shared, 10)).isEmpty();

        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("answered by the descent, which is the backfill").isEqualTo(Known.known(legacy));
        assertThat(HeldBy.complete(store)).as("every pointer read and judged: stamped").isTrue();
        assertThat(HeldBy.holders(store, shared, 10)).as("the descent indexed what it read").containsExactly(legacy);
        assertThat(HeldElsewhere.withheldByAnotherAlias(store, shared, Set.of(PATH)))
                .as("and the index answers from here on").isEqualTo(Known.known(legacy));
        assertThat(HoldClears.clearReleased(store, shared, Set.of(PATH), "test"))
                .as("so the release of a byte-identical coordinate lifts nothing").isFalse();
        assertThat(Withheld.is(store, shared)).isTrue();
    }

    @Test
    void a_version_wider_than_the_enumeration_bound_keeps_its_hold_rather_than_releasing_it() throws IOException {
        // The bound decided 2026-08-31. What matters is not that a cap exists but which way it fails: past the bound
        // knownPaths answers UNKNOWN rather than a truncated list, and every caller reads unknown as "assume the
        // worst". For a hold that means the withhold stands. A truncated list would instead read as the complete set
        // of the version's paths and quietly release a version whose remaining paths were never looked at.
        Publication publication = new Publication(store);
        String bytes = publication.storeBlob(new ByteArrayInputStream("wide".getBytes(StandardCharsets.UTF_8)));
        for (int leaf = 0; leaf <= 512; leaf++) {                    // one past the cap
            publication.link(String.format("/maven/org/vuln/lib/1.0/lib-1.0-%04d.jar", leaf), bytes);
        }

        assertThat(new StoreRepositoryInventory(store).knownPaths(ECOSYSTEM, COORD, VERSION))
                .as("un-enumerable within the bound, reported as unknown rather than as the first 512")
                .isInstanceOf(Known.Unknown.class);

        assertThat(HeldElsewhere.othersStillHeld(store, new ArtifactDescriptor(
                ECOSYSTEM, COORD, VERSION, PATH, null, false, null, -1L), PATH))
                .as("so the hold stands: a version whose paths cannot be enumerated is not released").isTrue();
    }

    /**
     * A held sibling the inventory cannot see must still hold the version, and the directory probe is the only thing
     * that can see it.
     *
     * <p>The two legs of {@code othersStillHeld} are not redundant. The second asks the format which paths the
     * version serves, and that enumeration reads the <em>publish</em> namespace - but a held path's pointer is
     * diverted to {@code publish/quarantine<path>}, so a sibling that has only ever been held has no publish pointer
     * and is invisible to it. The first leg, the quarantine directory probe, is what catches it, and lifting a
     * version-wide withhold on the strength of the second leg alone would un-withhold that sibling.
     *
     * <p>This is also the arrangement that pins the probe's page size. It reads the first <em>two</em> names because
     * at most one of them can be the path being released, so a second name - or a first that is not it - settles the
     * question; reading one would answer "no other path is held" whenever the released path happens to sort first,
     * which is exactly this case. Nothing else in the tree distinguishes those two, so this test is the guard on it.
     */
    @Test
    void a_held_sibling_with_no_publish_pointer_still_holds_the_version() throws IOException {
        String sibling = "/maven/org/vuln/lib/1.0/lib-1.0.pom";      // sorts after the jar, so a one-name probe misses it
        Publication publication = new Publication(store);
        String bytes = publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8)));
        publication.link("/quarantine" + PATH, bytes);
        publication.link("/quarantine" + sibling, bytes);

        assertThat(new StoreRepositoryInventory(store).knownPaths(ECOSYSTEM, COORD, VERSION).determined().answer()
                .orElse(List.of()))
                .as("the inventory enumerates the publish namespace, where a held-only sibling never appears")
                .doesNotContain(sibling);

        assertThat(HeldElsewhere.othersStillHeld(store, new ArtifactDescriptor(
                ECOSYSTEM, COORD, VERSION, PATH, null, false, null, -1L), PATH))
                .as("the quarantine directory still holds a sibling, so the version stays withheld").isTrue();
    }

    /**
     *, second of the four: the per-version reaper's guard. Discarding one path of a multi-path hold must not
     * strip the state the remaining held paths are reviewed against - the kev/license/reachability records, the
     * findings document and its sidecar, the version-wide withhold markers, and, for a blobs-namespace version, the
     * served blobs themselves. The guard asks the owning format which paths the version serves; with that module gone
     * it enumerated nothing, and "nothing" read as "no other path is held" reaped all of it on the first discard.
     */
    @Test
    void a_single_path_discard_keeps_version_state_it_cannot_enumerate_the_siblings_of() throws IOException {
        ArtifactDescriptor absent = new ArtifactDescriptor(
                "npm", "left-pad", "1.0.0", "/npm/left-pad/-/left-pad-1.0.0.tgz", null, false, null, -1L);
        assertThat(new StoreRepositoryInventory(store).knownPaths("npm", "left-pad", "1.0.0"))
                .as("no installed format can enumerate this version's served paths at all")
                .isInstanceOf(Known.Unknown.class);

        assertThat(HeldElsewhere.othersStillHeld(store, absent, absent.path()))
                .as("so the version-scoped state stays; the last discard that CAN be judged reaps it").isTrue();

        // And the guard still answers honestly where the format IS installed: this version's only held path is the
        // one being discarded, so its per-version records are genuinely free to go.
        ArtifactDescriptor placed = new ArtifactDescriptor(ECOSYSTEM, COORD, VERSION, PATH, null, false, null, -1L);
        assertThat(HeldElsewhere.othersStillHeld(store, placed, PATH))
                .as("refusing to act on what cannot be enumerated is not a refusal to reap").isFalse();
    }

    /**
     * The other half of an orphaned hold must not be permanent either. Nothing automatic reaps it - not the
     * fan-out, not a sweep, not the module's absence - but an operator's explicit release at a review surface does,
     * naming the kind it is releasing. No override is written for it: an override's body is that kind's private
     * vocabulary, and inventing one would fabricate a human decision about advisories nobody read.
     */
    @Test
    void an_operator_release_is_what_clears_an_orphaned_hold() throws IOException {
        store.write(HoldRecords.key("reachability", ECOSYSTEM, COORD, VERSION),
                new ByteArrayInputStream("GHSA-0000-0000-0000".getBytes(StandardCharsets.UTF_8)));
        Publication publication = new Publication(store);
        publication.link("/quarantine" + PATH, publication.storeBlob(
                new ByteArrayInputStream("swept body".getBytes(StandardCharsets.UTF_8))));

        HoldLifecycle.release(store, PATH);

        assertThat(HoldReleaseObserver.anyHolds(store, PATH))
                .as("the operator released it, so it is released - not stuck held forever").isFalse();
        assertThat(HoldRecords.heldKinds(store, PATH)).as("the orphaned record is reaped, not stranded").isEmpty();
        assertThat(store.readVersioned("overrides/reachability/" + ECOSYSTEM + "/"
                + URLEncoder.encode(COORD, StandardCharsets.UTF_8) + "/" + VERSION))
                .as("and no override is invented in the absent kind's vocabulary").isEmpty();
    }

    /**
     * the release's link decision is a choice between two namespaces, and there is a third state in which
     * neither may be chosen. A publish-time hold whose release pointer was never linked gets one; a blobs-namespace
     * hold (npm/PyPI/NuGet/Cargo/RubyGems/Debian/Go) must NOT, because those formats never serve through a
     * {@code publish/} pointer and one left behind is a phantom that retention - reverse-mapping only through
     * {@code ArtifactLayout} - never reclaims, and that corrupts the namespace classification a later KEV re-listing
     * reads.
     *
     * <p>The two-valued question read an absent format's silence as "not blobs-namespace" and synthesized the pointer.
     * {@code npm} is a real product ecosystem genuinely off this module's graph, so this is exactly the state an
     * operator produces by removing the module. The honest answer is not to guess a namespace but to keep the hold and
     * say why - a review release is a human's item, and reinstalling the format makes it exact.
     */
    @Test
    void a_release_at_a_path_no_installed_format_claims_is_refused_rather_than_given_a_phantom_pointer()
            throws IOException {
        String held = "/npm/left-pad/-/left-pad-1.0.0.tgz";
        assertThat(new StoreRepositoryInventory(store).describe(held))
                .as("the npm module really is absent from this graph, or the case proves nothing").isEmpty();
        Publication publication = new Publication(store);
        publication.link("/quarantine" + held, publication.storeBlob(
                new ByteArrayInputStream("the held tarball".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> HoldLifecycle.release(store, held))
                .as("a release that can neither link a pointer nor prove it must not is refused, and says why")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no installed format claims that path");

        assertThat(publication.blob(held)).as("no phantom publish/ pointer was synthesized").isEmpty();
        assertThat(publication.blob("/quarantine" + held))
                .as("and the hold stands exactly as it was - the refusal mutates nothing").isPresent();
    }

    /**
     * The other half: refusing what cannot be classified is not a refusal to release. A publish-time hold whose
     * ecosystem an installed format DOES place still gets its release pointer linked and its publish-time facts
     * recorded, exactly as before - the classification is an answer here, not a silence.
     */
    @Test
    void a_release_of_a_publish_namespace_hold_an_installed_format_places_still_links_its_pointer()
            throws IOException {
        String path = "/maven/org/held/lib/2.0/lib-2.0.jar";
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(
                new ByteArrayInputStream("the held jar".getBytes(StandardCharsets.UTF_8)));
        publication.link("/quarantine" + path, hash);
        assertThat(publication.blob(path)).as("the release pointer was never linked").isEmpty();

        assertThat(HoldLifecycle.release(store, path)).isEqualTo(hash);

        assertThat(publication.blob(path))
                .as("a publish/-namespace release still links its pointer to the released bytes").contains(hash);
        assertThat(new StoreRepositoryInventory(store).publishedAt("Maven", "org.held:lib", "2.0"))
                .as("and records the publish-time facts retention and enforcement must see").isPresent();
        assertThat(publication.blob("/quarantine" + path)).as("the hold pointer is cleared").isEmpty();
    }

    // ---- the durable path -> coordinate record, and the three hold paths that reduce to it ----

    /**
     *, the gap itself. Every path-keyed hold question turns a request path into a coordinate through the owning
     * format's layout, so an uninstalled FORMAT module made {@code heldKinds(store, path)} answer empty while the
     * coordinate-keyed record it should have found sat there intact - a hold that reads as released because a module
     * is absent. {@code npm} is a real product ecosystem genuinely off this module's graph, so the path below is
     * exactly what an operator produces by removing that module, and the {@code holds/kev} record beside it is what
     * that ecosystem's enforcement sweep left behind before it went.
     *
     * <p>The record {@link HeldSubjects} writes where the hold is placed - while the format is by construction still
     * installed to answer - is what closes it. The first assertion is the pre-answer with the record removed
     * from the picture, so the second is load-bearing on the record rather than on the {@code holds/kev} row.
     */
    @Test
    void the_path_keyed_hold_question_answers_from_the_durable_subject_when_no_format_places_the_path()
            throws IOException {
        String held = "/npm/left-pad/-/left-pad-1.0.0.tgz";
        assertThat(new StoreRepositoryInventory(store).describe(held))
                .as("the npm module really is absent from this graph, or the case proves nothing").isEmpty();
        // Both modules are genuinely off this graph: npm lays the path out, reachability wrote the record - so this is
        // an uninstalled FORMAT and an uninstalled KIND at once, which is what an operator produces by removing both.
        store.write(HoldRecords.key("reachability", "npm", "left-pad", "1.0.0"),
                new ByteArrayInputStream("GHSA-0000-0000-0000".getBytes(StandardCharsets.UTF_8)));

        assertThat(HoldRecords.heldKinds(store, held))
                .as("with nothing able to turn the path into a coordinate there is no record key to look under - "
                        + "this is the answer the whole ticket is about")
                .isEmpty();

        HeldSubjects.record(store, held, "npm", "left-pad", "1.0.0");

        assertThat(HoldRecords.heldKinds(store, held))
                .as("the record the hold wrote when its format was installed is the route back to the coordinate")
                .containsExactly("reachability");
        assertThat(HoldRecords.orphanedKinds(store, held))
                .as("and it is visibly a hold no installed module answers to").containsExactly("reachability");
        assertThat(HoldRecords.heldKinds(store, List.of(held)).get(held))
                .as("the review-queue page form answers from the same fallback, or one surface would disagree "
                        + "with the other")
                .containsExactly("reachability");
        assertThat(HeldSubjects.paths(store, "npm", "left-pad", "1.0.0"))
                .as("and the version face names the held path, the direction a coordinate-keyed screen needs")
                .containsExactly(held);
    }

    /**
     * The record is a statement about a hold and dies with it: an operator's release at the review surface reaps the
     * orphaned {@code holds/} record - which it can now find at all, this path being unplaceable - and the subject
     * record that made it findable, leaving nothing under either space.
     */
    @Test
    void a_release_reaps_the_subject_record_with_the_hold_it_described() throws IOException {
        String held = "/npm/left-pad/-/left-pad-2.0.0.tgz";
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(
                new ByteArrayInputStream("the held tarball".getBytes(StandardCharsets.UTF_8)));
        publication.link(held, hash);   // a retroactive hold overlays an already-serving path
        store.write(HoldRecords.key("reachability", "npm", "left-pad", "2.0.0"),
                new ByteArrayInputStream("GHSA-0000-0000-0000".getBytes(StandardCharsets.UTF_8)));
        HeldSubjects.hold(publication, store, held, hash, "npm", "left-pad", "2.0.0");

        assertThat(HoldRecords.heldKinds(store, held)).as("held, and findable without the npm module")
                .containsExactly("reachability");

        HoldLifecycle.release(store, held);

        assertThat(publication.blob("/quarantine" + held)).as("the human released it").isEmpty();
        assertThat(HoldRecords.heldKinds(store, held)).as("the orphaned record is reaped, not stranded").isEmpty();
        assertThat(HeldSubjects.read(store, held))
                .as("and the subject record goes with the hold it described - reclaimed by the lifecycle that ends "
                        + "the hold, never by a sweep")
                .isEmpty();
        assertThat(HeldSubjects.paths(store, "npm", "left-pad", "2.0.0"))
                .as("both faces go together, or the version face would withhold the name forever").isEmpty();
    }

    /**
     * (2). A discard has two halves - clear the review handle, and stop the bytes serving - and with the owning
     * format's module off the graph the second silently does nothing while the first ran unconditionally: the review
     * handle went and the held bytes kept serving, with nothing left to find them by. The durable subject record is
     * what tells the two silences apart, so the discard can refuse instead of half-destroying.
     */
    @Test
    void a_discard_that_cannot_stop_the_version_serving_is_refused_rather_than_taking_the_review_handle()
            throws IOException {
        String held = "/npm/left-pad/-/left-pad-3.0.0.tgz";
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(
                new ByteArrayInputStream("the held tarball".getBytes(StandardCharsets.UTF_8)));
        HeldSubjects.hold(publication, store, held, hash, "npm", "left-pad", "3.0.0");

        assertThatThrownBy(() -> HoldLifecycle.discard(store, held))
                .as("a discard that could clear the handle but not the bytes is refused, and says why")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refused")
                .hasMessageContaining("left-pad:3.0.0");

        assertThat(publication.blob("/quarantine" + held))
                .as("the review handle stays: the artifact is still held and still reviewable").isPresent();
        assertThat(HeldSubjects.read(store, held)).as("and so does the record that explains it").isPresent();

        // The control: a held path that names NO versioned artifact is a different fact, not a missing one - there is
        // nothing to keep serving, so the discard proceeds exactly as it always did.
        String checksum = "/npm/left-pad/-/left-pad-3.0.0.tgz.sha1";
        HeldSubjects.record(store, checksum, "npm", null, null);
        publication.link("/quarantine" + checksum, hash);
        assertThat(HoldLifecycle.discard(store, checksum))
                .as("refusing to destroy what cannot be judged is not a refusal to discard anything").isTrue();
        assertThat(publication.blob("/quarantine" + checksum)).isEmpty();
    }

    /**
     * (3). A screen-quarantined upload's held blob is the publish <em>envelope</em>, not the served artifact, so
     * a release replays the format's own dispatch to materialise the version. With that format uninstalled the replay
     * used to degrade to linking the stored blob "so the hold still resolves" - which materialises no installable
     * version at all and strands the phantom {@code publish/} pointer closed on the sibling branch. The honest
     * answer is the earlier: keep the hold, say why, and let reinstalling the module make the release exact.
     */
    @Test
    void a_screen_quarantined_release_whose_dispatch_format_is_gone_is_refused_rather_than_linking_the_envelope()
            throws IOException {
        String held = "/npm/-/user";   // the versionless publish endpoint an npm packument is PUT to
        Publication publication = new Publication(store);
        String hash = publication.storeBlob(
                new ByteArrayInputStream("{\"_id\":\"left-pad\"}".getBytes(StandardCharsets.UTF_8)));
        publication.link("/quarantine" + held, hash);
        QuarantineDispatch.record(store, held, "npm", "PUT", hash, Map.of());

        assertThatThrownBy(() -> HoldLifecycle.release(store, held))
                .as("the module that would lay the envelope out is gone, so the release is refused")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refused");

        assertThat(publication.blob(held))
                .as("no phantom publish/ pointer was linked over an envelope nothing serves").isEmpty();
        assertThat(publication.blob("/quarantine" + held))
                .as("and the review handle still stands, so the hold is still reviewable").isPresent();
        assertThat(QuarantineDispatch.read(store, held))
                .as("the replay context survives too - reinstalling the module makes the release exact").isPresent();
    }
}
