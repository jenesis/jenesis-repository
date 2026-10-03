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
 * The publish-facts recording subsystem behind {@link StoreRepositoryInventory}: the write side of the inventory that
 * records when a coordinate version was published (the timestamp retention orders and ages by, plus the
 * format-supplied prerelease flag), its provenance summary, and its last-download marker, and answers the point reads
 * those facts back ({@link #publishedAt}, {@link #lastDownloaded}, {@link #publishedFacts}, {@link #membership}). The
 * publish facts land in the consolidated metadata document's {@code published} section (its presence is membership
 * of the published set). Each first publish folds the coordinate's member into the {@link InventoryIdentity} rollup so
 * a whole-repository export's ETag revalidates. The facade owns the seam - the {@code record}/{@code
 * recordProvenance}/{@code recordDownload}/ {@code publishedAt}/{@code lastDownloaded} methods delegate here - and this
 * class shares the facade's compare-and-set {@code writeVersioned} and its store-key/codec helpers rather than
 * duplicating them.
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
                             Optional<PublishedSection.Facts> facts, Instant publishedBefore) {
    }

    /**
     * One compare-and-set of the version's document carrying every section the recording holds, then one fold of
     * the identity index: on a first publish the member joins with the fingerprint of the licences the document now
     * records, and on a re-publish whose union changed the recorded set the member is re-folded from the old set to
     * the new. The recent-releases feed sees a first publish only.
     */
    void commit(Recording recording) throws IOException {
        Instant now = Clocks.now();
        String documentKey = MetadataKey.version(recording.ecosystem, recording.coordinate, recording.version);
        Committed committed = DocumentTurns.take(store, documentKey,
                () -> Retries.decide(store, documentKey, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            Instant publishedBefore = PublishedSection.facts(document.section(PublishedSection.TAG))
                    .map(PublishedSection.Facts::at).orElse(null);
            Optional<List<LicenseInventory.Declared>> before = declaredIn(document);
            SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG, PublishedSection.record(
                    publishedAt(document, recording.originSha256, recording.published), recording.prerelease,
                    recording.published));
            if (recording.originSha256 != null && !recording.originSha256.isBlank()) {
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(recording.originSha256, recording.published));
            }
            if (recording.licenses != null) {
                mutations.put(LicenseSection.TAG, LicenseSection.union(recording.licenses, now));
            }
            if (recording.dependencies != null) {
                mutations.put(DependencySection.TAG, DependencySection.record(recording.dependencies, now));
            }
            if (recording.about != null) {
                mutations.put(AboutSection.TAG, AboutSection.record(recording.about, now));
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
                    PublishedSection.facts(next.section(PublishedSection.TAG)), publishedBefore));
        }));
        if (committed.firstPublish()) {
            identity.foldIn(InventoryIdentity.member(recording.ecosystem, recording.coordinate, recording.version,
                    LicenseSection.fingerprintOf(committed.licensesAfter())), recording.published);
            NewestFirst.RELEASES.record(store, recording.ecosystem, recording.coordinate, recording.version,
                    recording.published);
        } else {
            moveRecent(recording.ecosystem, recording.coordinate, recording.version, committed.publishedBefore(),
                    committed.facts().map(PublishedSection.Facts::at).orElse(null));
        }
        if (!committed.firstPublish() && committed.licensesChanged() && committed.facts().isPresent()
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
        // The publish facts land in the document's published section (its presence is membership of the published
        // set). Edge-triggered on the absent -> present transition, followed by the rollup fold-in on a first publish.
        // On a hand upload the local-upload origin row rides that SAME doc mutate.
        Landed landed = recordPublishedSection(ecosystem, coordinate, version, prerelease, published, originSha256);
        if (landed.first() != null) {
            NewestFirst.RELEASES.record(store, ecosystem, coordinate, version, published);
        } else {
            moveRecent(ecosystem, coordinate, version, landed.before(), landed.at());
        }
    }

    /**
     * Keep the newest-first row on the instant the document now records: a re-publish - another file of the version,
     * or other bytes - refreshes the {@code published} instant ({@link #publishedAt}), and a row left under the
     * earlier one would be a second row for one version once the reconcile backfills the new instant. The row is
     * written under the new instant before the old one goes, so the version is never missing from the feed; a crash
     * between the two leaves the extra row, which a reader shows once.
     */
    private void moveRecent(String ecosystem, String coordinate, String version, Instant before, Instant after)
            throws IOException {
        if (before == null || after == null || before.equals(after)) {
            return;
        }
        NewestFirst.RELEASES.record(store, ecosystem, coordinate, version, after);
        NewestFirst.RELEASES.forget(store, ecosystem, coordinate, version, before);
    }

    /** Write the publish facts into the document's {@code published} section and, on a first publish (the section
     *  was not a published member before), fold the member into the rollup identity once the document write has
     *  committed - from the document as committed, so the member's license fingerprint is the section that document
     *  carries and a later license transition re-folds from exactly it. Answers the document it wrote on a first
     *  publish; a re-publish of an existing member refreshes the instant/prerelease (preserving the pin) and answers
     *  the instant the document recorded before it and the one it records now.
     *
     *  <p>The fold does not ride the document write's batch as one unread compare-and-set attempt: a batch is not a
     *  transaction, so under concurrent publishers the document would commit while the rollup conflicted and the
     *  member would be gone from the identity until the next reconcile. Folding after the commit, through the
     *  retrying compare-and-set, is one small extra write per first publish. */
    private Landed recordPublishedSection(String ecosystem, String coordinate, String version, boolean prerelease,
                                          Instant published, String originSha256) throws IOException {
        // The document as the landing try wrote it on a first publish, and the instants it recorded before and now.
        String key = MetadataKey.version(ecosystem, coordinate, version);
        Landed landed = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            Instant before = PublishedSection.facts(document.section(PublishedSection.TAG))
                    .map(PublishedSection.Facts::at).orElse(null);
            SequencedMap<String, build.jenesis.repository.metadata.SectionMutation> mutations = new LinkedHashMap<>();
            Instant at = publishedAt(document, originSha256, published);
            mutations.put(PublishedSection.TAG, PublishedSection.record(at, prerelease, published));
            if (originSha256 != null && !originSha256.isBlank()) {
                // The hand-upload local-upload origin row rides the SAME doc mutate as the publish
                // commit's published section - one CAS, no extra round-trip. Idempotent (one row per (source, sha256)).
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(originSha256, published));
            }
            MetadataDocument next = document.mutate(mutations);
            return Retries.Verdict.write(next.serialize(), new Landed(firstPublish ? next : null, before, at));
        }));
        if (landed.first() == null) {
            return landed;
        }
        MetadataDocument committed = landed.first();
        // The absent -> present transition folds the coordinate's member into the rollup identity so the
        // whole-repository SBOM / NOTICE ETag revalidates - with the license fingerprint of the document that just
        // committed, so this fold and a rebuild reading that document agree on the member.
        identity.foldIn(InventoryIdentity.member(ecosystem, coordinate, version,
                LicenseSection.fingerprintOf(declaredIn(committed))), published);
        return landed;
    }

    /**
     * The instant a publish of the version records: its own, unless it uploads bytes the version already holds from an
     * upload. Such a re-upload changes nothing a reader sees, so the version keeps the instant it had and its row in
     * the newest-first feed stays where it is; another file of the version, or other bytes, move both.
     */
    private static Instant publishedAt(MetadataDocument document, String originSha256, Instant published) {
        Optional<Instant> before = PublishedSection.facts(document.section(PublishedSection.TAG))
                .map(PublishedSection.Facts::at);
        if (before.isEmpty() || originSha256 == null || originSha256.isBlank()) {
            return published;
        }
        boolean held = OriginSection.acquisitions(document.section(OriginSection.TAG)).stream()
                .anyMatch(acquisition -> acquisition.localUpload() && originSha256.equals(acquisition.sha256()));
        return held ? before.get() : published;
    }

    /** What a publish's document write landed: the document on a first publish, else none, and the instants the
     *  document recorded before and records now. */
    private record Landed(MetadataDocument first, Instant before, Instant at) {
    }

    /**
     * Record a copy a pull-through cached from {@code upstream} as a cached holding - the document's {@code cached}
     * section and the row of the newest-first cached index - unless the version is already held, as a release or as
     * a copy. One compare-and-set of the document, and none at all when it is already held, so a re-fill of a known
     * version costs the read that finds it and nothing else. Returns {@code true} when this call recorded it.
     *
     * <p>A published version is never recorded as a copy: a release here is the system of record for its bytes,
     * whatever an upstream also serves under the same name, and it keeps to the published set's rules.
     */
    boolean cache(String ecosystem, String coordinate, String version, String upstream, Instant at)
            throws IOException {
        String key = MetadataKey.version(ecosystem, coordinate, version);
        boolean recorded = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            if (Holdings.held(document)) {
                return Retries.Verdict.keep(false);
            }
            SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(CachedSection.TAG, CachedSection.record(at, upstream));
            return Retries.Verdict.write(document.mutate(mutations).serialize(), true);
        }));
        if (recorded) {
            NewestFirst.CACHED.record(store, ecosystem, coordinate, version, at);
        }
        return recorded;
    }

    /** When a coordinate version was first cached from an upstream and where from, or empty when it is not held as a
     *  cached copy - a point read of its document's {@code cached} section. A read never writes. */
    Optional<CachedSection.Facts> cachedFacts(String ecosystem, String coordinate, String version)
            throws IOException {
        return CachedSection.facts(metadata.section(ecosystem, coordinate, version, CachedSection.TAG));
    }

    /** The declared-license set a document carries, as {@link LicenseInventory#read} answers it on the consolidated
     *  layout: empty when the document has no {@code licenses} section. */
    static Optional<List<LicenseInventory.Declared>> declaredIn(MetadataDocument document) {
        return document.has(LicenseSection.TAG)
                ? Optional.of(LicenseSection.declared(document.section(LicenseSection.TAG)))
                : Optional.empty();
    }

    /** Record a coordinate version's provenance summary at publish - see
     *  {@link StoreRepositoryInventory#recordProvenance}. */
    void recordProvenance(String ecosystem, String coordinate, String version, boolean verified, String sha256)
            throws IOException {
        metadata.mutate(ecosystem, coordinate, version, ProvenanceSection.TAG,
                ProvenanceSection.record(verified, sha256, Clocks.now()));
    }

    /** Record when a coordinate version was last downloaded - see {@link StoreRepositoryInventory#recordDownload}. */
    void recordDownload(String ecosystem, String coordinate, String version, Instant when) throws IOException {
        recordDownloads(ecosystem, coordinate, version, 1, when);
    }

    /**
     * Record {@code delta} downloads of a coordinate version, the newest at {@code last}: one compare-and-set on the
     * document's {@code downloads} section, which sums with what other nodes landed.
     */
    void recordDownloads(String ecosystem, String coordinate, String version, long delta, Instant last)
            throws IOException {
        metadata.mutate(ecosystem, coordinate, version, DownloadsSection.TAG,
                DownloadsSection.add(delta, last, Clocks.now()));
    }

    /** The download facts of a coordinate version - count and newest instant - or empty when nothing was ever
     *  recorded. */
    Optional<DownloadsSection.Facts> downloads(String ecosystem, String coordinate, String version)
            throws IOException {
        return DownloadsSection.facts(metadata.section(ecosystem, coordinate, version, DownloadsSection.TAG));
    }

    Optional<List<DependencySection.Declared>> dependencies(String ecosystem, String coordinate, String version)
            throws IOException {
        return DependencySection.declared(metadata.section(ecosystem, coordinate, version, DependencySection.TAG));
    }

    /** What a version's document records for a full-text index - see {@link StoreRepositoryInventory#searchable}. */
    Optional<StoreRepositoryInventory.Searchable> searchable(String ecosystem, String coordinate, String version)
            throws IOException {
        return metadata.read(ecosystem, coordinate, version).map(document -> new StoreRepositoryInventory.Searchable(
                declaredIn(document), AboutSection.about(document.section(AboutSection.TAG))));
    }

    /** When a coordinate version was recorded as published, or empty. */
    Optional<Instant> publishedAt(String ecosystem, String coordinate, String version) throws IOException {
        return publishedFacts(ecosystem, coordinate, version).map(PublishedSection.Facts::at);
    }

    /** When a coordinate version was last downloaded, or empty if never: the document's {@code downloads}
     *  section. */
    Optional<Instant> lastDownloaded(String ecosystem, String coordinate, String version) throws IOException {
        return downloads(ecosystem, coordinate, version).map(DownloadsSection.Facts::last);
    }

    /** The publish facts of one coordinate version as a point read of its document's {@code published} section. A
     *  read never writes. */
    Optional<PublishedSection.Facts> publishedFacts(String ecosystem, String coordinate, String version)
            throws IOException {
        Optional<Section> section = metadata.section(ecosystem, coordinate, version, PublishedSection.TAG);
        return section.isPresent() && PublishedSection.published(section)
                ? PublishedSection.facts(section) : Optional.empty();
    }

    /**
     * Whether a coordinate version is a published member, for the sweeps that DELETE on a {@code false}:
     * {@link Known.Present} with the version's publish facts, {@link Known.Absent} when its document records no
     * publish. Typed as {@link Known} because those callers already judge a three-valued answer, and an unreadable
     * document reaches them as the {@link IOException} it is rather than as an absence.
     */
    Known<PublishedSection.Facts> membership(String ecosystem, String coordinate, String version) throws IOException {
        return publishedFacts(ecosystem, coordinate, version).map(Known::known).orElseGet(Known::absent);
    }
}
