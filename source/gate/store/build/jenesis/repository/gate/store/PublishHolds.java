package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.HoldKind;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.HoldRecords;

/**
 * The holds a screened publish leaves behind and the ones it lifts - the publish gate's side of the hold lifecycle,
 * beside the review surfaces' release and discard in {@link HoldLifecycle}. A held upload's subject and kind records
 * are written where the reviewer will meet the hold ({@link #reviewPath}); an accepted upload retires a stale gate
 * hold at its path unless a retroactive sweep owns it, and logs a verdict the admission superseded; a publish that
 * completes a held neighbour's declaration re-assesses it and releases it through {@link HoldLifecycle#release} if
 * the gate now clears it; and a blobs-namespace publish is checked for a reverse hold mapping that resolves it.
 *
 * <p>Stateless: the {@link ComplianceScreen} decides when each runs and hands in what it owns - the inspection, the
 * gate this publish assessed through, the recorder and the hold-mapping wiring.
 */
final class PublishHolds {

    /** The screen's own category, so an operator's logging level for the gate covers every line it writes. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComplianceScreen.class);

    private PublishHolds() {
    }

    /** Assess inspected subjects through the gate the publish assessed through, its per-repository overlays
     *  included, so a re-assessment cannot differ from the publish for a reason of the caller's own. */
    @FunctionalInterface
    interface Assessor {
        ComplianceGate.Assessment assess(List<ComplianceGate.Subject> inspected, String path);
    }

    /** Log the verdict of an upload the gate held and the chain still accepted: the publication primitive joined the
     *  hold to the admission that already serves these very bytes - a rival's signature released the path while this
     *  upload's verdict was in flight - so nothing is held, and the log says so rather than losing the verdict: the
     *  gate's own reasons, under the outcome the path actually has. */
    static void logSuperseded(ArtifactStore store, ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected,
                              ComplianceGate.Assessment assessment) throws IOException {
        List<String> reasons = new ArrayList<>();
        reasons.add("Hold superseded: " + artifact.path() + " already serves these bytes, admitted with "
                + "their signature before this upload's verdict landed, so the identical upload is "
                + "accepted as that artifact and nothing is held; the gate had found:");
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        new QuarantineLog(store).record(Clocks.now(), reviewPath(artifact, inspected),
                coordinate(artifact, inspected), Verdict.ALLOW, reasons);
    }

    /**
     * Retire a stale publish-time gate hold at the path of an accepted publish. The gate just cleared a fresh upload
     * there, and serving the accepted artifact must not stay blocked by a verdict on a body that was since replaced.
     * But a retroactive KEV sweep writes its holds/kev record BEFORE it links the {@code /quarantine} pointer, so
     * blindly deleting the pointer would strand that record (a dangling holds/kev entry until the next reanalysis
     * interval) and drop a known-exploited hold a re-publish must not clear. So only the pointer this screen's own
     * kind of hold owns is cleared - when the sweep owns it, both stay in place and the reanalysis pass releases it.
     */
    static void retireStale(ArtifactStore store, StoreRepositoryInventory inventory, ArtifactDescriptor artifact)
            throws IOException {
        if (store.readVersioned(Publication.quarantineKey(artifact.path())).isPresent()
                && !sweepHeld(store, inventory, artifact)) {
            store.delete(Publication.quarantineKey(artifact.path()));
            // The serving pointer's copy of the superseded hold goes with it, before the accepted layout
            // links the fresh bytes: a link carries its predecessor's flag, so a flag left here would hold
            // the artifact this screen just cleared. A sweep-owned pointer left standing above keeps its
            // copy too, which is what keeps a known-exploited hold effective across a re-publish.
            new Publication(store).suppress(artifact.path(), false);
            // The superseded hold's subject record goes with the pointer it explained: the record is
            // reclaimed by the lifecycle that ends the hold, never by a sweep, and never because a module is
            // absent. A sweep-owned pointer left standing above keeps its record too.
            HeldSubjects.forget(store, artifact.path());
        }
    }

