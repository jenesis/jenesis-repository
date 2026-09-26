package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.DocumentTurns;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;

/**
 * The publish-facts recording subsystem extracted from {@link StoreRepositoryInventory}: the write side of the inventory
 * that records when a coordinate version was published (the timestamp retention orders and ages by, plus the
 * format-supplied prerelease flag), its provenance summary, and its last-download marker, and answers the point reads
 * those facts back ({@link #publishedAt}, {@link #lastDownloaded}, {@link #publishedFacts}, {@link #membership}). The
 * publish facts land in the consolidated metadata document's {@code published} section when a {@link MetadataStore} is
 * installed (its presence is membership of the published set); with no metadata store installed the {@code published/}
 * sidecar is the source of truth instead. One layout or the other, never a fall-through. Each first
 * publish folds the coordinate's member into the {@link InventoryIdentity} rollup so a whole-repository export's ETag
 * revalidates. The facade owns the seam - the {@code record}/{@code recordProvenance}/{@code recordDownload}/
 * {@code publishedAt}/{@code lastDownloaded} methods delegate here - and this class shares the facade's compare-and-set
 * {@code writeVersioned} and its store-key/codec helpers rather than duplicating them.
 */
final class InventoryRecording {

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final MetadataStore metadata;
    private final InventoryIdentity identity;

    InventoryRecording(StoreRepositoryInventory inventory, ArtifactStore store, MetadataStore metadata,
                       InventoryIdentity identity) {
        this.inventory = inventory;
        this.store = store;
        this.metadata = metadata;
        this.identity = identity;
    }

    /** Record a published request path by the neutral coordinate the owning format describes - see
     *  {@link StoreRepositoryInventory#record(String, Instant)}. */
    void record(String path, Instant published) throws IOException {
        record(path, published, null);
    }

    /** Record a published request path, folding a {@code local-upload} origin row for {@code originSha256} (the stored
     *  blob's content hash) into the same publish-commit doc mutate. A {@code null} sha records no origin
     *  row - the non-upload record paths (a hold release, a re-screen) pass {@code null}. */
    void record(String path, Instant published, String originSha256) throws IOException {
        Optional<ArtifactDescriptor> descriptor = resolve(path);
        if (descriptor.isPresent()) {
            record(descriptor.get(), published, originSha256);
        }
    }

    /**
     * The descriptor the claiming format gives a request path - an {@link ArtifactLayout}'s or a {@link BlobLayout}'s,
     * present or not - or, for a path that no layout-bearing format handles, the one a capability-only
     * {@link BlobLayout} of the path's own ecosystem gives it. That fallback is the OCI inventory layout: the path's
     * claiming format (the real OCI format) is neither an {@link ArtifactLayout} nor a {@link BlobLayout}, so the
     * handles-gated pass finds nothing, and without the fallback an accepted OCI manifest push would write no
     * {@code published/oci/<name>/<ref>} row - the row every retroactive KEV/license enforcement sweep enumerates the
     * image by. Ecosystem-guarded exactly as describe's fallback is. Empty when nothing describes the path.
     */
    Optional<ArtifactDescriptor> resolve(String path) {
        for (RepositoryFormat format : StoreRepositoryInventory.formats()) {
            if (!format.handles(path)) {
                continue;
            }
            if (format instanceof ArtifactLayout layout) {
                return layout.describe(path, store);
            }
            if (format instanceof BlobLayout layout) {
                return layout.describe(path);
            }
        }
        for (RepositoryFormat format : StoreRepositoryInventory.formats()) {
            if (format.handles(path) || !(format instanceof BlobLayout layout)) {
                continue;
            }
            Optional<ArtifactDescriptor> descriptor = layout.describe(path);
            if (descriptor.isPresent() && layout.ecosystem().equals(descriptor.get().ecosystem())) {
                return descriptor;
            }
        }
        return Optional.empty();
    }

    /** A {@link Recording} for the coordinate and version a format describes {@code path} to, or empty when no
     *  installed format describes it that far - a folder, a listing, a path no format claims. */
    Optional<Recording> recording(String path, Instant published) {
        return resolve(path)
                .filter(descriptor -> descriptor.coordinate() != null && descriptor.version() != null)
                .map(descriptor -> new Recording(this, descriptor.ecosystem(), descriptor.coordinate(),
                        descriptor.version(), descriptor.prerelease(), published));
    }

