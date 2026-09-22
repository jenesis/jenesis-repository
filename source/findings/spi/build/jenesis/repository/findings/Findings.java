package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Stamp;

/**
 * The findings ledger over one repository's scoped store: any module records findings and labels against a
 * coordinate, every surface reads and filters them. Writes obey categorize-never-discard - {@link #record} merges
 * by {@code (source, id)} and never removes a sibling row, {@link #supersede} marks rather than deletes, and
 * {@link #label} adds an attributed annotation to an existing finding. The only removals are the artifact's own
 * lifecycle: an eviction deletes the version's rows through the {@linkplain #key stable key} this contract fixes,
 * and a discarded quarantine hold takes its gate findings with it - the ledger's data never outlives what it
 * describes, and never goes for any lesser reason.
 */
public interface Findings {

    /** The repository-scope key root the ledger owns; declared in the storage manifest by the persistence module. */
    String PREFIX = "findings";

    /**
     * The repository-level eviction-epoch marker: a one-segment in-tree sentinel beside the ledger's ecosystem subtrees
     * (the same shape as {@code findings/scanned}, {@link #scanned scan stamp}), declared in the storage manifest by the
     * persistence module. It carries an opaque token bumped every time a version's findings are reclaimed by
     * <em>eviction</em> - the "dirty" signal the vulnerability rank index folds into its rebuild stamp so an evicted
     * version's line drops on the next rank-index pass rather than lingering until the next <em>scan</em> moves
     * {@link #scanned scan stamp}. It is deliberately NOT the scan stamp: an eviction is not a scan, so bumping the freshness
     * stamp would both misreport the report's as-of instant (Principle 10) and make the eventually-consistent read fall
     * back - the two failure modes this separate epoch avoids.
     */
    String EVICTED = PREFIX + "/evicted";

    /** The key of the {@linkplain #scanned freshness stamp}, inside the prefix so it lives and dies with the
     *  ledger's key-space. */
    String SCANNED = PREFIX + "/scanned";

    /** Record (or refresh) a finding against a coordinate. An existing row with the same {@code (source, id)} keeps
     *  its {@code firstSeen}, labels and supersession mark while the mutable facts and {@code lastSeen} update; a new
     *  row is appended beside its siblings, never replacing one. */
    void record(String ecosystem, String coordinate, String version, Finding finding) throws IOException;

    /** Attach a label to the finding identified by {@code (source, id)} on a coordinate - an addition, never a
     *  rewrite. Labels from the same {@code (label.source(), label.name())} pair replace their own prior value (a
     *  classifier's re-run refreshes its opinion); everything else on the finding is untouched.
     *
     *  @throws IllegalArgumentException when no such finding exists on the coordinate */
    void label(String ecosystem, String coordinate, String version, String source, String id, Finding.Label label)
            throws IOException;

    /** Mark the finding identified by {@code (source, id)} as superseded by {@code supersededBy} (a finding id, a
     *  feed name, or a short reason) - the categorize-never-discard replacement for deletion: the row stays, marked.
     *
     *  @throws IllegalArgumentException when no such finding exists on the coordinate */
    void supersede(String ecosystem, String coordinate, String version, String source, String id, String supersededBy)
            throws IOException;

    /**
     * A batch of same-{@code (coordinate, version)} writes committed in <em>one</em> mutation (§4a): the rows to
     * {@link #record} (append-or-refresh, categorize-never-discard) and the labels to attach to existing rows. This is
     * the shape that collapses the per-row CAS storms - a scan pass's advisory rows, an AI sweep's per-finding labels
     * plus its queryable judgement row - from one read-modify-write per row into one per (coordinate-version, pass).
     * Immutable: both lists are copied defensively, and the batch never changes after construction (§11).
     */
    record Batch(List<Finding> records, List<Annotation> annotations) {

        /** A label targeted at the existing {@code (source, id)} row it annotates. */
        public record Annotation(String source, String id, Finding.Label label) {
        }

        public Batch {
            records = List.copyOf(records);
            annotations = List.copyOf(annotations);
        }

        /** A batch of rows to record and nothing to label. */
        public static Batch ofRecords(List<Finding> records) {
            return new Batch(records, List.of());
        }

