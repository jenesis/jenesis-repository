package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.HeldBy;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.HoldRecords;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.QuarantineDispatch;
import build.jenesis.repository.gate.HoldClears;
import build.jenesis.repository.gate.HeldElsewhere;

/**
 * The one release/discard primitive every review surface delegates to, so the HTTP API ({@code GatedRepository})
 * and the console ({@code RepositoryAdmin}) cannot disagree on crash-window ordering - before this each carried its
 * own copy with <em>opposite</em> orderings, and the API-side copy could re-hold a release a human had just cleared.
 * Both operations are idempotent and converge on re-run after a crash at any point:
 *
 * <ul>
 *   <li>{@link #release}: the override markers are made durable <em>first</em> (a crash leaves the artifact
 *       held-and-overridden - a re-run converges, and no enforce sweep re-holds a human's release), then the release
 *       pointer is linked <em>only when absent</em> - a version re-published with corrected bytes while held keeps
 *       the corrected blob rather than being silently rolled back to the quarantined one - and only then is the
 *       {@code /quarantine} pointer cleared.</li>
 *   <li>{@link #discard}: a discard against a path with no live hold is a refused no-op, so a duplicate or stale
 *       discard can never wipe a now-serving version's findings, waivers and quarantine history. The log rows and
 *       hold records go first (the pointer - the review queue's index - stays as the retry surface if the node dies
 *       mid-discard), then a <em>retroactive</em> hold's release pointer is evicted so the discarded artifact does
 *       not resume serving the moment its hold lifts - but only while it still points at the held bytes, so a
 *       corrected republish keeps serving.</li>
 * </ul>
 */
