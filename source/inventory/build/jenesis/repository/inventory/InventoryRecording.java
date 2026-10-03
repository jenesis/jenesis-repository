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
 * recordDownload}/{@code publishedAt}/{@code lastDownloaded} methods delegate here - and this
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
                        descriptor.version(), descriptor.prerelease(), published).file(path));
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
     * the new. The recent-releases feed sees a first publish only, and a recording that changes nothing the document
     * says - a client re-running a publish it already made - writes nothing.
     */
    void commit(Recording recording) throws IOException {
        Instant now = Clocks.now();
        String documentKey = MetadataKey.version(recording.ecosystem, recording.coordinate, recording.version);
        Committed committed = DocumentTurns.take(store, documentKey,
                () -> Retries.decide(store, documentKey, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            Optional<List<LicenseInventory.Declared>> before = declaredIn(document);
            SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG, PublishedSection.record(
                    changedAt(document, recording.originSha256, recording.published), recording.prerelease,
                    recording.published));
            if (recording.originSha256 != null && !recording.originSha256.isBlank()) {
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(recording.originSha256, recording.published));
            }
            if (recording.licenses != null) {
                mutations.put(LicenseSection.TAG, LicenseSection.union(recording.licenses, now));
            }
            if (recording.dependencies != null) {
                mutations.put(DependencySection.TAG,
                        DependencySection.record(recording.file(), recording.dependencies, now));
            }
            if (recording.about != null) {
                mutations.put(AboutSection.TAG, AboutSection.record(recording.about, now));
            }
            if (recording.provenanceVerified != null) {
                mutations.put(ProvenanceSection.TAG, ProvenanceSection.record(recording.file(),
                        recording.provenanceVerified, recording.provenanceSha256, now));
            }
            if (recording.signatureOutcome != null) {
                mutations.put(SignatureSection.TAG, SignatureSection.record(recording.file(),
                        recording.signatureOutcome,
                        recording.signatureSigner, recording.signatureGrade, recording.signatureLocation,
                        recording.signatureSource, recording.signatureDetails, now));
            }
            MetadataDocument next = document.mutate(mutations);
            Optional<List<LicenseInventory.Declared>> after = declaredIn(next);
            boolean licensesChanged = before.isEmpty() ? after.isPresent()
                    : !new LinkedHashSet<>(before.get()).equals(new LinkedHashSet<>(after.orElse(List.of())));
            Committed landed = new Committed(firstPublish, before, after, licensesChanged,
                    PublishedSection.facts(next.section(PublishedSection.TAG)));
            return !firstPublish && same(document, next) ? Retries.Verdict.keep(landed)
                    : Retries.Verdict.write(next.serialize(), landed);
        }));
        if (committed.firstPublish()) {
            identity.foldIn(InventoryIdentity.member(recording.ecosystem, recording.coordinate, recording.version,
                    LicenseSection.fingerprintOf(committed.licensesAfter())), recording.published);
            NewestFirst.RELEASES.record(store, recording.ecosystem, recording.coordinate, recording.version,
                    recording.published);
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
        if (recordPublishedSection(ecosystem, coordinate, version, prerelease, published, originSha256)) {
            // A version is listed in the newest-first feed under its first publish, which no later file moves.
            NewestFirst.RELEASES.record(store, ecosystem, coordinate, version, published);
        }
    }

    /** Write the publish facts into the document's {@code published} section and, on a first publish (the section
     *  was not a published member before), fold the member into the rollup identity once the document write has
     *  committed - from the document as committed, so the member's license fingerprint is the section that document
     *  carries and a later license transition re-folds from exactly it. Answers whether it was the first publish; a
     *  later file of an existing member moves its last-changed instant and refreshes the prerelease flag (preserving
     *  the pin and the first publish), and one that changes nothing writes nothing.
     *
     *  <p>The fold does not ride the document write's batch as one unread compare-and-set attempt: a batch is not a
     *  transaction, so under concurrent publishers the document would commit while the rollup conflicted and the
     *  member would be gone from the identity until the next reconcile. Folding after the commit, through the
     *  retrying compare-and-set, is one small extra write per first publish. */
    private boolean recordPublishedSection(String ecosystem, String coordinate, String version, boolean prerelease,
                                           Instant published, String originSha256) throws IOException {
        // The document as the landing try wrote it on a first publish, else none.
        String key = MetadataKey.version(ecosystem, coordinate, version);
        MetadataDocument committed = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            boolean firstPublish = !PublishedSection.published(document.section(PublishedSection.TAG));
            SequencedMap<String, build.jenesis.repository.metadata.SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG, PublishedSection.record(changedAt(document, originSha256, published),
                    prerelease, published));
            if (originSha256 != null && !originSha256.isBlank()) {
                // The hand-upload local-upload origin row rides the SAME doc mutate as the publish
                // commit's published section - one CAS, no extra round-trip. Idempotent (one row per (source, sha256)).
                mutations.put(OriginSection.TAG, OriginSection.recordUpload(originSha256, published));
            }
            MetadataDocument next = document.mutate(mutations);
            return !firstPublish && same(document, next) ? Retries.Verdict.<MetadataDocument>keep(null)
                    : Retries.Verdict.write(next.serialize(), firstPublish ? next : null);
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

    /**
     * The instant a file of the version records as its last change: its own publish, unless it uploads bytes the
     * version already holds from an upload. Such a re-upload changes nothing a reader sees, so the version keeps the
     * instant it had; another file of the version, or other bytes, move it.
     */
    private static Instant changedAt(MetadataDocument document, String originSha256, Instant published) {
        Optional<Instant> before = PublishedSection.facts(document.section(PublishedSection.TAG))
                .map(PublishedSection.Facts::changed);
        if (before.isEmpty() || originSha256 == null || originSha256.isBlank()) {
            return published;
        }
        boolean held = OriginSection.acquisitions(document.section(OriginSection.TAG)).stream()
                .anyMatch(acquisition -> acquisition.localUpload() && originSha256.equals(acquisition.sha256()));
        return held ? before.get() : published;
    }

    /** Whether {@code next} says what {@code document} says - the same sections with the same contents - whatever
     *  instant each section was last stamped with: such a write changes nothing a reader sees, so it is not made. */
    private static boolean same(MetadataDocument document, MetadataDocument next) {
        if (!document.tags().equals(next.tags())) {
            return false;
        }
        for (String tag : document.tags()) {
            Optional<Section> was = document.section(tag);
            Optional<Section> is = next.section(tag);
            if (was.isEmpty() || is.isEmpty()) {
                if (!Objects.equals(document.raw(tag), next.raw(tag))) {
                    return false;
                }
                continue;
            }
            if (was.get().schema() != is.get().schema() || was.get().state() != is.get().state()
                    || !Objects.equals(was.get().error(), is.get().error())
                    || !Objects.equals(was.get().signal(), is.get().signal())
                    || !Objects.equals(was.get().payload(), is.get().payload())) {
                return false;
            }
        }
        return true;
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