        /** A batch of labels to attach and nothing to record. */
        public static Batch ofAnnotations(List<Annotation> annotations) {
            return new Batch(List.of(), annotations);
        }
    }

    /** Record (or refresh) many findings against one coordinate version in a single commit - the batched form of
     *  {@link #record} the scan sweep and the gate use so a pass's rows cost one mutation, not one per row. */
    default void recordAll(String ecosystem, String coordinate, String version, List<Finding> findings)
            throws IOException {
        commit(ecosystem, coordinate, version, Batch.ofRecords(findings));
    }

    /** Attach many labels to a coordinate version's existing findings in a single commit - the batched form of
     *  {@link #label} the AI sweeps use so a per-coordinate labelling pass costs one mutation, not one per label.
     *
     *  @throws IllegalArgumentException when an annotation names a finding not recorded on the coordinate */
    default void labelAll(String ecosystem, String coordinate, String version, List<Batch.Annotation> annotations)
            throws IOException {
        commit(ecosystem, coordinate, version, Batch.ofAnnotations(annotations));
    }

    /**
     * Apply a {@link Batch} of records and labels to one coordinate version in a single commit (§4a). The default
     * applies each write in turn (a mutation each) so a simple implementation stays correct; the store overrides it to
     * fold the whole batch into <em>one</em> compare-and-set against the coordinate's document.
     *
     * @throws IllegalArgumentException when an annotation names a finding not present after the batch's records apply
     */
    default void commit(String ecosystem, String coordinate, String version, Batch batch) throws IOException {
        for (Finding finding : batch.records()) {
            record(ecosystem, coordinate, version, finding);
        }
        for (Batch.Annotation annotation : batch.annotations()) {
            label(ecosystem, coordinate, version, annotation.source(), annotation.id(), annotation.label());
        }
    }

    /** Every finding recorded against a coordinate version, superseded rows included (marked, not hidden), in
     *  recorded order; empty when none was ever recorded. */
    List<Finding> of(String ecosystem, String coordinate, String version) throws IOException;

    /** Every finding recorded in this repository that matches the filter, walked from the ledger's own key tree -
     *  no feed is queried and no artifact is read. A coordinate-scoped filter resolves by a direct key-prefix lookup
     *  rather than a full scan of every coordinate. */
    List<Located> all(Filter filter) throws IOException;

    /**
     * A bounded page of the repository-wide walk: the findings matching the filter from {@code offset}, at most
     * {@code limit} of them, so a large repository's view is served a slice at a time rather than the whole ledger
     * materialised on every request (§7). A coordinate-scoped filter still resolves by direct key-prefix lookup.
     *
     * <p><strong>A ledger pages its own walk; the inherited body is a small-ledger fallback and says so out loud.</strong>
     * The {@code default} delegates to {@link #pageByListing}, which materialises {@link #all(Filter)} and slices it:
     * it answers the right page, but it buffers every matching finding in the repository to do it - the opposite of
     * what a paged read is for. So it refuses rather than pretending: past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} matched rows it throws an {@link IllegalStateException} naming the
     * inheriting class and the remedy, instead of quietly turning one console render into an unbounded heap
     * allocation. The store-backed ledger therefore overrides this - it bounds the walk itself, collecting no more
     * than one page past the offset, and serves a selective filter from the durable filter index - and an
     * implementation whose matched set genuinely <em>is</em> in memory calls {@link #pageByListing} by name, making
     * the cost a decision at the call site rather than an accident of inheritance.
     *
     * @throws IllegalStateException when the inherited fallback matches more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} findings
     */
    /** The distinct facet values the ledger's findings carry - the choices a filter offers - as far as a bounded
     *  read can answer them: the built filter index's buckets. Empty when no index stands; a console then offers
     *  the values of the rows it shows. */
    default Optional<Facets> facets() throws IOException {
        return Optional.empty();
    }

    record Facets(SortedSet<String> kinds, SortedSet<String> sources, SortedSet<String> categories) {
    }

    /** The furthest an offset page reaches into the ledger: an offset past it is clamped, so a page read never
     *  examines more than this many rows before its window. */
    int MAX_OFFSET = 10_000;

    default Page all(Filter filter, int offset, int limit) throws IOException {
        return pageByListing(this, filter, offset, limit);
    }

