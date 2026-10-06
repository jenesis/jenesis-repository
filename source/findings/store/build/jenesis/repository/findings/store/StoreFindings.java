package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.inventory.Mailbox;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The findings ledger over one repository's scoped store: the {@code findings} section of each coordinate version's
 * metadata document ({@link MetadataKey#version}). A version's record is a point lookup, the repository-wide view walks
 * {@code meta/}, never an artifact body, and eviction reclaims a version with one document delete. Every mutation
 * merges only this section through the store's section-scoped compare-and-set, carrying the others verbatim, so the
 * sweep, the gate and an on-demand report writing one coordinate converge on the union of their rows.
 *
 * <p>Categorize-never-discard: {@link #record} only appends or refreshes, {@link #supersede} marks a row rather than
 * deleting it, and the only removals are the artifact lifecycle's - eviction and the discarded-hold observer.
 *
 * <p><strong>Batched commit.</strong> {@link #commit} folds a batch of rows and labels into one section mutate - one
 * compare-and-set per coordinate version, not one per row.
 */
public final class StoreFindings implements Findings {


    private final ArtifactStore store;

    /** The consolidated metadata store this repository's document lives in. */
    private final MetadataStore metadata;

    public StoreFindings(ArtifactStore store) {
        this(store, MetadataProvider.installed().over(store));
    }

    /** Bind the ledger to an explicit metadata store rather than the discovered one. */
    public StoreFindings(ArtifactStore store, MetadataStore metadata) {
        this.store = store;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public void record(String ecosystem, String coordinate, String version, Finding finding) throws IOException {
        boolean[] appended = {false};
        apply(ecosystem, coordinate, version, rows -> {
            appended[0] = FindingsSection.merge(rows, finding);   // the committed run's value is what stands
            return rows;
        });
        if (appended[0]) {
            emitFinding(ecosystem, coordinate, version, finding);
        }
    }

    @Override
    public void label(String ecosystem, String coordinate, String version, String source, String id,
                      Finding.Label label) throws IOException {
        apply(ecosystem, coordinate, version, rows -> {
            applyLabel(rows, source, id, label, ecosystem, coordinate, version);
            return rows;
        });
    }

    @Override
    public void supersede(String ecosystem, String coordinate, String version, String source, String id,
                          String supersededBy) throws IOException {
        apply(ecosystem, coordinate, version, rows -> {
            for (int index = 0; index < rows.size(); index++) {
                Finding existing = rows.get(index);
                if (existing.source().equals(source) && existing.id().equals(id)) {
                    rows.set(index, new Finding(existing.id(), existing.source(), existing.kind(),
                            existing.category(), existing.severity(), existing.confidence(), existing.description(),
                            existing.references(), existing.provenance(), existing.attributes(),
                            existing.firstSeen(), existing.lastSeen(), supersededBy, existing.labels()));
                    return rows;
                }
            }
            throw new IllegalArgumentException("No finding " + source + "/" + id + " recorded against "
                    + ecosystem + " " + coordinate + " " + version + " to supersede");
        });
    }

    @Override
    public void commit(String ecosystem, String coordinate, String version, Batch batch) throws IOException {
        if (batch.records().isEmpty() && batch.annotations().isEmpty()) {
            return;
        }
        List<Finding> appended = new ArrayList<>();
        apply(ecosystem, coordinate, version, rows -> {
            appended.clear();                                     // reset each attempt; the committed run stands
            for (Finding finding : batch.records()) {
                if (FindingsSection.merge(rows, finding)) {
                    appended.add(finding);
                }
            }
            for (Batch.Annotation annotation : batch.annotations()) {
                applyLabel(rows, annotation.source(), annotation.id(), annotation.label(),
                        ecosystem, coordinate, version);
            }
            return rows;
        });
        for (Finding finding : appended) {
            emitFinding(ecosystem, coordinate, version, finding);
        }
    }

    @Override
    public List<Finding> of(String ecosystem, String coordinate, String version) throws IOException {
        // An absent section means nothing has been recorded for this version.
        return metadata.read(ecosystem, coordinate, version)
                .filter(document -> document.has(FindingsSection.TAG))
                .map(document -> FindingsSection.rows(document.section(FindingsSection.TAG)))
                .orElseGet(List::of);
    }

    @Override
    public List<Located> all(Filter filter) throws IOException {
        return collect(filter, Integer.MAX_VALUE, Integer.MAX_VALUE).located();
    }

    @Override
    public Page all(Filter filter, int offset, int limit) throws IOException {
        // A facet filter has no key push-down in the live walk, so it is served from the filter index when one is
        // built. A coordinate filter or a bare/ecosystem filter already bounds the walk below, which an unbuilt index
        // falls back to as well.
        if (FindingsFilterIndex.indexable(filter)) {
            Page indexed = new FindingsFilterIndex(store).read(filter, offset, limit);
            if (indexed != null) {
                return indexed;
            }
        }
        int from = Math.clamp(offset, 0, MAX_OFFSET);
        int size = Math.max(0, limit);
        // Collect one past the window, so the page can say whether more remain.
        Collected collected = collect(filter, from + size + 1, EXAMINED_CAP);
        if (from >= collected.located().size()) {
            return new Page(List.of(), collected.cut());
        }
        int end = Math.min(collected.located().size(), from + size);
        return new Page(collected.located().subList(from, end),
                collected.located().size() > from + size || collected.cut());
    }

    /** The most versions a live walk examines to fill one window when the filter index does not stand; past it the page
     *  is answered as is and marked as having more. */
    static final int EXAMINED_CAP = 20_000;

    private record Collected(List<Located> located, boolean cut) {
    }

    @Override
    public Optional<Facets> facets() throws IOException {
        return new FindingsFilterIndex(store).facets();
    }

    /**
     * Rebuild the findings-filter index, gated on the composite stamp - scan freshness followed by the
     * {@linkplain Findings#evictions eviction epoch} - so a pass whose findings have not moved is a no-op and an
     * eviction alone still rebuilds.
     */
    @Override
    public void reindex() throws IOException {
        String scanStamp = Findings.scanned(store).read().map(Instant::toString).orElse("");
        new FindingsFilterIndex(store).rebuild(this, scanStamp + ' ' + Findings.evictions(store).current());
    }

    /** Apply a row transform through the {@code findings} section - one compare-and-set. */
    private void apply(String ecosystem, String coordinate, String version,
                       UnaryOperator<List<Finding>> rowTransform) throws IOException {
        boolean[] moved = {false};
        metadata.mutate(ecosystem, coordinate, version, FindingsSection.TAG, FindingsSection.transform(rows -> {
            Set<String> before = standing(rows);
            List<Finding> after = rowTransform.apply(rows);
            moved[0] = !before.equals(standing(after));        // the committed run's value is what stands
            return after;
        }, Instant.now()));
        if (moved[0]) {
            Mailbox.CHANGED.post(store, ecosystem, coordinate, version);
        }
    }

    /** What a version's findings say of it to what relies on it: each active finding with its severity. A re-scan
     *  refreshing a finding's last sighting, or a label, leaves it as it was. */
    private static Set<String> standing(List<Finding> rows) {
        Set<String> standing = new HashSet<>();
        for (Finding finding : rows) {
            if (finding.active()) {
                standing.add(finding.source() + "\n" + finding.id() + "\n" + finding.severity());
            }
        }
        return standing;
    }

    /** Label-or-throw against a mutable row list, matching {@link #label}'s contract inside a batch: a classifier's
     *  re-run refreshes its own {@code (source, name)} opinion and leaves other sources' labels alone. */
    private static void applyLabel(List<Finding> rows, String source, String id, Finding.Label label,
                                   String ecosystem, String coordinate, String version) {
        for (int index = 0; index < rows.size(); index++) {
            Finding existing = rows.get(index);
            if (existing.source().equals(source) && existing.id().equals(id)) {
                List<Finding.Label> labels = new ArrayList<>(existing.labels());
                labels.removeIf(previous -> previous.source().equals(label.source())
                        && previous.name().equals(label.name()));
                labels.add(label);
                rows.set(index, new Finding(existing.id(), existing.source(), existing.kind(),
                        existing.category(), existing.severity(), existing.confidence(), existing.description(),
                        existing.references(), existing.provenance(), existing.attributes(),
                        existing.firstSeen(), existing.lastSeen(), existing.supersededBy(), labels));
                return;
            }
        }
        throw new IllegalArgumentException("No finding " + source + "/" + id + " recorded against "
                + ecosystem + " " + coordinate + " " + version + " to label");
    }

    private void emitFinding(String ecosystem, String coordinate, String version, Finding finding) {
        // A genuinely new finding is an event; best-effort, and a no-op without an event sink.
        EventSink.emit(store, RepositoryEvent.finding(ecosystem, coordinate, version, finding.source(), finding.id(),
                finding.severity() == null ? null : finding.severity().name(), finding.category(), Instant.now()));
    }

    /** Walk for the findings a filter matches, collecting at most {@code cap}. A coordinate filter is a direct key
     *  lookup, an ecosystem filter narrows to that subtree. */
    private Collected collect(Filter filter, int cap, int examinedCap) throws IOException {
        List<Located> located = new ArrayList<>();
        int[] examined = {0};
        boolean[] cut = {false};
        // The coordinate level is paged wide enough to cover the whole budget in one page, as each coordinate holds at
        // least one version: a filesystem scans the directory once per page, so at the drain width a budget twice that
        // read a million-name level three times per call.
        walk(filter, LEDGER.page(Math.max(BoundedChildren.DRAIN_PAGE, examinedCap + 1)), new LocatedSink() {
            @Override
            public boolean accept(Located candidate) {
                located.add(candidate);
                return located.size() >= cap;           // stop once the bounded window is full
            }

            @Override
            public boolean examined() {
                if (++examined[0] > examinedCap) {
                    cut[0] = true;
                    return true;
                }
                return false;
            }
        });
        return new Collected(located, cut[0]);
    }

    /** Deliver every finding a filter matches to {@code visitor}, streamed over the same walk as {@link #collect}, so a
     *  whole-ledger pass stays bounded in heap. */
    @Override
    public void all(Filter filter, Visitor visitor) throws IOException {
        walk(filter, LEDGER, candidate -> {
            visitor.accept(candidate);
            return false;                               // a full streaming pass never stops early
        });
    }

    /** The walk behind {@link #collect} and {@link #all(Filter, Visitor)}: each match is offered to {@code sink}, which
     *  returns true to stop, and {@code coordinates} pages the level of coordinate names under each ecosystem. */
    private void walk(Filter filter, BoundedChildren coordinates, LocatedSink sink) throws IOException {
        // The root is spelled at the call site so the storage-namespace census, which resolves a key only there, sees
        // it.
        try {
            eachEcosystem(filter, MetadataKey.PREFIX, ecosystem ->
                    eachCoordinate(filter, ecosystem, MetadataKey.PREFIX, coordinates, encoded ->
                            LEDGER.scan(store, MetadataKey.PREFIX + "/" + ecosystem + "/" + encoded, version -> {
                                if (version.equals(MetadataKey.COORDINATE)) {
                                    return;                      // the per-coordinate document, not a version's
                                }
                                emit(filter, sink, ecosystem, encoded, version);
                            })));
        } catch (Stop _) {
            // the sink asked to stop
        }
    }

    /** Offer one coordinate version's recorded rows to the sink, stopping the whole descent when it says so. */
    private void emit(Filter filter, LocatedSink sink, String ecosystem, String encoded, String version)
            throws IOException {
        if (sink.examined()) {
            throw new Stop();                                    // the bounded collect's examined budget is spent
        }
        String coordinate = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        for (Finding finding : rows(ecosystem, coordinate, version)) {
            if (offer(filter, sink, ecosystem, coordinate, version, finding)) {
                throw new Stop();                                // the bounded collect's window is full
            }
        }
    }

    /** The rows recorded for one coordinate version. */
    private List<Finding> rows(String ecosystem, String coordinate, String version) throws IOException {
        Optional<Section> section = metadata.section(ecosystem, coordinate, version, FindingsSection.TAG);
        return section.isEmpty() ? List.of() : FindingsSection.rows(section);
    }

    /** The early-stop signal for a walk nested three enumerations deep - the cancellation hook {@link BoundedChildren}
     *  documents - so {@code collect} stops at its window. */
    private static final class Stop extends RuntimeException {

        private Stop() {
            super(null, null, false, false);                      // a control signal: no message, no stack trace
        }
    }

    /** Offer one finding to the sink if it matches the filter, returning the sink's stop answer; a non-match never
     *  stops. */
    private static boolean offer(Filter filter, LocatedSink sink, String ecosystem, String coordinate,
                                 String version, Finding finding) throws IOException {
        Located candidate = new Located(ecosystem, coordinate, version, finding);
        return filter.matches(candidate) && sink.accept(candidate);
    }

    @FunctionalInterface
    private interface LocatedSink {
        boolean accept(Located located) throws IOException;

        /** Called once per version the walk is about to read; true stops the walk before reading it. */
        default boolean examined() {
            return false;
        }
    }

    /** The enumeration every level of the ledger scan streams through. The sink wants every match, so neither the entry
     *  cap nor the step budget may end it; it bounds heap, one page of names at a time. It pages at the drain page
     *  because a filesystem cannot seek a directory and rescans it per page, and {@code meta/<ecosystem>} holds one
     *  child per coordinate. A coordinate-filtered call resolves by direct key and enumerates nothing. */
    private static final BoundedChildren LEDGER =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).steps(Integer.MAX_VALUE)
                    .page(BoundedChildren.DRAIN_PAGE);

    /** The ecosystem subtrees a walk visits: every stored ecosystem, or only those matching the filter's, compared
     *  case-insensitively as {@link build.jenesis.repository.findings.Findings.Filter#matches} does. */
    private void eachEcosystem(Filter filter, String root, BoundedChildren.Names names) throws IOException {
        LEDGER.scan(store, root, ecosystem -> {
            if (filter.ecosystem() == null || ecosystem.equalsIgnoreCase(filter.ecosystem())) {
                names.accept(ecosystem);
            }
        });
    }

    /** The encoded coordinate segments a walk visits under an ecosystem: every coordinate, or - the push-down - only
     *  the ones the coordinate filter names, probed directly. The filter value may be a bare coordinate or
     *  {@code coordinate:version}, so both the whole value and its prefix before the last colon are candidates. */
    private void eachCoordinate(Filter filter, String ecosystem, String root, BoundedChildren coordinates,
                                BoundedChildren.Names names) throws IOException {
        if (filter.coordinate() == null) {
            coordinates.scan(store, root + "/" + ecosystem, names);
            return;
        }
        SequencedSet<String> candidates = new LinkedHashSet<>();
        candidates.add(URLEncoder.encode(filter.coordinate(), StandardCharsets.UTF_8));
        int lastColon = filter.coordinate().lastIndexOf(':');
        if (lastColon > 0) {
            candidates.add(URLEncoder.encode(filter.coordinate().substring(0, lastColon), StandardCharsets.UTF_8));
        }
        for (String candidate : candidates) {
            names.accept(candidate);
        }
    }
}
