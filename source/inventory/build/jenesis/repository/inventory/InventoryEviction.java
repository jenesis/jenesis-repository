package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.format.ArtifactLayout;

/**
 * The eviction subsystem behind {@link StoreRepositoryInventory}: the destroy leg that unpublishes every pointer
 * a version occupies and reaps its derived per-version rows. {@link #evict} unpublishes the {@code publish/} pointers a
 * Publication-namespace format's {@link ArtifactLayout} resolves (each removal observed with the coordinate this
 * eviction already resolved) and the blob pointers a {@link BlobLayout blobs-namespace} format holds, then deletes the
 * version's consolidated document, its {@code pinned/} marker and every derived row, folding the evicted member out of
 * the {@link InventoryIdentity} rollup; {@link #discardBlobs} is the blobs-namespace-only destroy leg a discard needs.
 * The facade owns the seam - {@code evict}/{@code discardBlobs} delegate here - and this class shares the facade's
 * membership check, last-version query and its store-key/codec helpers rather than duplicating them.
 */
final class InventoryEviction {

    private static final System.Logger LOGGER = System.getLogger(InventoryEviction.class.getName());

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final Publication publication;
    private final InventoryIdentity identity;

    InventoryEviction(StoreRepositoryInventory inventory, ArtifactStore store, Publication publication,
                      InventoryIdentity identity) {
        this.inventory = inventory;
        this.store = store;
        this.publication = publication;
        this.identity = identity;
    }