    /**
     * Stream every finding a filter matches to {@code visitor}, in {@link #all(Filter)}'s order and with the same
     * union/dedup semantics, but without ever materialising the whole matched set in heap - the form a whole-ledger
     * pass (a console facet fold, a background reclassification) uses to stay bounded on a repository with a very
     * large finding set.
     *
     * <p>The {@code default} delegates to {@link #streamByListing}, which materialises {@link #all(Filter)} and
     * emits it row by row - the one thing this signature exists to avoid - so it carries the same visible ceiling
     * as {@link #all(Filter, int, int)}: past {@link ArtifactStore#MAX_INHERITED_CHILDREN} matched rows it throws
     * rather than buffering the ledger behind a streaming promise. The store overrides it to stream the key-tree
     * walk itself, holding no more than the current row.
     *
     * @throws IllegalStateException when the inherited fallback matches more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} findings
     */
    default void all(Filter filter, Visitor visitor) throws IOException {
        streamByListing(this, filter, visitor);
    }

    /**
     * Page {@code findings} by materialising and slicing its whole {@link #all(Filter)} answer - the explicit, named
     * form of the fallback {@link #all(Filter, int, int)} inherits, for an implementation whose matched set is
     * already in memory (a map-backed ledger, a fixture) and for which a "bounded" walk would be this code anyway.
     *
     * <p>It is bounded, and the bound throws: see {@link InheritedBound}, which holds the ceiling and the refusal
     * for every SPI that ships this shape.
     *
     * @throws IllegalStateException when the filter matches more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *                               findings
     */
    static Page pageByListing(Findings findings, Filter filter, int offset, int limit) throws IOException {
        List<Located> matched = InheritedBound.bounded(findings, "all(Filter, int, int)", "all(Filter)",
                findings.all(filter));
        int from = Math.min(Math.max(0, offset), matched.size());
        int end = Math.min(matched.size(), from + Math.max(0, limit));
        return new Page(matched.subList(from, end), end < matched.size());
    }

    /**
     * Emit {@code findings}' whole {@link #all(Filter)} answer to {@code visitor} row by row - the explicit, named
     * form of the fallback {@link #all(Filter, Visitor)} inherits, for an implementation whose matched set is
     * already in memory. Bounded exactly as {@link #pageByListing} is.
     *
     * @throws IllegalStateException when the filter matches more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *                               findings
     */
    static void streamByListing(Findings findings, Filter filter, Visitor visitor) throws IOException {
        for (Located located : InheritedBound.bounded(findings, "all(Filter, Visitor)", "all(Filter)",
                findings.all(filter))) {
            visitor.accept(located);
        }
    }

    /** A sink for {@link #all(Filter, Visitor)}: receives each matching finding in turn, allowed the store I/O the
     *  walk does, so a caller folds facets or rows without buffering the whole matched set. */
    @FunctionalInterface
    interface Visitor {
        void accept(Located located) throws IOException;
    }

    /**
     * Rebuild any durable derived index this ledger keeps for the repository-wide views (the findings-filter index that
     * lets a selective {@code /api/findings} query seek a facet bucket rather than scan the whole plane), from the
     * current findings. Driven by a scheduled maintenance pass off the request path, not a read. The default is a no-op:
     * a simple implementation keeps no derived index and answers {@link #all(Filter, int, int)} by walking directly; the
     * store overrides it to rebuild the index, gated on a freshness stamp so a pass whose findings have not moved writes
     * nothing.
     */
    default void reindex() throws IOException {
    }

    /** A finding located at its coordinate, for the repository-wide views. */
    record Located(String ecosystem, String coordinate, String version, Finding finding) {
    }

    /** A bounded slice of the repository-wide walk: the located findings in this page, whether more remain past it (so a
     *  reader knows to ask for the next offset), and - when the page was served from the eventually-consistent
     *  findings-filter index - the ledger scan freshness that index was {@code builtScanStamp built at} (an
     *  {@code Instant} string, or blank for a never-scanned repository). {@code builtScanStamp} is {@code null} on a
     *  page the live walk produced (or a not-yet-built index falling back to it), signalling the reader to render the
     *  live scan stamp; a non-null value is the index's own as-of instant, so a selective query served from a built
     *  index is never labelled fresher than the index it came from (Principle 10, mirroring the health/vulnerability
     *  rank indexes' {@code builtScanStamp} split). */
    record Page(List<Located> located, boolean more, String builtScanStamp) {