public final class HoldLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldLifecycle.class);

    /** The media type an OCI release stamps into the {@code oci/types/<hex>} sidecar when the held manifest recorded no
     *  {@code Content-Type} - the same default the {@code OciManifests} accept path and serve path fall back to, so
     *  a released manifest that was pushed without a content type serves as the OCI image manifest exactly as an
     *  accepted one would. */
    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private HoldLifecycle() {
    }

    /**
     * Promote a quarantined path into the release layout after review and clear the hold, returning the released
     * blob's hash. The quarantined blob is already stored content-addressed, so this re-points rather than copies.
     * Ordering is the crash-safe one: overrides first ({@link HoldReleaseObserver#released}), the guarded link and
     * publish-time sidecar next, the {@code /quarantine} pointer last.
     *
     * @throws IllegalStateException when nothing is quarantined at {@code path} (already released or never held).
     */
    public static String release(ArtifactStore store, String path) throws IOException {
        return release(store, path, HoldReleaseObserver.discovered());
    }

    /** {@link #release(ArtifactStore, String)} fanning out to {@code hooks} rather than to the discovered ones - the
     *  substitution seam, so a contract over one hook drives the real choreography with that hook in it. */
    public static String release(ArtifactStore store, String path, Iterable<HoldReleaseObserver> hooks)
            throws IOException {
        Publication publication = new Publication(store);
        Optional<String> held = publication.blob("/quarantine" + path);
        if (held.isEmpty()) {
            throw new IllegalStateException("Nothing quarantined at " + path);
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        // Read the two facts the link decision turns on BEFORE any hook writes, so the namespace refusal below can
        // leave the store untouched rather than land after the overrides are promoted and the orphaned records reaped.
        // Both are pure reads of small objects, and no hook may write either key space (contract clause 8).
        Optional<QuarantineDispatch> dispatch = QuarantineDispatch.read(store, path);
        Optional<String> current = publication.blob(path);
        // a release that can neither link a pointer nor prove it must not is refused, and refused here - before
        // anything is mutated. With the owning format's module off the graph describe() answers nothing, the
        // two-valued blobs-namespace question read that silence as "not blobs-namespace", and the release synthesized a
        // publish/<path> pointer for a version whose format may never serve through one: a phantom entry no format
        // serves, that retention's ArtifactLayout reverse-mapping never reclaims, and that corrupts the namespace
        // classification a later KEV re-listing reads. The hold is a human's review item, so the honest answer is to
        // keep it and say why, not to guess a namespace: reinstalling the format module makes the release exact.
        if (dispatch.isEmpty() && current.isEmpty() && unplaceable(publishPointer(inventory, path))) {
            throw new IOException("Release of " + path + " is refused: no installed format claims that path, so this "
                    + "release cannot tell a publish/-namespace hold - whose release links a release pointer - from a "
                    + "blobs-namespace one, whose release must not, and linking one for a blobs-namespace format "
                    + "strands a pointer nothing serves and retention never reclaims. The hold stays exactly as it is; "
                    + "install the format module again to release it.");
        }
        // One artifact, several names: a cross-published modular jar serves under its Maven coordinate AND under
        // /module/<name>/<version>/<name>.jar and /module/<name>/<name>.jar, over one blob. The operator never chose
        // to publish the second and third - one publish created them and one sweep held them - so a reviewer clearing
        // the artifact clears all of them, and the alias views are not review items of their own.
        //
        // They go FIRST, and that ordering is the fix rather than an incidental. The marker lift below refuses when
        // another live /quarantine pointer still names these bytes, which is exactly what a held alias is; and
        // clearVersionWithholds' othersStillHeld sees an alias's pointer as a sibling of the version still under
        // review and keeps every version-wide marker standing. Clearing the aliases before either runs leaves both
        // asking their question against a store where no alias holds anything, so neither needs a widened exclusion
        // set - and an alias whose clear FAILED still keeps the marker, which is the direction a release may err in.
        //
        // The relation is read from the record the cross-publish wrote (ServedAliases), never inferred: same content
        // hash and same coordinate version were both tried as the signal and both are unsound - distinct files of one
        // version routinely share a hash, and a version's other files are not this file under another name.
        for (String alias : ServedAliases.group(store, path)) {
            if (!alias.equals(path)) {
                releaseAlias(store, publication, path, alias, hooks);
            }
        }
        // Overrides durable BEFORE the pointer clears: a crash here leaves the hold in place and overridden, so a
        // re-run converges and the kev-/license-/reachability-enforce sweeps never re-hold the human's release.
        HoldReleaseObserver.released(store, path, hooks);
        // A hold whose kind is no longer installed has no hook to consume its record, and the record is authoritative
        // - so without this a released artifact would read as held forever and no operator could ever get it
        // back. This IS that operator action: a human chose release at a review surface, so the orphaned records go
        // too, named in the log. Absence alone never reaches here. Inside the same pre-commit window as the fan-out
        // and failing the release the same way, so a store failure leaves the hold standing.
        HoldRecords.releaseOrphaned(store, path);
        if (dispatch.isPresent()) {
            // A screen()-quarantined hosted upload: the deploy choreography stored and screened the body but SKIPPED
            // the format dispatch, so the held blob is the raw publish envelope (an npm packument, a NuGet/PyPI
            // multipart), not a served artifact. Linking it would materialise no version (no tarball, not installable)
            // and strand a phantom publish/ pointer. Replay the format's own handle from the stored context instead -
            // the same dispatch seam the accept path drives - so the version is actually laid out (tarball stored,
            // module views cross-published, metadata written). The descriptor is dropped AFTER the /quarantine pointer
            // clears below, so a crash mid-release re-reads it and re-replays (idempotent, content-addressed) rather
            // than falling through to link the raw envelope.
            replay(store, path, held.get(), dispatch.get());
        } else if (current.isEmpty() && linkable(publishPointer(inventory, path))) {
            // A publish-time (publish/-namespace) hold: the release pointer was never linked, so link it and record the
            // publish-time sidecar the gate skipped for a quarantined upload (retention/enforcement must see the
            // version). A blobs-namespace hold (npm/PyPI/NuGet/Cargo/RubyGems/Debian/Go) is deliberately NOT given a
            // publish/ pointer - those formats never use one; its served blobs resume purely by lifting the withhold
            // markers below, so synthesizing a pointer here would strand a phantom publish/ entry the format never
            // serves. That is now the WHOLE release for those formats however the hold arose: a retroactive
            // KEV/licence hold overlays a version that is already laid out, and a screen-time QUARANTINE is laid out by
            // the format itself behind the same withheld/<hash> marker - so this primitive has one blobs-namespace
            // release mechanism (lift the marker) rather than a second, format-specific replay beside it, and the
            // release stays a pure retraction-lift, which is what makes a retried release converge by construction.
            publication.link(path, held.get());
            inventory.record(path, Instant.now());
        }
        // A present pointer is left alone: equal hash means it is already right; a different hash is a corrected
        // republish that must not be rolled back to the quarantined bytes - lifting the hold is all that remains.
        // Lift the content-addressed marker so the released bytes serve again from the blobs namespace too - but only
        // when no OTHER coordinate sharing this hash is still held, or clearing it would un-withhold that byte-identical
        // sibling (the blobs-namespace serve gate keys withheld on the marker, not the per-path pointer). Routed through
        // the HoldClears owner: it re-runs the cross-alias guard after the clear and re-marks if a sibling was held in
        // the window (the #207 race, cross-alias form). The excluded set is this coordinate's own served paths, so its
        // own still-present /quarantine pointer (unpublished just below) never triggers a false re-mark.
        HoldClears.clearReleased(store, held.get(), releasingCoordinatePaths(inventory, path), "hold-release " + path,
                inventory.describe(path).orElse(ArtifactDescriptor.at(null, path)));
        publication.unpublish("/quarantine" + path);
        // The dispatch descriptor is consumed only now the hold pointer is gone, so a crash before this point re-runs
        // the replay rather than the raw-envelope fallback; a crash after it leaves at worst a stray descriptor (the
        // version is already materialised and serving), never a phantom pointer.
        QuarantineDispatch.discard(store, path);
        // The subject record goes the same way and for the same reason: it describes THIS hold, so it is
        // reclaimed by the lifecycle that ends the hold rather than by any sweep. Ordered after the pointer clear, so
        // a crash in the window leaves a record whose hold is gone - which costs a later reader one wasted point read
        // and can assert nothing - rather than a hold whose record is gone, which is the fail-open direction.
        HeldSubjects.forget(store, path);
        clearVersionWithholds(store, path);
        // The hold resolved: tell an integrator that reacted to the quarantine webhook. Best-effort and a no-op with
        // no sink installed, per EventSink.emit - so a notification hiccup never fails a release the store already
        // committed. The coordinate/version are carried when the path still resolves to one (a discarded blobs hold
        // may not), the path being the stable identifier the quarantine event carried.
        Optional<ArtifactDescriptor> released = inventory.describe(path);
        EventSink.emit(store, RepositoryEvent.release(
                released.map(ArtifactDescriptor::ecosystem).orElse(null),
                released.map(ArtifactDescriptor::coordinate).orElse(null),
                released.map(ArtifactDescriptor::version).orElse(null), path, Instant.now()));
        return held.get();
    }

    /** Lift the version-wide blobs-namespace withhold markers once the LAST held path of the version releases: a
     *  partial release (one path of a multi-path hold) keeps the version's blobs-namespace serving retracted, in
     *  step with the sibling {@code /quarantine} pointers that remain. Best-effort per the marker's read-side role:
     *  a path that maps to no coordinate simply has no version-wide markers to lift. */
    private static void clearVersionWithholds(ArtifactStore store, String path) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return;
        }
        if (HeldElsewhere.othersStillHeld(store, described.get(), path)) {
            return;
        }
        Set<String> ownPaths = new HashSet<>(ownPaths(inventory, described.get()));
        ownPaths.add(path);
        for (String hash : inventory.blobHashes(described.get().ecosystem(), described.get().coordinate(),
                described.get().version())) {
            // Same cross-alias guard as the release tail, routed through the HoldClears owner: a version-wide marker
            // whose hash a DIFFERENT coordinate still holds under its own /quarantine pointer stays standing, or lifting
            // it would un-withhold that sibling; and the owner re-marks if a sibling was held in the clear window.
            HoldClears.clearReleased(store, hash, ownPaths, "hold-release version-withhold", described.get());
        }
    }

    /**
     * Discard a quarantined path without releasing it: reap the quarantine log rows, findings document and
     * {@code holds/} records through the discovered observers, evict a retroactive hold's still-held release pointer,
     * and clear the hold. Returns {@code false} - touching nothing - when no live hold exists at {@code path}, so a
     * duplicate or stale discard never strips a served version's audit trail.
     */
    public static boolean discard(ArtifactStore store, String path) throws IOException {
        return discard(store, path, HoldReleaseObserver.discovered());
    }

    /** {@link #discard(ArtifactStore, String)} fanning out to {@code hooks} rather than to the discovered ones - the
     *  substitution seam. */
    public static boolean discard(ArtifactStore store, String path, Iterable<HoldReleaseObserver> hooks)
            throws IOException {
        Publication publication = new Publication(store);
        Optional<String> held = publication.blob("/quarantine" + path);
        if (held.isEmpty()) {
            return false;
        }
        // Resolve the coordinate for the resolution event BEFORE the reap below destroys the version's state; the
        // path stays the stable identifier if it no longer maps to a coordinate.
        Optional<ArtifactDescriptor> discarded = new StoreRepositoryInventory(store).describe(path);
        // (2), and refused HERE - before anything is mutated - for the reason refused the eviction. A
        // discard's destroy intent has two halves: clear the review handle, and stop the bytes serving. With the owning
        // format's module off the graph the second half silently does nothing (discardBlobs is a no-op for an ecosystem
        // no installed BlobLayout owns, and there is no publish/ pointer for a blobs-namespace version to unpublish)
        // while the first ran unconditionally - so the review handle went and the known-exploited bytes kept serving,
        // with nothing left to find them by. There is no "do the whole thing anyway" to choose: which keys the version
        // serves under is layout knowledge. What the durable subject record adds is the ability to tell the two silences
        // apart - a path that names no versioned artifact at all (a checksum, a raw upload: nothing to destroy, discard
        // proceeds exactly as before) from a path whose hold was placed on a real coordinate this deployment can no
        // longer place. The second is refused, loudly and reversibly: the hold stays exactly as it is, and reinstalling
        // the format module makes the discard exact.
        if (discarded.isEmpty()) {
            Optional<HeldSubjects.Subject> subject = HeldSubjects.read(store, path);
            if (subject.isPresent() && subject.get().versioned()) {
                throw new IOException("Discard of " + path + " is refused: the hold was placed on "
                        + subject.get().ecosystem() + " " + subject.get().coordinate() + ":" + subject.get().version()
                        + ", and no installed format claims that path any more - so this discard could clear the review "
                        + "handle but not stop the version serving, leaving the artifact downloadable with nothing "
                        + "left to review it by. The hold stays exactly as it is; install the format module again to "
                        + "discard it.");
            }
        }
        // Log rows and hold records first: a crash mid-discard leaves the /quarantine pointer - the review queue's
        // index - in place as the surface an operator retries from, instead of dangling record rows forever.
        new QuarantineLog(store).discarded(path);
        HoldReleaseObserver.discarded(store, path, hooks);
        // The discard counterpart of the release leg's orphan reap: a kind with no installed hook cannot drop its own
        // record, and a discarded version has no published/ sidecar for any sweep to ever reach, so the row would
        // dangle forever. Guarded exactly as the installed kinds' own onDiscarded guards it - the record is per
        // VERSION, so discarding one path of a multi-path hold must not strip the state the remaining held paths are
        // reviewed against; the last discard reaps it.
        if (discarded.isPresent() && discarded.get().coordinate() != null && discarded.get().version() != null
                && !HeldElsewhere.othersStillHeld(store, discarded.get(), path)) {
            HoldRecords.releaseOrphaned(store, path);
        }
        // A screen()-quarantined upload's stored dispatch context is thrown away with the hold: the upload is
        // discarded, so nothing will ever replay it and the descriptor would otherwise dangle forever.
        QuarantineDispatch.discard(store, path);
        // A retroactive hold overlays a live release pointer; the discard's destroy intent evicts it so the
        // known-exploited artifact does not resume serving - but only while it still points at the held bytes.
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Optional<String> current = publication.blob(path);
        if (current.isPresent() && current.get().equals(held.get())) {
            publication.unpublish(path);
        } else if (current.isEmpty()) {
            // A pure blobs-namespace retro hold has no publish/ release pointer to unpublish; the version serves from
            // blobs/ under the withhold markers. The destroy intent evicts the version's served blobs (delete the
            // format's own pointer keys, lift their markers) so it neither resumes serving nor is re-held next sweep -
            // the counterpart of unpublishing a publish/ pointer. Guarded on no other held path of the version, so
            // discarding one served path of a multi-path hold does not destroy the bytes the rest are reviewed against.
            Optional<ArtifactDescriptor> described = inventory.describe(path);
            if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null
                    && inventory.servesFromBlobs(described.get().ecosystem())
                    && !HeldElsewhere.othersStillHeld(store, described.get(), path)) {
                inventory.discardBlobs(described.get().ecosystem(), described.get().coordinate(),
                        described.get().version());
            }
        }
        publication.unpublish("/quarantine" + path);
        // The subject record is reclaimed with the hold it described, exactly as on the release leg.
        HeldSubjects.forget(store, path);
        // The hold resolved by destruction: the other terminal outcome a quarantine-webhook subscriber must hear
        // about. Best-effort, no-op with no sink installed - a notification hiccup never fails the discard.
        EventSink.emit(store, RepositoryEvent.discard(
                discarded.map(ArtifactDescriptor::ecosystem).orElse(null),
                discarded.map(ArtifactDescriptor::coordinate).orElse(null),
                discarded.map(ArtifactDescriptor::version).orElse(null), path, Instant.now()));
        return true;
    }

    /**
     * The {@code publish/} release pointer this hold's release must link - the question, asked as the
     * three-valued thing it is rather than as a yes/no about one namespace with a third enum constant bolted on.
     *
     * <ul>
     *   <li>{@link Known.Present} carries the request path to link: a publish-time, {@code publish/}-namespace hold
     *       (Maven, raw, the jenesis layout), whose release links the release pointer and records the publish-time
     *       facts the gate skipped for a quarantined upload.</li>
     *   <li>{@link Known.Absent} is a genuine, observed "there is no pointer to link": a pure blobs-namespace hold
     *       (npm/PyPI/NuGet/Cargo/RubyGems/Debian/Go), whose release lifts the content-addressed marker and NOTHING
     *       else - those formats never serve through a {@code publish/} pointer, so synthesizing one would strand an
     *       entry retention (which reverse-maps only through a format's {@code ArtifactLayout}) never reclaims.</li>
     *   <li>{@link Known.Unknown} is no installed format claiming the path at all, so the question was never put. Not
     *       a synonym for either answer: the path may well be a blobs-namespace one whose module is simply off the
     *       graph, and the release is refused rather than guessed.</li>
     * </ul>
     *
     * <p>The caller has already established the path carries no live {@code publish/} pointer and no stored dispatch
     * context, so this is what decides whether the release synthesizes one.
     */
    private static Known<String> publishPointer(StoreRepositoryInventory inventory, String path) {
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty()) {
            return Known.uninstalled("no installed format claims " + path + ", so which serving namespace the hold "
                    + "belongs to - and therefore whether its release links a publish/ pointer - was never asked");
        }
        // A claiming format that describes the path WITHOUT a coordinate (a checksum, generated metadata) has answered:
        // such a path is a publish/-namespace one and its release links the pointer, exactly as before.
        return described.get().coordinate() != null && described.get().version() != null
                && inventory.servesFromBlobs(described.get().ecosystem())
                ? Known.absent()
                : Known.known(path);
    }

    /** Whether {@link #publishPointer} could not be answered at all - the refusal arm, spelled out so the other two
     *  can never absorb it. */
    private static boolean unplaceable(Known<String> pointer) {
        return switch (pointer) {
            case Known.Unknown<String> _ -> true;
            case Known.Determined<String> _ -> false;
        };
    }

    /** Whether the release links a {@code publish/} pointer: only an answered <em>yes</em> does. An unanswerable path
     *  never reaches here (the refusal above precedes it), and a blobs-namespace hold answers no. */
    private static boolean linkable(Known<String> pointer) {
        return switch (pointer) {
            case Known.Present<String> _ -> true;
            case Known.Absent<String> _ -> false;
            case Known.Unknown<String> _ -> false;
        };
    }

    /**
     * Replay a screen-quarantined upload's format dispatch from its stored context, so the release materialises the
     * version the same way an accepted upload does. A hosted-deploy dispatch (a {@code PUT}/{@code POST}) looks the
     * claiming {@link RepositoryFormat} up by the recorded name and drives its own {@link RepositoryFormat#handle handle}
     * over a synthetic exchange that restreams the held blob at the recorded request path with the recorded method and
     * headers - the identical seam the deploy edge drives on ACCEPT, never a forked publish path. An
     * {@code IMPORT} dispatch (recorded by the import edge when a migrated asset screened to QUARANTINE) instead replays
     * the matching {@link RepositoryImporter#importArtifact} from the stored blob (see {@link #replayImport}), the
     * import analog of the accept path's restream-into-importer. An {@link QuarantineDispatch#OCI OCI} dispatch (recorded
     * at the OCI manifest choke point's QUARANTINE leg) replays neither: an OCI push serves by digest straight from
     * {@code blobs/<hex>} under the native {@code withheld/<hex>} marker, never through a {@code publish/} pointer, so
     * its release completes the deferred OCI layout the QUARANTINE leg skipped (see {@link #releaseOci}) - the
     * {@code oci/types/<hex>} sidecar and the tag pointer - rather than re-driving a format. The screen is suppressed for
     * the deploy/import replay ({@link ComplianceScreen#replaying}) so a format whose own handle re-publishes through
     * {@code Publication} (Maven's does) does not re-quarantine the just-released bytes; the OCI completion writes no
     * body through {@code Publication.screen}, so it needs no such suppression. When the format/importer is no longer
     * installed the deploy/import release is <b>refused</b> ({@link #refusedReplay}) rather than degraded to linking
     * the stored blob: the envelope is not the artifact, so a link materialises nothing and strands a pointer.
     */
    private static void replay(ArtifactStore store, String path, String hash, QuarantineDispatch dispatch)
            throws IOException {
        if (QuarantineDispatch.OCI.equals(dispatch.method())) {
            releaseOci(store, path, hash, dispatch);
            return;
        }
        if (QuarantineDispatch.IMPORT.equals(dispatch.method())) {
            replayImport(store, path, hash, dispatch);
            return;
        }
        Optional<RepositoryFormat> format = RepositoryFormat.installed(dispatch.format());
        if (format.isEmpty()) {
            throw refusedReplay(store, path, "no installed format is named '" + dispatch.format() + "'");
        }
        try (ReplayExchange exchange = new ReplayExchange(store, path, hash, dispatch)) {
            ComplianceScreen.replaying(() -> format.get().handle(exchange, store));
        }
    }

    /**
     * The refusal a screen-quarantined release raises when the module that would materialise it is gone ((3)),
     * naming the held coordinate from the durable {@link HeldSubjects} record where one was written.
     *
     * <p>The replay legs used to degrade to {@code Publication.link(path, hash)} - "so the hold still resolves" - and
     * that was wrong twice over. The held blob of an envelope-bodied format is the publish <em>envelope</em> (an npm
     * packument, a NuGet/PyPI multipart), so linking it materialises no version at all: nothing is installable and the
     * release reports success. And for a blobs-namespace format it strands the phantom {@code publish/} pointer 
     * closed on the sibling branch - an entry no format serves and retention's layout reverse-mapping never reclaims.
     * The release surface is a human's decision about a review item, so the honest answer is the one already
     * settled for the other branch: keep the hold, say why, and let reinstalling the module make the release exact.
     * Nothing has been mutated at this point beyond the pre-commit override promotion, which a re-run converges on.
     */
    private static IOException refusedReplay(ArtifactStore store, String path, String missing) throws IOException {
        Optional<HeldSubjects.Subject> subject = HeldSubjects.read(store, path);
        String held = subject.filter(HeldSubjects.Subject::versioned)
                .map(placed -> " The hold is on " + placed.ecosystem() + " " + placed.coordinate() + ":"
                        + placed.version() + ".")
                .orElse("");
        return new IOException("Release of " + path + " is refused: it was quarantined before its format laid the "
                + "upload out, and " + missing + " to lay it out now - so this release could only link the stored "
                + "publish envelope, which materialises no installable version and strands a pointer nothing serves."
                + held + " The hold stays exactly as it is; install the module again to release it.");
    }

    /**
     * Replay an import-quarantined asset into its layout-only importer from the stored blob, so a released migrated
     * asset materialises the same way an ACCEPT import laid it out. The import edge recorded the target-layout ecosystem
     * as the dispatch {@code format} (method {@code IMPORT}) and the walk's SOURCE path in the context map: the owning
     * importer is the one whose {@link RepositoryImporter#importTarget importTarget} of that source path resolves to that
     * ecosystem - the same describe-then-lay-out contract the walk selected it by, so exactly one importer matches. Its
     * {@code importArtifact} is driven over the SOURCE path (not the target/held path - an importer keys its layout on
     * the source path, e.g. Maven's {@code importArtifact} prepends {@code /maven/}, so the held target path would
     * double-prefix) with the blob restreamed from the CAS, the identical seam the accept path drives, never a forked
     * layout. When no such importer is installed the release is <b>refused</b> ({@link #refusedReplay}) rather than
     * degraded to a link. The screen is suppressed during the replay, mirroring the deploy path, so a layout that touches
     * {@code Publication} cannot re-quarantine the just-released bytes.
     */
    private static void replayImport(ArtifactStore store, String path, String hash, QuarantineDispatch dispatch)
            throws IOException {
        String source = dispatch.headers().getOrDefault(QuarantineDispatch.IMPORT_SOURCE_PATH, path);
        // WSPI.2 (c): the importer is a format capability now, not a second discovered service - discover the
        // formats and filter by instanceof RepositoryImporter (mirroring RepositoryImport), so the
        // owning importer is the format that both carries the capability and describes this source path.
        Optional<RepositoryImporter> importer = RepositoryFormat.installed().stream()
                .filter(format -> format instanceof RepositoryImporter)
                .map(format -> (RepositoryImporter) format)
                .filter(candidate -> candidate.importTarget(source)
                        .map(descriptor -> descriptor.ecosystem().equals(dispatch.format())).orElse(false))
                .findFirst();
        if (importer.isEmpty()) {
            throw refusedReplay(store, path, "no installed importer lays '" + source + "' out as "
                    + dispatch.format());
        }
        try (InputStream blob = store.open("blobs/" + hash)) {
            ComplianceScreen.replaying(() -> importer.get().importArtifact(source, blob, store));
        }
    }

    /**
     * Complete the deferred OCI layout for a released manifest hold, so a held image becomes pullable by tag AND digest.
     * The OCI manifest choke point stored the manifest content-addressed at the serving key {@code blobs/<hash>} but, on
     * QUARANTINE, laid out none of OCI's native metadata and set the {@code withheld/<hash>} marker instead; the shared
     * release tail lifts that marker, and this writes the two things the accept path would have: the
     * {@code oci/types/<hash>} media-type sidecar the serve path returns verbatim, and - for a tag reference (never a
     * {@code sha256:} digest reference, which already resolves by digest) - the {@code oci/<name>/tags/<tag>} pointer at
     * {@code sha256:<hash>} <em>only when that tag is still absent</em> (see {@link #linkOciTag}), so a pull by tag
     * resolves without rolling back a clean manifest re-pushed to the same tag during the hold. The image name and tag
     * are read back off the recorded request
     * path ({@code /v2/<name>/manifests/<reference>}); the media type rides the dispatch context's {@code Content-Type},
     * falling back to the OCI image-manifest type exactly as the accept path does. A recorded path that is not the OCI
     * manifest shape degrades to linking the stored blob so the hold still resolves rather than being silently dropped.
     * That fallback is kept HERE and only here: it is a statement about the recorded path's <em>shape</em>, which no
     * module absence can change, and the manifest blob at a non-manifest path is the artifact rather than an envelope -
     * unlike the deploy/import replays, whose absent-module legs are now refused rather than degraded.
     */
    private static void releaseOci(ArtifactStore store, String path, String hash, QuarantineDispatch dispatch)
            throws IOException {
        int manifests = path.indexOf("/manifests/");
        if (!path.startsWith("/v2/") || manifests < "/v2/".length()) {
            new Publication(store).link(path, hash);
            new StoreRepositoryInventory(store).record(path, Instant.now());
            return;
        }
        String name = path.substring("/v2/".length(), manifests);
        String reference = path.substring(manifests + "/manifests/".length());
        String mediaType = dispatch.headers().getOrDefault("Content-Type", OCI_MANIFEST);
        store.write("oci/types/" + hash, new ByteArrayInputStream(mediaType.getBytes(StandardCharsets.UTF_8)));
        // A digest reference already pulls by digest once the marker lifts; only a tag reference needs the pointer laid
        // out so the released image also pulls by tag.
        if (!reference.startsWith("sha256:")) {
            linkOciTag(store, "oci/" + name + "/tags/" + reference, "sha256:" + hash);
        }
    }

    /** Point an OCI tag at a released manifest digest <em>only when the tag is absent</em> - the create-only mirror of
     *  the publish-path release guard ({@link #release}), never a last-writer-wins re-link. A tag that is already
     *  present is left untouched: an equal pointer means the release is already right (a crash-rerun re-drives this and
     *  converges), and a <em>different</em> pointer is a clean manifest re-pushed to this tag <em>during</em> the hold -
     *  rolling it back to the quarantined digest would silently un-do a corrected re-push. The released manifest still
     *  becomes pullable BY DIGEST because the shared release tail lifts its {@code withheld/<hex>} marker; only the tag
     *  is not stolen back. Absence is claimed with an expected-absent compare-and-set ({@code writeVersioned(key, …,
     *  null)}); a lost race (a concurrent push linked the tag first) lands the same leave-alone branch on the re-read. */
    private static void linkOciTag(ArtifactStore store, String key, String digest) throws IOException {
        byte[] value = digest.getBytes(StandardCharsets.UTF_8);
        Optional<ArtifactStore.Versioned> present = store.readVersioned(key);
        if (present.isPresent()) {
            leaveOciTag(key, digest, present.get());
            return;
        }
        if (!store.writeVersioned(key, value, null)) {
            // A concurrent linker won the create race; re-read and leave whatever landed alone (never roll it back).
            store.readVersioned(key).ifPresent(raced -> leaveOciTag(key, digest, raced));
        }
    }

    /** Leave a present OCI tag pointer standing on release; log at INFO only when it has MOVED away from the released
     *  digest (a corrected re-push during the hold), the operator's breadcrumb that the released manifest stays pullable
     *  by digest while the tag now resolves the newer bytes. An equal pointer is the idempotent crash-rerun case - silent. */
    private static void leaveOciTag(String key, String digest, ArtifactStore.Versioned present) {
        String pointer = new String(present.content(), StandardCharsets.UTF_8).trim();
        if (!pointer.equals(digest)) {
            LOGGER.info("OCI tag {} moved during hold (now {} not {}); released manifest stays pullable by digest",
                    key, pointer, digest);
        }
    }

    /** A synthetic {@link FormatExchange} that replays a quarantined upload into its format on release: the recorded
     *  publish method at the held request path, its body restreamed from the content-addressed blob (opened lazily,
     *  closed with the exchange), the framing headers the dispatch recorded, and the format's response discarded - the
     *  release cares that the version materialises, not what the publish would have answered. Carries no explode header
     *  (a replay never re-batches) and no query parameters (a hosted publish reads none). Mirrors the deploy
     *  controller's own PublishExchange and the batch ingestion's CapturingExchange. */
    private static final class ReplayExchange implements FormatExchange, Closeable {

        private final ArtifactStore store;
        private final String path;
        private final String hash;
        private final QuarantineDispatch dispatch;
        private InputStream stream;

        private ReplayExchange(ArtifactStore store, String path, String hash, QuarantineDispatch dispatch) {
            this.store = store;
            this.path = path;
            this.hash = hash;
            this.dispatch = dispatch;
        }

        @Override
        public String method() {
            return dispatch.method();
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            for (Map.Entry<String, String> header : dispatch.headers().entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    return header.getValue();   // HTTP header names are case-insensitive
                }
            }
            return null;
        }

        @Override
        public InputStream requestStream() throws IOException {
            if (stream == null) {
                stream = store.open("blobs/" + hash);
            }
            return stream;
        }

        @Override
        public void setResponseHeader(String name, String value) {
            // the replay discards the publish response; the release outcome is the materialised version
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    /** The releasing coordinate/version's own served paths (plus {@code path} itself), the excluded set the cross-alias
     *  guard reasons over at a release site - the same set {@link HeldElsewhere#othersStillHeld} treats as "this coordinate's own".
     *  A path that maps to no coordinate contributes only itself, so a hold H is never treated as its own alias.
     *
     *  <p>A version whose paths cannot be enumerated at all contributes only {@code path} too, and the Unknown arm is
     *  written rather than collapsed because this is a release site: a NARROWER exclusion set can only make the
     *  cross-alias guard consider more review pointers and so keep more markers standing - it can never lift one -
     *  which is the direction a release may err in. */
    private static Set<String> releasingCoordinatePaths(StoreRepositoryInventory inventory, String path)
            throws IOException {
        Set<String> own = new HashSet<>();
        own.add(path);
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
            own.addAll(ownPaths(inventory, described.get()));
        }
        return own;
    }

    /** The served paths of an artifact's version for an exclusion set: the enumerated ones, or none at all when
     *  nothing installed can enumerate them - see {@link #releasingCoordinatePaths} for why the unaskable case is the
     *  safe direction here and only here. */
    private static List<String> ownPaths(StoreRepositoryInventory inventory, ArtifactDescriptor artifact)
            throws IOException {
        return switch (inventory.knownPaths(artifact.ecosystem(), artifact.coordinate(), artifact.version())) {
            case Known.Present<List<String>> present -> present.value();
            case Known.Absent<List<String>> _ -> List.of();
            case Known.Unknown<List<String>> _ -> List.of();
        };
    }

    /**
     * Lift the hold on one cross-published alias of a path being released - the same artifact under a second name.
     *
     * <p>It is deliberately NOT the whole of {@link #release}. An alias is not its own review item: it gets no
     * release event (one artifact was reviewed, so one release is reported), no pointer link (its view pointer was
     * never unlinked - a held alias serves nothing because of its /quarantine pointer and the content marker, both
     * of which this clears) and no dispatch replay (the format laid the view out at publish time, from the origin's
     * bytes). What it does need is everything that would otherwise let a sweep put the hold straight back: the
     * override the enforcement passes read, and the orphaned records of a hold kind no longer installed.
     *
     * <p>Best-effort per alias and contained: a record naming a path that no longer exists, or an alias whose own
     * clear fails, must not fail a release whose subject has already been decided by a human. What it costs is a
     * marker left standing - the state before this existed - rather than a disclosure.
     */
    private static void releaseAlias(ArtifactStore store, Publication publication, String released,
                                     String alias, Iterable<HoldReleaseObserver> hooks) {
        try {
            if (publication.blob("/quarantine" + alias).isEmpty()) {
                return;   // not held: the common case for a view whose hold was only ever the content marker
            }
            HoldReleaseObserver.released(store, alias, hooks);
            HoldRecords.releaseOrphaned(store, alias);
            publication.unpublish("/quarantine" + alias);
            QuarantineDispatch.discard(store, alias);
            HeldSubjects.forget(store, alias);
        } catch (IOException | RuntimeException contained) {
            LOGGER.warn("Releasing {} left its cross-published alias {} held; the artifact serves under the released "
                    + "name and the alias keeps its hold until a re-run of the release clears it.", released, alias,
                    contained);
        }
    }

}
