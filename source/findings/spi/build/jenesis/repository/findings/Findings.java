package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Stamp;

/**
 * The findings ledger over one repository's scoped store: any module records findings and labels against a
 * coordinate, every surface reads and filters them. Writes categorize and never discard: {@link #record} merges by
 * {@code (source, id)}, {@link #supersede} marks rather than deletes, and {@link #label} adds an attributed annotation.
 * Rows go only with the artifact: an eviction deletes the version's rows, and a discarded hold takes its gate
 * findings.
 */
public interface Findings {

    /** The repository-scope key root the ledger owns; declared in the storage manifest by the persistence module. */
    String PREFIX = "findings";

    /**
     * The eviction epoch's key, beside the ledger's ecosystem subtrees: a token bumped whenever a version's findings
     * are reclaimed by eviction, which the vulnerability rank index folds into its rebuild stamp so an evicted version
     * drops on its next pass. Separate from the {@link #scanned scan stamp}, since an eviction is not a scan and moving
     * that stamp would misreport the report's as-of instant.
     */
    String EVICTED = PREFIX + "/evicted";

    /** The key of the {@linkplain #scanned freshness stamp}, inside the ledger's key-space. */
    String SCANNED = PREFIX + "/scanned";

    /** The {@link #changed change epoch}'s key. */
    String CHANGED = PREFIX + "/changed";

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
     * Writes to one coordinate version committed as one mutation: the rows to {@link #record} and the labels to attach,
     * so a pass costs one read-modify-write per version rather than one per row.
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

    /** {@link #record} for many findings of one coordinate version, in one commit. */
    default void recordAll(String ecosystem, String coordinate, String version, List<Finding> findings)
            throws IOException {
        commit(ecosystem, coordinate, version, Batch.ofRecords(findings));
    }

    /** {@link #label} for many findings of one coordinate version, in one commit.
     *
     *  @throws IllegalArgumentException when an annotation names a finding not recorded on the coordinate */
    default void labelAll(String ecosystem, String coordinate, String version, List<Batch.Annotation> annotations)
            throws IOException {
        commit(ecosystem, coordinate, version, Batch.ofAnnotations(annotations));
    }

    /**
     * Applies a {@link Batch} to one coordinate version. The default applies each write in turn; the store folds the
     * batch into one compare-and-set.
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

    /** Every finding in this repository matching the filter, from the ledger alone; a coordinate-scoped filter is a
     *  key-prefix lookup. */
    List<Located> all(Filter filter) throws IOException;

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

    /**
     * A bounded page of the findings matching the filter: from {@code offset}, at most {@code limit}.
     *
     * <p>The default, {@link #pageByListing}, materialises {@link #all(Filter)} and slices it, so it refuses past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} matched rows. The store-backed ledger overrides it, collecting no
     * more than one page past the offset and serving a selective filter from the filter index; an in-memory ledger
     * calls {@link #pageByListing} by name.
     *
     * @throws IllegalStateException when the inherited fallback matches more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} findings
     */
    default Page all(Filter filter, int offset, int limit) throws IOException {
        return pageByListing(this, filter, offset, limit);
    }

    /**
     * Streams every finding a filter matches to {@code visitor}, in {@link #all(Filter)}'s order, without
     * materialising the matched set. The default, {@link #streamByListing}, materialises it and so refuses past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} rows; the store streams its key-tree walk.
     *
     * @throws IllegalStateException when the inherited fallback matches more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} findings
     */
    default void all(Filter filter, Visitor visitor) throws IOException {
        streamByListing(this, filter, visitor);
    }

    /**
     * Pages {@code findings} by materialising and slicing {@link #all(Filter)}, for a ledger already in memory. Bounded
     * by {@link InheritedBound}, which throws past the ceiling.
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
     * Emits {@code findings}' whole {@link #all(Filter)} answer to {@code visitor}, for a ledger already in memory.
     * Bounded as {@link #pageByListing} is.
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

    /** A sink for {@link #all(Filter, Visitor)}, allowed store I/O. */
    @FunctionalInterface
    interface Visitor {
        void accept(Located located) throws IOException;
    }

    /**
     * Rebuilds the derived index this ledger keeps for repository-wide views (the filter index a selective
     * {@code /api/findings} query seeks), from a maintenance pass off the request path. A no-op by default; the store
     * skips the rebuild when the findings have not moved.
     */
    default void reindex() throws IOException {
    }

    /** A finding located at its coordinate, for the repository-wide views. */
    record Located(String ecosystem, String coordinate, String version, Finding finding) {
    }

    /** A page of located findings, whether more remain, and {@code builtScanStamp}: the scan instant the filter index
     *  serving the page was built at (blank for a never-scanned repository), or {@code null} for a page the live walk
     *  produced, whose reader renders the live scan stamp. So an index-served page is never labelled fresher than its
     *  index. */
    record Page(List<Located> located, boolean more, String builtScanStamp) {

        public Page {
            located = List.copyOf(located);
        }

        /** A page the live walk produced. */
        public Page(List<Located> located, boolean more) {
            this(located, more, null);
        }
    }

    /**
     * The query surface's filter; a {@code null} member matches everything. {@code coordinate} matches the bare
     * coordinate or {@code coordinate:version}; {@code ecosystem} matches case-insensitively, so same-named packages of
     * two ecosystems are not conflated.
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
     * The instant the repository's advisory findings were last refreshed against the feeds, by the sweep or an
     * operator's rescan; last writer wins. Absent means never scanned, which a view renders as such, not as clean.
     */
    static Stamp scanned(ArtifactStore store) {
        return new Stamp(store, SCANNED);
    }

    /**
     * The {@link #EVICTED eviction epoch}, bumped by a lifecycle owner (an eviction, a cache reclaim, a discarded-hold
     * reap) after the findings are removed, so a rebuild that sees the new epoch also sees the removal.
     */
    static Epoch evictions(ArtifactStore store) {
        return new Epoch(store, EVICTED);
    }

    /**
     * The change epoch, bumped by a pass that changed the repository's advisory findings - recorded one it did not
     * hold at that severity, or superseded one - whether or not it was a full pass, so a view built from the ledger
     * sees a finding the moment a pass records it rather than at the next full pass, which is all {@link #scanned}
     * moves on.
     */
    static Epoch changed(ArtifactStore store) {
        return new Epoch(store, CHANGED);
    }
}