        public Page {
            located = List.copyOf(located);
        }

        /** A page the live walk produced (or a not-yet-built index fell back to): not index-served, so
         *  {@code builtScanStamp} is {@code null} and a reader renders the live scan stamp. */
        public Page(List<Located> located, boolean more) {
            this(located, more, null);
        }
    }

    /**
     * The query surface's filter; a {@code null} member matches everything. {@code coordinate} matches the bare
     * coordinate or the {@code coordinate:version} form, so a per-artifact view needs no separate parameter.
     * {@code ecosystem} scopes the match to one ecosystem (case-insensitively), so a coordinate that two ecosystems
     * both name - an {@code npm} and a {@code PyPI} package of the same name - is not conflated across them; a
     * {@code null} ecosystem matches every ecosystem, the pre-ecosystem behaviour.
     */
    record Filter(String coordinate, Finding.Kind kind, String source, String category, Severity severity,
                  String ecosystem) {

        public static Filter none() {
            return new Filter(null, null, null, null, null, null);
        }

        public boolean matches(Located located) {
            if (ecosystem != null && !ecosystem.equalsIgnoreCase(located.ecosystem())) {
                return false;
            }
            if (coordinate != null && !coordinate.equals(located.coordinate())
                    && !coordinate.equals(located.coordinate() + ":" + located.version())) {
                return false;
            }
            Finding finding = located.finding();
            if (kind != null && finding.kind() != kind) {
                return false;
            }
            if (source != null && !source.equals(finding.source())) {
                return false;
            }
            if (category != null && !category.equalsIgnoreCase(finding.category())) {
                return false;
            }
            return severity == null || finding.severity() == severity;
        }
    }

    /**
     * The stable store key of a coordinate version's findings document, fixed by this contract so the artifact's
     * lifecycle owners reclaim the rows without reaching into the persistence module: the inventory's {@code evict}
     * deletes this key when the version goes, exactly as it takes the {@code downloaded/} and {@code licenses/}
     * sidecars. The coordinate is URL-encoded into a single traversal-free segment, the same scheme the
     * {@code published/} sidecar uses, so the two trees join on their segments; the {@code ecosystem} and
     * {@code version} are each validated as a single traversal-free segment through {@link ArtifactStore#segment} -
     * the guard the sibling {@code published/}/{@code pinned/} sidecar keys carry - so a {@code ..}-laced version or
     * ecosystem fed by a caller (a report request, an admin label) can never aim a read or write at a neighbouring
     * key-space inside the repository scope.
     */
    static String key(String ecosystem, String coordinate, String version) {
        return PREFIX + "/" + ArtifactStore.segment(ecosystem) + "/"
                + URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + "/" + ArtifactStore.segment(version);
    }

    /**
     * The instant the repository's advisory findings were last refreshed against the live feeds - by the scheduled
     * vulnerability sweep or an operator's explicit rescan - so every view can show how fresh its rendered ledger is.
     * Last-writer-wins: the newest completed refresh is the panel's honest freshness, whoever drove it. Absent means the
     * repository was never scanned, which a view must render as "never scanned", not as "clean".
     */
    static Stamp scanned(ArtifactStore store) {
        return new Stamp(store, SCANNED);
    }

    /**
     * The {@link #EVICTED eviction epoch}: bumped when a version's findings were reclaimed by eviction, so the
     * vulnerability rank index rebuilds on its next pass instead of no-opping on an unmoved {@link #scanned} stamp.
     * Bumped by an artifact-lifecycle owner (the inventory's {@code evict}, a cache reclaim, a discarded-hold reap)
     * <em>after</em> the findings are removed, so a rebuild that observes the new epoch also observes the removal;
     * module-independent, so a lifecycle owner marks the change whether or not the persistence module is installed -
     * exactly as it deletes findings by {@link #key} without reaching into the module.
     */
    static Epoch evictions(ArtifactStore store) {
        return new Epoch(store, EVICTED);
    }
}