    Recording recording(String ecosystem, String coordinate, String version, boolean prerelease, Instant published) {
        return new Recording(this, ecosystem, coordinate, version, prerelease, published);
    }

    /** What one commit of a recording did to the version's document, read out of the mutation that landed. */
    private record Committed(boolean firstPublish, Optional<List<LicenseInventory.Declared>> licensesBefore,
                             Optional<List<LicenseInventory.Declared>> licensesAfter, boolean licensesChanged,
                             Optional<PublishedSection.Facts> facts) {
    }

    /**
     * One compare-and-set of the version's document carrying every section the recording holds, then one fold of
     * the identity index: on a first publish the member joins with the fingerprint of the licences the document now
     * records, and on a re-publish whose union changed the recorded set the member is re-folded from the old set to
     * the new, exactly as the separate licence write used to do after its own commit. The recent-releases feed sees
     * a first publish only, as before. On the sidecar plane - no metadata module installed - the published key and
     * the licence sidecar are two keys anyway and the plane keeps no provenance, so the recording lands through the
     * per-fact faces there.
     */
    void commit(Recording recording) throws IOException {
        if (metadata == null) {
            record(recording.ecosystem, recording.coordinate, recording.version, recording.prerelease,
                    recording.published, recording.originSha256);
            if (recording.licenses != null) {
                new LicenseInventory(store).record(recording.ecosystem, recording.coordinate, recording.version,
                        recording.licenses);
            }
            return;
        }
        Instant now = Clocks.now();
        String documentKey = MetadataKey.version(recording.ecosystem, recording.coordinate, recording.version);
        Committed committed = DocumentTurns.take(store, documentKey,
                () -> Retries.decide(store, documentKey, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            Optional<List<LicenseInventory.Declared>> before = declaredIn(document);
            SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG,
                    PublishedSection.record(recording.published, recording.prerelease, recording.published));
            if (recording.originSha256 != null && !recording.originSha256.isBlank()) {
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(recording.originSha256, recording.published));
            }
            if (recording.licenses != null) {
                mutations.put(LicenseSection.TAG, LicenseSection.union(recording.licenses, now));
            }
            if (recording.dependencies != null) {
                mutations.put(DependencySection.TAG, DependencySection.record(recording.dependencies, now));
            }
            if (recording.provenanceVerified != null) {
                mutations.put(ProvenanceSection.TAG,
                        ProvenanceSection.record(recording.provenanceVerified, recording.provenanceSha256, now));
            }
            if (recording.signatureOutcome != null) {
                mutations.put(SignatureSection.TAG, SignatureSection.record(recording.signatureOutcome,
                        recording.signatureSigner, recording.signatureGrade, recording.signatureLocation,
                        recording.signatureSource, recording.signatureDetails, now));
            }
            MetadataDocument next = document.mutate(mutations);
            Optional<List<LicenseInventory.Declared>> after = declaredIn(next);
            boolean licensesChanged = before.isEmpty() ? after.isPresent()
                    : !new LinkedHashSet<>(before.get()).equals(new LinkedHashSet<>(after.orElse(List.of())));
            return Retries.Verdict.write(next.serialize(), new Committed(firstPublish, before, after, licensesChanged,
                    PublishedSection.facts(next.section(PublishedSection.TAG))));
        }));
        if (committed.firstPublish()) {
            identity.foldIn(InventoryIdentity.member(recording.ecosystem, recording.coordinate, recording.version,
                    LicenseSection.fingerprintOf(committed.licensesAfter())), recording.published);
            RecentReleases.record(store, recording.ecosystem, recording.coordinate, recording.version,
                    recording.published);
        } else if (committed.licensesChanged() && committed.facts().isPresent()
                && committed.facts().get().at() != null) {
            identity.refold(
                    InventoryIdentity.member(recording.ecosystem, recording.coordinate, recording.version,
                            LicenseSection.fingerprintOf(committed.licensesBefore())),
                    InventoryIdentity.member(recording.ecosystem, recording.coordinate, recording.version,
                            LicenseSection.fingerprintOf(committed.licensesAfter())),
                    committed.facts().get().at());
        }
    }

    /** Record a publish from the format-neutral descriptor an {@link ArtifactLayout} produced; a no-op for a descriptor
     *  that carries no coordinate (a checksum, generated metadata). */
    void record(ArtifactDescriptor descriptor, Instant published) throws IOException {
        record(descriptor, published, null);
    }

    /** Record a publish from the descriptor, folding a {@code local-upload} origin row for {@code originSha256}. */
    void record(ArtifactDescriptor descriptor, Instant published, String originSha256) throws IOException {
        if (descriptor.coordinate() == null || descriptor.version() == null) {
            return;
        }
        record(descriptor.ecosystem(), descriptor.coordinate(), descriptor.version(), descriptor.prerelease(),
                published, originSha256);
    }

    /** Record when a coordinate version was published, defaulting a non-prerelease. */
    void record(String ecosystem, String coordinate, String version, Instant published) throws IOException {
        record(ecosystem, coordinate, version, false, published);
    }

    /** Record when a coordinate version was published - the timestamp cleanup orders and ages by, plus the
     *  format-supplied prerelease flag the prerelease-expiry rule reads. */
    void record(String ecosystem, String coordinate, String version, boolean prerelease, Instant published)
            throws IOException {
        record(ecosystem, coordinate, version, prerelease, published, null);
    }

    /** Record a publish, folding a {@code local-upload} origin row for {@code originSha256} into the same doc mutate as
     *  the {@code published} section - one CAS, no extra round-trip. A {@code null} sha records no origin. */
    void record(String ecosystem, String coordinate, String version, boolean prerelease, Instant published,
                String originSha256) throws IOException {
        boolean firstPublish;
        if (metadata != null) {
            // The publish facts land in the document's published section (its presence is membership of the
            // published set). Edge-triggered on the absent -> present transition, followed by the rollup fold-in on
            // a first publish. On a hand upload the local-upload origin row rides that SAME doc mutate.
            firstPublish = recordPublishedSection(ecosystem, coordinate, version, prerelease, published, originSha256);
            if (!firstPublish) {
                return;
            }
        } else {
            String key = StoreRepositoryInventory.publishedKey(ecosystem, coordinate, version);
            firstPublish = store.readVersioned(key).isEmpty();
            inventory.writeVersioned(key, (published.toString() + " " + prerelease).getBytes(StandardCharsets.UTF_8));
            if (!firstPublish) {
                return;
            }
            // The absent -> present transition folds the coordinate's member into the rollup identity so the
            // whole-repository SBOM / NOTICE ETag revalidates. The member's license contribution is the canonical
            // fingerprint of the declared SET read through LicenseInventory, so an incremental fold and a full rebuild
            // agree whether or not the version has been migrated. (The document path folds after its own commit.)
            Optional<byte[]> licenses = LicenseSection.fingerprintOf(
                    new LicenseInventory(store).read(ecosystem, coordinate, version));
            identity.foldIn(InventoryIdentity.member(ecosystem, coordinate, version, licenses), published);
        }
        RecentReleases.record(store, ecosystem, coordinate, version, published);
    }

    /** Write the publish facts into the document's {@code published} section and, on a first publish (the section
     *  was not a published member before), fold the member into the rollup identity once the document write has
     *  committed - from the document as committed, so the member's license fingerprint is the section that document
     *  carries and a later license transition re-folds from exactly it. Returns {@code true} on a first publish; a
     *  re-publish of an existing member refreshes the instant/prerelease (preserving the pin) and returns
     *  {@code false}.
     *
     *  <p>The fold used to ride the document write's batch as one compare-and-set attempt on the first try only,
     *  and its outcome was never read: a batch is not a transaction, so under concurrent publishers the document
     *  committed while the rollup conflicted and the member was gone from the identity until the next reconcile -
     *  what the identity-drift canary measured over thirty-two writers. Folding after the commit, through the
     *  retrying compare-and-set, is one small extra write per first publish. */
    private boolean recordPublishedSection(String ecosystem, String coordinate, String version, boolean prerelease,
                                           Instant published, String originSha256) throws IOException {
        // The document as the landing try wrote it, or null when the member was already published.
        String key = MetadataKey.version(ecosystem, coordinate, version);
        MetadataDocument committed = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            SequencedMap<String, build.jenesis.repository.metadata.SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG, PublishedSection.record(published, prerelease, published));
            if (originSha256 != null && !originSha256.isBlank()) {
                // The hand-upload local-upload origin row rides the SAME doc mutate as the publish
                // commit's published section - one CAS, no extra round-trip. Idempotent (one row per (source, sha256)).
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(originSha256, published));
            }
            MetadataDocument next = document.mutate(mutations);
            return Retries.Verdict.write(next.serialize(), firstPublish ? next : null);
        }));
        if (committed == null) {
            return false;
        }
        // The absent -> present transition folds the coordinate's member into the rollup identity so the
        // whole-repository SBOM / NOTICE ETag revalidates - with the license fingerprint of the document that just
        // committed, so this fold and a rebuild reading that document agree on the member.
        identity.foldIn(InventoryIdentity.member(ecosystem, coordinate, version,
                LicenseSection.fingerprintOf(declaredIn(committed))), published);
        return true;
    }

    /** The declared-license set a document carries, as {@link LicenseInventory#read} answers it on the consolidated
     *  layout: empty when the document has no {@code licenses} section. */
    static Optional<List<LicenseInventory.Declared>> declaredIn(MetadataDocument document) {
        return document.has(LicenseSection.TAG)
                ? Optional.of(LicenseSection.declared(document.section(LicenseSection.TAG)))
                : Optional.empty();
    }

    /** Record a coordinate version's provenance summary at publish - see
     *  {@link StoreRepositoryInventory#recordProvenance}. A no-op when the consolidated metadata store is absent. */
    void recordProvenance(String ecosystem, String coordinate, String version, boolean verified, String sha256)
            throws IOException {
        if (metadata == null) {
            return;
        }
        metadata.mutate(ecosystem, coordinate, version, ProvenanceSection.TAG,
                ProvenanceSection.record(verified, sha256, Clocks.now()));
    }

    /** Record when a coordinate version was last downloaded - see {@link StoreRepositoryInventory#recordDownload}. */
    void recordDownload(String ecosystem, String coordinate, String version, Instant when) throws IOException {
        recordDownloads(ecosystem, coordinate, version, 1, when);
    }

    /**
     * Record {@code delta} downloads of a coordinate version, the newest at {@code last}: one compare-and-set on the
     * document's {@code downloads} section in the consolidated store, which sums with what other nodes landed; the
     * {@code downloaded/} sidecar carrying the instant alone where no consolidated store is installed.
     */
    void recordDownloads(String ecosystem, String coordinate, String version, long delta, Instant last)
            throws IOException {
        if (metadata != null) {
            metadata.mutate(ecosystem, coordinate, version, DownloadsSection.TAG,
                    DownloadsSection.add(delta, last, Clocks.now()));
            return;
        }
        inventory.writeVersioned(StoreRepositoryInventory.downloadedKey(ecosystem, coordinate, version),
                last.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The download facts of a coordinate version - count and newest instant - or empty where the consolidated store
     *  is not installed or nothing was ever recorded. */
    Optional<DownloadsSection.Facts> downloads(String ecosystem, String coordinate, String version)
            throws IOException {
        if (metadata == null) {
            return Optional.empty();
        }
        return DownloadsSection.facts(metadata.section(ecosystem, coordinate, version, DownloadsSection.TAG));
    }

    Optional<List<DependencySection.Declared>> dependencies(String ecosystem, String coordinate, String version)
            throws IOException {
        if (metadata == null) {
            return Optional.empty();
        }
        return DependencySection.declared(metadata.section(ecosystem, coordinate, version, DependencySection.TAG));
    }

    /** When a coordinate version was recorded as published - the {@code published/} sidecar's instant - or empty. */
    Optional<Instant> publishedAt(String ecosystem, String coordinate, String version) throws IOException {
        return publishedFacts(ecosystem, coordinate, version).map(PublishedSection.Facts::at);
    }

    /** When a coordinate version was last downloaded, or empty if never: the document's {@code downloads} section
     *  first, then the {@code downloaded/} sidecar - a deployment without the consolidated store, or a marker from
     *  before the section existed. A sidecar that does not parse reads as never, not as an error: the marker is
     *  best-effort by design. */
    Optional<Instant> lastDownloaded(String ecosystem, String coordinate, String version) throws IOException {
        Optional<Instant> recorded = downloads(ecosystem, coordinate, version).map(DownloadsSection.Facts::last);
        if (recorded.isPresent()) {
            return recorded;
        }
        return store.readVersioned(StoreRepositoryInventory.downloadedKey(ecosystem, coordinate, version))
                .flatMap(versioned -> {
                    try {
                        return Optional.of(Instant.parse(new String(versioned.content(), StandardCharsets.UTF_8).trim()));
                    } catch (DateTimeParseException _) {
                        return Optional.empty();
                    }
                });
    }

    /** The publish facts of one coordinate version as a point read: the {@code published} section when a consolidated
     *  store is installed, and the {@code published/} sidecar when none is (the graceful-absence layout the write
     *  side mirrors). One layout or the other - never one falling through to the other, which would let a document
     *  that legitimately carries no published section be answered from a stale key. A read never writes (§10). */
    Optional<PublishedSection.Facts> publishedFacts(String ecosystem, String coordinate, String version)
            throws IOException {
        if (metadata != null) {
            Optional<Section> section = metadata.section(ecosystem, coordinate, version, PublishedSection.TAG);
            return section.isPresent() && PublishedSection.published(section)
                    ? PublishedSection.facts(section) : Optional.empty();
        }
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(
                StoreRepositoryInventory.publishedKey(ecosystem, coordinate, version));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        String[] value = new String(stored.get().content(), StandardCharsets.UTF_8).trim().split(" ");
        Instant published;
        try {
            published = value.length > 0 && !value[0].isEmpty() ? Instant.parse(value[0]) : Instant.EPOCH;
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
        boolean prerelease = value.length > 1 && Boolean.parseBoolean(value[1]);
        boolean pinned = store.readVersioned(
                StoreRepositoryInventory.pinnedKey(ecosystem, coordinate, version)).isPresent();
        return Optional.of(new PublishedSection.Facts(published, prerelease, pinned));
    }

    /**
     * Whether a coordinate version is a published member, three-valued for the sweeps that DELETE on a {@code false}
     *: {@link Known.Present} with the version's publish facts, {@link Known.Absent} when neither plane holds
     * a record for it, {@link Known.Unknown} when the plane this deployment does not read holds one.
     *
     * <p>There is deliberately no two-valued {@code isPublished} beside this. It existed, "for the callers that only
     * fold a rollup and do nothing destructive with a {@code false}" - and both of its callers were on the eviction
     * path, which is the one place a fused answer is least affordable. A caller that genuinely may treat an unread
     * plane as a non-membership now writes that arm where the knowledge is.
     *
     * <p>Which plane carries the publish facts is chosen by {@code MetadataProvider.installed()}: the consolidated
     * {@code meta} document when a persistence module is installed, the legacy {@code published/} sidecar when none
     * is - and never one falling through to the other, deliberately, so a document that legitimately carries no
     * published section is not answered from a stale key. That choice is a function of an installed module, so
     * uninstalling the metadata persistence module (or installing it over a store written without one) silently flips
     * every version of the repository to "not a published member" - and the liveness guard covers the FORMAT leg only,
     * so the reconcile sweep then judges those versions by liveness alone and reaps the derived rows of every one it
     * can read as gone, while the forward leg re-records versions that were never lost. A record standing on the plane
     * this deployment does not read is not evidence of anything: nothing was asked of it.
     *
     * <p>So a version whose own plane says nothing, while the OTHER plane holds a record for it, is
     * {@link Known.Unknown} ({@link Known.Cause#UNINSTALLED} - the module that owns the plane the record lives on is
     * not the one installed here) rather than {@link Known.Absent}. There is deliberately no fold of one plane into
     * the other here either (a read never writes, and the two are not migrated on the fly): the row is left exactly
     * where it is, for the operator's explicit purge or for a deployment that installs the module again. One extra
     * point read, only on the miss.
     */
    Known<PublishedSection.Facts> membership(String ecosystem, String coordinate, String version) throws IOException {
        Optional<PublishedSection.Facts> facts = publishedFacts(ecosystem, coordinate, version);
        if (facts.isPresent()) {
            return Known.known(facts.get());
        }
        String other;
        try {
            other = metadata != null
                    ? StoreRepositoryInventory.publishedKey(ecosystem, coordinate, version)
                    : MetadataKey.version(ecosystem, coordinate, version);
        } catch (IllegalArgumentException _) {
            // The other plane's codec refuses this coordinate outright (a traversal-hostile segment, a version opening
            // with the reserved '@'), so no record can ever have been written there and there is nothing unread.
            return Known.absent();
        }
        return store.exists(other)
                ? Known.uninstalled("the publish facts of " + ecosystem + " " + coordinate + ":" + version
                        + " stand at " + other + ", on the plane this deployment's metadata persistence does not "
                        + "read; nothing was asked of where they actually live")
                : Known.absent();
    }
}