    /**
     * The screen-time half of the durable path -> coordinate record, and the kind records of a quarantining verdict.
     * The publication has just linked the {@code /quarantine<path>} review pointer for this disposition, and this is
     * the moment the owning format is by construction installed - it is what screened the upload - so the subject is
     * captured now and every later path-keyed question answers from it whether or not that module survives. It is the
     * SAME resolution the accepted branch's sidecars use (the layout first, the inspected envelope subject for a
     * versionless publish endpoint second), so a held upload and an accepted one agree on what the path was. A path
     * that resolves to no coordinate is recorded as such: "asked, and there is no version here" is a fact a reader
     * needs, and is not the same as no record at all. The write is inside the screen's own pre-commit window and its
     * IOException propagates, so a hold whose subject could not be recorded fails the publish rather than standing
     * unexplained.
     */
    static void recordHeld(ArtifactStore store, String reviewPath, ArtifactDescriptor artifact,
                           List<ComplianceGate.Subject> inspected, ComplianceGate.Assessment assessment)
            throws IOException {
        Optional<StoreRepositoryInventory.Coordinate> subject =
                PublishRecorder.publishedCoordinate(store, artifact, inspected);
        HeldSubjects.record(store, reviewPath,
                subject.map(StoreRepositoryInventory.Coordinate::ecosystem).orElse(artifact.ecosystem()),
                subject.map(StoreRepositoryInventory.Coordinate::coordinate).orElse(null),
                subject.map(StoreRepositoryInventory.Coordinate::version).orElse(null));
        if (subject.isPresent() && assessment != null) {
            recordHolds(store, subject.get(), assessment);
        }
    }

    /**
     * Release the neighbours this publish completed the declaration for - the late-declaration re-assessment
     * {@link QualityInspector#completes} exists for.
     *
     * <p>The case is Maven's and it is the ordinary one: {@code mvn deploy} sends the jar before the POM, so a jar
     * carrying no licence of its own is screened while the coordinate's licence document is still in flight, reads as
     * unknown, and is held by the {@code license-unknown} dial's own default. Nothing was wrong with the publish and
     * nothing is wrong with the artifact - the evidence simply had not arrived. When it does, each held neighbour is
     * re-assessed <em>through the ordinary publish gate</em> over its own stored bytes, and released only if that gate
     * now answers {@code ALLOW}.
     *
     * <p><b>This is not an override.</b> A neighbour still held for any other reason - a denied licence, an advisory,
     * a deny-listed coordinate, an unparseable body - fails the same assessment again and stays exactly where it is,
     * because the only thing that changed for it is a sibling it does not read. Nor does it re-decide settled
     * evidence: {@link GatePolicyProvider}'s idempotency clause asks that a re-screen reproduce the verdict the
     * artifact was published under, and this reproduces it faithfully - over a store that now holds one document more
     * than it did, which is the whole of what makes the answer legitimately different.
     *
     * <p>The screen calls this only while no review release is replaying and a gate is wired; {@code assessor} is the
     * gate this publish assessed through.
     */
    static void releaseCompleted(PublishInspection inspection, Assessor assessor, PublishRecorder recorder,
                                 ArtifactStore store, String published) throws IOException {
        for (String prefix : inspection.completing(published)) {
            releaseCompletedUnder(inspection, assessor, recorder, store, published, prefix);
        }
    }

    /** Re-assess every held artifact directly under {@code prefix} except the one just published, releasing those the
     *  gate now clears. Each neighbour is contained on its own: one that will not inspect leaves the rest to be
     *  judged, since a body that cannot be parsed is a reason to keep THAT hold, not to abandon the pass. */
    private static void releaseCompletedUnder(PublishInspection inspection, Assessor assessor,
                                              PublishRecorder recorder, ArtifactStore store, String published,
                                              String prefix) throws IOException {
        Publication publication = new Publication(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        for (String child : store.list(Publication.quarantineKey(prefix))) {
            String path = prefix + child;
            if (path.equals(published)) {
                continue;
            }
            try {
                if (clearedByCompletion(inspection, assessor, recorder, publication, inventory, store, path,
                        published)) {
                    // The release is the ordinary one, so every hook, override and withhold marker is lifted exactly
                    // as a reviewer's release lifts them.
                    HoldLifecycle.release(store, path);
                    LOGGER.info("Released {}: its declaration was completed by the publish of {}, and the gate now "
                            + "clears it", path, published);
                }
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Could not re-assess held " + path + " after " + published + " published; it stays held",
                        failure);
            }
        }
    }