    /** Evict a blobs-namespace version's served content on discard - see
     *  {@link StoreRepositoryInventory#discardBlobs}. A no-op for an ecosystem with no installed {@link BlobLayout}.
     *
     *  <p>A discard is a DESTROY: it deletes the format's own pointer keys so this coordinate never serves the bytes
     *  again regardless of any marker. It deliberately does NOT lift the content-addressed {@code withheld/<hash>}
     *  marker - release ({@code HoldLifecycle.release} / {@code ReanalysisTask.releaseHeld}) is the only marker-lifting
     *  path. The marker is keyed by content hash, so one marker withholds the bytes wherever they are served: a
     *  BYTE-IDENTICAL sibling coordinate that still carries its own standing hold shares this hash, and clearing the
     *  marker here would un-withhold that sibling even though its {@code /quarantine} pointer still holds it (the
     *  blobs-namespace serve gate keys "withheld" on the marker, not the per-path pointer). Leaving the marker keeps
     *  every such sibling correctly withheld; for content that was in fact uniquely referenced by this now-destroyed
     *  pointer the marker is inert (nothing resolves to the reclaimed blob) - exactly what the companion {@link #evict}
     *  already tolerates, deleting its blob pointers without lifting the marker. */
    void discardBlobs(String ecosystem, String coordinate, String version) throws IOException {
        for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(ecosystem)) {
            for (String key : blobLayout.blobKeys(coordinate, version, store)) {
                Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
                if (pointer.isEmpty()) {
                    continue;
                }
                store.delete(key);
            }
        }
    }

    /**
     * Whether this deployment can enumerate the pointer keys a coordinate version occupies - the precondition BOTH
     * destroy legs must satisfy before they touch anything.
     *
     * <p>Exactly the line the reconcile sweep's liveness draws ({@code InventoryReconciler.liveness}): a
     * {@code publish/}-namespace {@link ArtifactLayout} that resolves at least one prefix for the coordinate, or a
     * {@link BlobLayout blobs-namespace} format that owns its ecosystem, is a format that was <em>asked</em>; an absent
     * module - or a layout that resolves no prefix - leaves the question unasked, and unasked must never share an
     * outcome with "there are no pointers".
     *
     * <p>Which keys a version's pointers live under is layout knowledge and nothing else in this store records it, so
     * without it an eviction can delete every derived row and <em>silently</em> leave the pointers standing: the
     * artifact keeps serving with its publish facts, pin, licenses, meta document and override markers destroyed. That
     * is the one outcome worse than not evicting at all, because it is invisible and leaves the store inconsistent.
     */
    private boolean pointersEnumerable(String ecosystem, String coordinate, String version) throws IOException {
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
            if (!layout.paths(coordinate, version, store).isEmpty()) {
                return true;
            }
        }
        return !StoreRepositoryInventory.blobLayoutsFor(ecosystem).isEmpty();
    }

    /** The named refusal both destroy legs raise, saying what could not be asked and what the operator can do about it -
     *  never a reason to proceed. A destroy is the irreversible act, decided per version, so "I could not tell
     *  which pointers this version occupies" is answered by touching nothing at all. */
    private static IOException refusal(String verb, String ecosystem, String coordinate, String version) {
        return new IOException(verb + " of " + ecosystem + " " + coordinate + ":" + version + " is refused: no "
                + "installed format can place that ecosystem's coordinates, so the pointer keys the version occupies "
                + "cannot be enumerated and the destroy would delete its publish facts, pin, licenses, meta document "
                + "and override markers while leaving it serving; install the format module again, or purge the "
                + "ecosystem's data explicitly, before evicting");
    }

    void evict(Release release) throws IOException {
        evict(release, BlobLayout::blobKeys);
    }

    /**
     * Remove a version a client asked to remove: the eviction, taking the keys the layout gives up for a client's
     * removal ({@link BlobLayout#removalKeys}) rather than for a retention eviction, which differ only for a
     * version that holds no pointer of its own.
     */
    void remove(Release release) throws IOException {
        evict(release, BlobLayout::removalKeys);
    }

    /** The keys a blobs-namespace layout gives up for one coordinate version. */
    @FunctionalInterface
    private interface Keys {
        List<String> of(BlobLayout layout, String coordinate, String version, ArtifactStore store) throws IOException;
    }

    private void evict(Release release, Keys keys) throws IOException {
        // Nothing is destroyed unless the pointers can be found: the layout-driven unpublish legs below no-op for an
        // absent format, and every delete under them would still run unconditionally.
        if (!pointersEnumerable(release.ecosystem(), release.coordinate(), release.version())) {
            throw refusal("eviction", release.ecosystem(), release.coordinate(), release.version());
        }
        NewestFirst.RELEASES.forget(store, release.ecosystem(), release.coordinate(), release.version(),
                release.published());
        // A release that was first held as a copy cached from an upstream still has that copy's newest-first row, and
        // the document that would let a later pass find it is deleted below.
        NewestFirst.CACHED.forget(store, release.ecosystem(), release.coordinate(), release.version(),
                inventory.cachedAt(release.ecosystem(), release.coordinate(), release.version())
                        .map(CachedSection.Facts::at).orElse(null));
        AdvisedSection.forget(store, inventory.holding(release.ecosystem(), release.coordinate(), release.version())
                .map(StoreRepositoryInventory.Holding::advised), release.ecosystem(), release.coordinate(),
                release.version());
        PackageNameIndex.forget(store, release.ecosystem(), release.coordinate(), release.version());
        Mailbox.CHANGED.forget(store, release.ecosystem(), release.coordinate(), release.version());
        // Capture the version's rollup contribution before its document is removed, so the maintained identity can be
        // folded out once the eviction completes (the whole-repository SBOM / NOTICE ETag then revalidates). Read here,
        // ahead of the deletes, because the document carrying both is gone by the end.
        boolean published = foldsOut(inventory.membership(
                release.ecosystem(), release.coordinate(), release.version()));
        // The evicted member's license contribution, read through LicenseInventory as its canonical declared-set
        // fingerprint, so the fold-out here and the fold-in at publish agree on the member.
        Optional<byte[]> evictedLicenses = LicenseSection.fingerprintOf(
                new LicenseInventory(store).read(release.ecosystem(), release.coordinate(), release.version()));
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(release.ecosystem())) {
            for (String prefix : layout.paths(release.coordinate(), release.version(), store)) {
                for (String child : store.list("publish" + prefix)) {
                    // The layout-enriched removal: this eviction already resolved the release's coordinate, so the
                    // observers' onDeleted carries it (the primitive completes the blob identity from the
                    // pointer) instead of the bare path the neutral unpublish(String) would report.
                    publication.unpublish(new ArtifactDescriptor(release.ecosystem(), release.coordinate(),
                            release.version(), prefix + "/" + child, null, release.prerelease(), null, -1L));
                    // The cross-publish alias record goes with the pointer it describes. A row says "this served path
                    // is that one under a second name", so once the path is gone there is nothing for it to relate -
                    // and a stale row is not merely untidy: a later release that resolved a group through it would
                    // act on a name this version no longer owns.
                    ServedAliases.forget(store, prefix + "/" + child);
                }
            }
        }
        // A blobs-namespace format (npm, PyPI, Cargo, ...) keeps its pointers under its own key roots, not under
        // publish/, so its eviction removes the format's own pointer keys for this version; the now-unreferenced
        // content blob is reclaimed by the discovered garbage collector, exactly as for a Maven pointer. The free
        // Publication never sees these deletes, so each is notified explicitly - once per deleted pointer, the
        // descriptor's path the raw store key (the walk's convention for a pointer outside publish/) and its blob
        // identity read off the pointer before the delete - so npm/PyPI/Cargo removals are observed like any other.
        for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(release.ecosystem())) {
            for (String key : keys.of(blobLayout, release.coordinate(), release.version(), store)) {
                Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
                if (pointer.isPresent()) {
                    store.delete(key);
                    String named = ServableNames.hash(pointer.get().content());
                    ArtifactDescriptor removed = new ArtifactDescriptor(release.ecosystem(), release.coordinate(),
                            release.version(), key, null, release.prerelease(),
                            StoreRepositoryInventory.hash(named) ? named : null, -1L);
                    publication.deleted(removed);
                }
            }
        }
        // The version's derived per-coordinate rows go with it, or they would dangle forever: every hold-override
        // marker is reaped by kind at the end of this method.
        // The pin goes with the version too: retention itself never evicts a pinned release, so reaching here
        // pinned means a direct operator eviction - leaving the row would show a phantom pin in the console and,
        // worse, silently auto-pin a later republish of the same version with no human decision behind it.
        deleteIfPresent(StoreRepositoryInventory.pinnedKey(
                release.ecosystem(), release.coordinate(), release.version()));
        // What a plug-in keeps outside the document, naming the version, is its to take back while the document can
        // still say what it named.
        tellObservers(release);
        // The version's consolidated metadata document goes with the artifact it describes: it carries the
        // publish facts, the downloads and the licenses, so evicting the version removes the whole per-version
        // document.
        boolean versionDocGone = deleteIfPresent(
                MetadataKey.version(release.ecosystem(), release.coordinate(), release.version()));
        // The vulnerability rank index no-ops its rebuild while a freshness stamp only a SCAN moves has not moved; an
        // eviction is not a scan, so without a signal an evicted version's line would linger in the ranked report until
        // the next scan (a whole scan interval). Bump the findings eviction epoch - a dirty signal the rank index folds
        // into its rebuild stamp, NOT the scan stamp (which would misreport the report's as-of instant and thrash the
        // eventually-consistent read) - when the version's document, which carries its findings section, actually went.
        // The line then drops on the next rank-index pass; a version that never had a document neither deletes nor
        // bumps.
        if (versionDocGone) {
            Findings.evictions(store).bump();
        }
        // The maintainer-health record is a per-COORDINATE fact (version-independent), so unlike the per-version
        // findings it is reclaimed only when the coordinate's LAST published version goes - which an empty published
        // set for the coordinate says, the version's document having been deleted just above. Health lives in the
        // @coordinate metadata document's health section, so the last-version eviction deletes that document. A record
        // stranded by a concurrent last-version eviction race is a bounded orphan the operator purge / orphan sweep
        // reaps, never silently swept.
        if (!inventory.anyPublishedVersion(release.ecosystem(), release.coordinate())) {
            // The last version went, so the coordinate's per-coordinate health is reclaimed - and, exactly as for the
            // findings above, the health rank index would otherwise keep serving the departed coordinate until the next
            // scan moved its freshness stamp. Bump the health eviction epoch (the dirty signal the health rank index
            // folds into its rebuild stamp, never the scan stamp) when the @coordinate document actually went, so the
            // weakest-first panel drops it on the next rank-index pass.
            if (deleteIfPresent(MetadataKey.coordinate(release.ecosystem(), release.coordinate()))) {
                HealthLedger.evictions(store).bump();
            }
        }
        // Every kind's hold-override marker goes with the version (overrides/kev, overrides/license, any other
        // kind): an override records a human's clearance of a hold for THIS stored
        // version, so if the version is ever re-published it is re-screened and re-reviewed, the conservative
        // direction. Composed through OverrideRecords, the one owner of that key space - a reaper spelling the key
        // its own way would silently STRAND a marker written in another spelling, which would then suppress the
        // re-screen of a later re-publish. The kind index is enumerated rather than hard-coded so an
        // uninstalled kind's markers are reaped too: absence must not strand a row any more than it may delete one.
        for (String kind : OverrideRecords.kinds(store)) {
            deleteIfPresent(OverrideRecords.key(kind, release.ecosystem(), release.coordinate(), release.version()));
        }
        // The hold-subject records go with the version too: a row says "the hold at this request path is a hold
        // on THIS coordinate version", so once the version is destroyed there is nothing left for it to describe. This
        // is the second of the two ways a row is reclaimed - the first being the hold's own end (release, discard,
        // auto-release, an accepted re-publish superseding it) - and between them a row can outlive neither its hold
        // nor its artifact. Neither is a sweep: this runs on an eviction that has already proved it can place the
        // version's pointers (the refusal above), so no row is ever removed because a module is absent.
        HeldSubjects.forget(store, release.ecosystem(), release.coordinate(), release.version());
        // Whatever else a plug-in keeps per version lives in a section of the per-version meta document deleted just
        // above, so that one delete is its reclamation.
        if (published) {
            identity.foldOut(InventoryIdentity.member(
                    release.ecosystem(), release.coordinate(), release.version(), evictedLicenses), release.published());
        }
    }

    /** The sections a cache-reclaim retains when it discards a re-heatable fallback blob: the
     *  {@code origin} trail ("where the bytes came from") and its sibling {@code verdict} record ("what the screen
     *  decided about digest D"). The {@code verdict} tag is owned by the gateway's {@code VerdictSection}; it is named
     *  here by its stable wire tag, since the inventory module does not depend on the gateway. */
    private static final Set<String> RETAINED_ON_RECLAIM = Set.of(OriginSection.TAG, "verdict");

    /**
     * Reclaim a <em>re-heatable cached fallback</em> blob under quota/disk pressure:
     * discard the bytes (unpublish every pointer the version occupies so the now-unreferenced blob is garbage-collected)
     * but <b>retain the {@code origin} and {@code verdict} sections</b> of the meta document - the "durable records
     * beside transient bytes" spine, so audit survives the eviction and a pull-through can re-heat the entry. The
     * decision is made off the durable {@code origin} record: a version carrying a {@code local-upload} row is
     * system-of-record and is <b>never</b> cache-evicted (returns {@code false}, nothing touched); a version whose only
     * origin is one or more {@code fallback} rows is re-heatable and is reclaimed (returns {@code true}). A version with
     * no origin record is not a recognised cache entry and is left untouched (returns {@code false}) - a quota sweep
     * never blindly reclaims a blob it cannot classify.
     */
    boolean reclaimFallbackCache(String ecosystem, String coordinate, String version) throws IOException {
        String docKey = MetadataKey.version(ecosystem, coordinate, version);
        Optional<ArtifactStore.Versioned> currentDoc = store.readVersioned(docKey);
        if (currentDoc.isEmpty()) {
            return false;                       // no meta doc - not a recognised cache entry
        }
        build.jenesis.repository.metadata.MetadataDocument document =
                build.jenesis.repository.metadata.MetadataDocument.read(currentDoc.get().content());
        Optional<build.jenesis.repository.metadata.Section> origin = document.section(OriginSection.TAG);
        if (OriginSection.hasLocalUpload(origin)) {
            return false;                       // system-of-record: a local-upload blob is never cache-evicted
        }
        if (!OriginSection.reheatableFallbackOnly(origin)) {
            return false;                       // nothing re-heatable recorded - do not reclaim a blob we cannot classify
        }
        // The same refusal the eviction raises, and deliberately NOT a fourth false. This method's boolean
        // already carries three distinct "I will not touch this" answers, and every one of them is a decision read off
        // a durable record; a quota sweep consumes false as "try the next candidate", so folding "I could not tell
        // which pointers this version occupies" into it would make an unplaceable ecosystem's entries silently and
        // permanently unreclaimable. Worse, without it the trim below would run anyway: the meta document would lose
        // its published/licenses/findings sections and this would return true - "bytes reclaimed" - while the
        // format's pointers, and the blob they name, still stood.
        if (!pointersEnumerable(ecosystem, coordinate, version)) {
            throw refusal("cache reclamation", ecosystem, coordinate, version);
        }
        // The two things this destroy needs off the version's durable publish facts, read once: whether it is a
        // published member (the identity fold-out below) and whether it is a prerelease (the flag every observer of
        // the pointer removals is handed). They are different questions with different answers: handing the
        // membership one to unpublishPointers as its prerelease argument would tell every PublicationObserver of a
        // cache reclaim that every published version was a prerelease and every unpublished one was not. An evict
        // has the Release to read the flag off; this path has only the recorded facts, so it reads them.
        Known<PublishedSection.Facts> membership = inventory.membership(ecosystem, coordinate, version);
        boolean published = foldsOut(membership);
        boolean prerelease = membership instanceof Known.Present<PublishedSection.Facts> present
                && present.value().prerelease();
        Optional<byte[]> evictedLicenses = LicenseSection.fingerprintOf(
                new LicenseInventory(store).read(ecosystem, coordinate, version));
        // Discard the bytes: unpublish every pointer the version occupies (the blob GCs once unreferenced), exactly the
        // pointer-removal an evict does - but the meta document is trimmed, not deleted, below.
        unpublishPointers(ecosystem, coordinate, version, prerelease);
        // Retain only origin + verdict; drop every served-fact section (published/licenses/findings/...) that goes with
        // the discarded bytes, so the version stops being a served member while its audit trail survives. Decided over
        // the document as it stands at the write, and retried through Retries: a peer's write in between - a download
        // counter flushing, a scan recording a finding - is a lost compare-and-set, and a trim that dropped it would
        // leave the document saying the version is published after its pointers are gone.
        Retries.decide(store, docKey, current -> {
            if (current.isEmpty()) {
                return Retries.Verdict.keep(null);
            }
            build.jenesis.repository.metadata.MetadataDocument now =
                    build.jenesis.repository.metadata.MetadataDocument.read(current.get().content());
            SequencedMap<String, build.jenesis.repository.metadata.SectionMutation> removals = new LinkedHashMap<>();
            for (String tag : now.tags()) {
                if (!RETAINED_ON_RECLAIM.contains(tag)) {
                    removals.put(tag, _ -> null);   // a mutation returning null removes the section
                }
            }
            return removals.isEmpty() ? Retries.Verdict.keep(null)
                    : Retries.Verdict.write(now.mutate(removals).serialize(), null);
        });
        // The cached section went with the trim, so the copy's newest-first row goes too: the document no longer says
        // it is held, and nothing else would ever name the row again.
        NewestFirst.CACHED.forget(store, ecosystem, coordinate, version,
                CachedSection.facts(document.section(CachedSection.TAG)).map(CachedSection.Facts::at).orElse(null));
        AdvisedSection.forget(store, AdvisedSection.advised(document.section(AdvisedSection.TAG)), ecosystem,
                coordinate, version);
        PackageNameIndex.forget(store, ecosystem, coordinate, version);
        Mailbox.CHANGED.forget(store, ecosystem, coordinate, version);
        // The trim above dropped every served-fact section, the findings section among them, so this version's ranked
        // line must drop too - bump the findings eviction epoch so the vulnerability rank index rebuilds on its next
        // pass rather than paging the reclaimed line until the next scan.
        Findings.evictions(store).bump();
        if (published) {
            identity.foldOut(InventoryIdentity.member(ecosystem, coordinate, version, evictedLicenses),
                    membership instanceof Known.Present<PublishedSection.Facts> present ? present.value().at() : null);
        }
        return true;
    }

    /** Unpublish every pointer a version occupies (the layout {@code publish/} pointers and any blobs-namespace format
     *  pointers), so the now-unreferenced content blob is reclaimed by the garbage collector - the byte-discard half an
     *  {@link #evict} and a {@link #reclaimFallbackCache} share, without the sidecar/document reap.
     *  {@code prerelease} is the version's own recorded flag and nothing else: it rides out to every
     *  {@code PublicationObserver} on each descriptor, so anything else handed in here is a fact this destroy invents
     *  about the artifact rather than reads about it. */
    private void unpublishPointers(String ecosystem, String coordinate, String version, boolean prerelease)
            throws IOException {
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
            for (String prefix : layout.paths(coordinate, version, store)) {
                for (String child : store.list("publish" + prefix)) {
                    publication.unpublish(new ArtifactDescriptor(ecosystem, coordinate, version,
                            prefix + "/" + child, null, prerelease, null, -1L));
                }
            }
        }
        for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(ecosystem)) {
            for (String key : blobLayout.blobKeys(coordinate, version, store)) {
                Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
                if (pointer.isPresent()) {
                    store.delete(key);
                    String named = ServableNames.hash(pointer.get().content());
                    publication.deleted(new ArtifactDescriptor(ecosystem, coordinate, version, key, null, prerelease,
                            StoreRepositoryInventory.hash(named) ? named : null, -1L));
                }
            }
        }
    }

    /**
     * Whether this destroy must fold the version's member out of the {@link InventoryIdentity} rollup - the ONE thing
     * the version's published membership decides on the destroy paths. Nothing here deletes, releases or discloses on
     * the answer: the pointers are already gone by the time the fold runs, and both destroys refuse outright, before
     * touching anything, when the version's pointers cannot be enumerated at all.
     *
     * <p>Every arm is written out, because the unanswerable one is a real deployment state (the publish facts stand on
     * the metadata plane this deployment does not read) and it is not a synonym for either answer. It folds
     * nothing out, and that is the self-healing direction of the two: a member left in the accumulator is recomputed
     * away by the next {@code reconcile}'s authoritative {@code rebuildIdentity} over the live published set, whereas
     * XOR-ing out a contribution that was never folded in corrupts the digest until that same rebuild - and until then
     * every {@code If-None-Match} revalidation of the SBOM / attribution export answers off a digest that matches
     * nothing. Both are transient; only one of them can be wrong in a direction that serves a stale ETag as fresh.
     */
    private static boolean foldsOut(Known<PublishedSection.Facts> membership) {
        return switch (membership) {
            case Known.Present<PublishedSection.Facts> _ -> true;
            case Known.Absent<PublishedSection.Facts> _ -> false;
            case Known.Unknown<PublishedSection.Facts> _ -> false;
        };
    }

    /** Delete a small sidecar if it exists - an eviction tolerates a sidecar that was never written. Returns whether it
     *  actually deleted something, so a caller can bump a derived-index eviction epoch only when a home really went (and
     *  never for a version/coordinate that never had one). */
    /** Tell every installed {@link EvictionObserver} that {@code release} is being evicted, with its document as it
     *  stands; a version with no document tells nothing, and an observer's failure is logged, never raised. */
    private void tellObservers(Release release) throws IOException {
        List<EvictionObserver> observers = EvictionObserver.installed();
        if (observers.isEmpty()) {
            return;
        }
        Optional<MetadataDocument> document = MetadataProvider.installed().over(store).read(release.ecosystem(),
                release.coordinate(), release.version());
        if (document.isEmpty()) {
            return;
        }
        for (EvictionObserver observer : observers) {
            try {
                observer.evicting(store, release.ecosystem(), release.coordinate(), release.version(),
                        document.get());
            } catch (IOException | RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "An eviction observer could not take back what it kept for "
                        + release.ecosystem() + " " + release.coordinate() + ":" + release.version()
                        + "; its reconcile removes it", e);
            }
        }
    }

    private boolean deleteIfPresent(String key) throws IOException {
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
            return true;
        }
        return false;
    }
}