    /** Whether the gate now answers ALLOW for the artifact held at {@code path}, read back from its held blob with the
     *  siblings that have since landed in view. Anything short of a clean ALLOW - no held blob, no describable
     *  coordinate, nothing to gate, an unparseable body, a feed that failed closed - keeps the hold. */
    private static boolean clearedByCompletion(PublishInspection inspection, Assessor assessor,
                                               PublishRecorder recorder, Publication publication,
                                               StoreRepositoryInventory inventory, ArtifactStore store, String path,
                                               String published) throws IOException {
        Optional<String> held = publication.blob("/quarantine" + path);
        if (held.isEmpty()) {
            return false;
        }
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty()) {
            return false;
        }
        // The publish path's own read view, over the HELD blob rather than a freshly stored one: the sibling reads it
        // carries now resolve the document that has since been published, which is the entire point. The HELD view,
        // because a sidecar is withheld by its subject's hold - so an artifact held for the want of its signature
        // could never be released by that signature arriving, the re-assessment asking what a client would see and
        // being answered by the hold it is deciding about. Publication states the reasoning at heldContentOf.
        List<ComplianceGate.Subject> inspected =
                inspection.inspect(described.get(), publication.heldContentOf(held.get()));
        if (inspected.isEmpty()) {
            return false;
        }
        ComplianceGate.Assessment assessment = assessor.assess(inspected, path);
        // The version is being judged on the strength of what just landed - for Maven's order, the signature - so
        // this is where its signer is first seen: learned if it releases, wanted if nobody could place it.
        recorder.recordMaintainers(store, inspected, path);
        recorder.reportSigners(store, inspected, path, assessment.verdict() == Verdict.ALLOW);
        if (assessment.verdict() == Verdict.ALLOW) {
            return true;
        }
        // Still held, and possibly for a new reason: the sidecar that just landed may have said who signed the
        // artifact, and "no publisher signature" is no longer what a reviewer should read. The log's latest row for
        // the path is what the review queue shows, so it says what the re-assessment found.
        List<String> reasons = new ArrayList<>();
        reasons.add("Re-assessed after " + published + " landed; still held:");
        for (ComplianceGate.Finding finding : assessment.findings()) {
            reasons.add(finding.detail());
        }
        new QuarantineLog(store).record(Clocks.now(), path, coordinate(described.get(), inspected),
                Verdict.QUARANTINE, reasons);
        return false;
    }

    /**
     * Verify that a blobs-namespace format resolves the publish it just laid out: the served request path is one its
     * {@code servedPaths} maps the coordinate version back to, and the stored content hash is one its
     * {@code blobHashes} resolves. A format whose {@code describe} emits a coordinate (so a retroactive KEV/license
     * sweep enumerates the version) but whose {@code blobKeys}/{@code servedPaths} resolve NOTHING back would hold
     * un-retractably - the RPM/conda/conan class that shipped five times and surfaced only in a later audit. Caught
     * here it fails on the FIRST publish. On a break: emit {@code jenrepo.publish.holdmapping.broken{eco}} + one WARN,
     * and {@code throw} only when {@code jenrepo.strict-hold-mapping} is on (every test config sets it) - production
     * stays alarm-not-abort, since a broken format must not DoS publishes (the {@code hold.unenforceable} gauge
     * reasoning). Scoped strictly to the JUST-published path/hash, which the format just wrote, so an
     * evicted-but-still- enumerated sibling version - which legitimately resolves to nothing at describe time - never
     * false-positives. Only blobs-namespace ecosystems are checked: a {@code publish/}-namespace layout (Maven) or a
     * non-blobs upload has no such reverse mapping to verify, and a path that names no versioned artifact (an index, a
     * packument, a versionless envelope endpoint) is skipped exactly as the {@code published} record is.
     */
    static void verifyHoldMapping(ArtifactDescriptor artifact, ArtifactStore store,
                                  ComplianceScreen.HoldMappingBrokenListener meter, BooleanSupplier strict)
            throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(artifact.path());
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return;   // the path names no versioned artifact (an index/packument/envelope) - nothing to round-trip
        }
        String ecosystem = described.get().ecosystem();
        if (ecosystem == null || !inventory.servesFromBlobs(ecosystem)) {
            return;   // a publish/-namespace (Maven) or non-blobs ecosystem has no blobs-namespace mapping to verify
        }
        String coordinate = described.get().coordinate();
        String version = described.get().version();
        // The two pointer reads: the served path just requested must be one the coordinate maps back to, and the
        // content hash just stored must be one the coordinate resolves. Both scoped to THIS publish's path/hash.
        boolean pathResolves = inventory.paths(ecosystem, coordinate, version).contains(artifact.path());
        boolean hashResolves = artifact.hash() != null
                && inventory.blobHashes(ecosystem, coordinate, version).contains(artifact.hash());
        if (pathResolves && hashResolves) {
            return;   // the reverse mapping resolves the publish just made - the format is wired
        }
        LOGGER.warn("Repository publish of {} {}:{} through the blobs-namespace {} format resolves no reverse hold "
                        + "mapping for the artifact just served ({}): servedPaths {} the request path, blobHashes {} "
                        + "the stored content hash. A retroactive known-exploited or license hold on this version "
                        + "could mark nothing and retract no served path - the blobKeys/servedPaths mapping is unwired. "
                        + "This is caught at publish rather than in a later audit.",
                ecosystem, coordinate, version, ecosystem, artifact.path(),
                pathResolves ? "contains" : "is MISSING", hashResolves ? "contains" : "is MISSING");
        if (meter != null) {
            meter.broken(ecosystem);
        }
        if (strict != null && strict.getAsBoolean()) {
            throw new IOException("hold-mapping round-trip broken for " + ecosystem + " " + coordinate + ":" + version
                    + " at " + artifact.path() + " - the blobs-namespace format enumerates this version but its "
                    + "blobKeys/servedPaths resolve neither the served path nor the stored content hash "
                    + "(jenrepo.strict-hold-mapping is on)");
        }
    }

    /**
     * Write the {@code holds/<kind>} record for every finding of the quarantining assessment that names a hold kind,
     * grouped by kind - the same record the kind's retroactive sweep writes before it links its hold pointers, so an
     * operator's release of this publish-time hold promotes the same subjects into the same sticky override and the
     * sweep never re-holds a version a human has cleared. Before the finding carried its kind, a gate hold wrote no
     * record and the KEV kind recovered the CVEs from the quarantine log's reason text by regular expression; a
     * finding whose wording did not match left the release without an override, and the next sweep re-held it.
     * Written inside the screen's pre-commit window like the held-subject record beside it, so a hold whose record
     * could not be written fails the publish rather than standing without it.
     */
    private static void recordHolds(ArtifactStore store, StoreRepositoryInventory.Coordinate subject,
                                    ComplianceGate.Assessment assessment) throws IOException {
        Map<String, Set<String>> byKind = new LinkedHashMap<>();
        for (ComplianceGate.Finding finding : assessment.findings()) {
            if (finding.hold() != null && !finding.hold().subjects().isEmpty()) {
                byKind.computeIfAbsent(finding.hold().kind(), _ -> new LinkedHashSet<>())
                        .addAll(finding.hold().subjects());
            }
        }
        for (Map.Entry<String, Set<String>> kind : byKind.entrySet()) {
            HoldKind.of(kind.getKey()).hold(store, subject.ecosystem(), subject.coordinate(), subject.version(),
                    kind.getValue());
        }
    }

    /** Whether ANY retroactive enforcement sweep owns a hold on this path's coordinate - a {@code holds/} record of
     *  any discovered kind (KEV, license, reachability, a plugged future one), written before its {@code /quarantine}
     *  pointer. When one does, an accepted publish must not clear the pointer: doing so would strand the sweep's hold
     *  record and let an unprivileged re-upload launder a human-review hold (consulting one kind alone would let a
     *  re-publish declaring clean metadata clear another kind's retroactive hold). The check reads the durable
     *  {@link HoldRecords} and then the discovered {@link HoldReleaseObserver}s, so a new hold kind joins by writing a
     *  record, never an edit here - and a kind whose module has been UNINSTALLED still counts, so
     *  uninstalling a compliance module cannot turn an ordinary re-upload into a laundering channel for the holds it
     *  had placed. A path a format DID place and that no sweep holds is not sweep-owned, so a plain publish-time gate
     *  hold is cleared as before.
     *
     *  <p><b>The one remaining way absence answered here is closed at this caller.</b> {@code anyHolds} keys
     *  on the coordinate a request path resolves to, and that resolution needs the owning FORMAT installed - the one
     *  dependence left standing and left open, for want of a durable path &rarr; coordinate record. With
     *  that format's module gone the path resolves to nothing, every record read has no key to look under, and the
     *  answer degrades to "not held" - so this leg would delete {@code publish/quarantine<path>}, the review queue's
     *  only index of the hold, on an accepted upload to that path. The question here is not "is anything held" but
     *  "may this screen clear a pointer it may not own", and unresolvable is not ownership: an unplaceable path
     *  answers sweep-owned and the pointer stays. The cost is a stale publish-time pointer left standing while the
     *  format is uninstalled - visible, and lifted by a review release - against a hold silently laundered. */
    private static boolean sweepHeld(ArtifactStore store, StoreRepositoryInventory inventory,
                                     ArtifactDescriptor artifact) throws IOException {
        if (inventory.describe(artifact.path()).isEmpty()) {
            return true;    // no installed format places this path, so no hold on it can be proved absent
        }
        return HoldReleaseObserver.anyHolds(store, artifact.path());
    }

    /** The coordinate the log names: what the inspector parsed, or the descriptor's own, or the bare path when the
     *  withholding verdict came from another screen over an artifact this one had nothing to say about. */
    static String coordinate(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        if (inspected != null && !inspected.isEmpty()) {
            ComplianceGate.Subject subject = inspected.getFirst();
            return subject.coordinate() + ":" + subject.version();
        }
        return artifact.coordinate() == null ? artifact.path() : artifact.coordinate() + ":" + artifact.version();
    }

    /**
     * Where a hold this screen is recording will actually be reviewable: the screened path, unless the
     * descriptor names no coordinate and an installed blobs-namespace layout derives exactly one served path for the
     * coordinate an inspector <em>did</em> read out of the body - the shape of a format whose coordinate lives inside
     * the artifact, which commits under its push endpoint and re-keys its {@code /quarantine} handle onto the package
     * afterwards.
     *
     * <p>Three conditions, each load-bearing. A descriptor that already names the coordinate is the artifact's own
     * path and nothing re-keys it. The derivation is the store-free
     * {@link StoreRepositoryInventory#plannedPaths} one, because this runs BEFORE the layout and the pointer a
     * store-backed enumeration probes for does not exist yet. And exactly one path: a format serving a version at
     * several aliases has no single review handle to agree with, so the screened path stays what it was rather than
     * this picking one and disagreeing with the other.
     */
    static String reviewPath(ArtifactDescriptor artifact, List<ComplianceGate.Subject> inspected) {
        // A null inspected list is the leg that never ran assess on this thread - a hook-contract driver calling
        // committed directly, or a disposition another interceptor reached. There is nothing to derive from, so the
        // screened path stands, exactly as the coordinate() and publishedCoordinate() reads beside this one do.
        if (inspected == null || (artifact.coordinate() != null && artifact.version() != null)) {
            return artifact.path();
        }
        for (ComplianceGate.Subject subject : inspected) {
            List<String> planned = StoreRepositoryInventory.plannedPaths(
                    subject.ecosystem(), subject.coordinate(), subject.version());
            if (planned.size() == 1) {
                return planned.getFirst();
            }
        }
        return artifact.path();
    }
}
