package build.jenesis.repository.findings.store;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.walk.BoundedChildren;

/**
 * The findings ledger over one repository's scoped store, consolidated into the {@code findings} section of
 * the unified per-coordinate metadata document ({@link MetadataKey#version}) that replaces the standalone
 * {@code findings/} sidecar. Each coordinate version's findings are one tagged section of its document - a version's
 * whole record is still a point lookup, the repository-wide view still walks a name tree (now {@code meta/}, never an
 * artifact body), and an eviction reclaims the version with a single document delete. Every mutation re-reads, merges
 * only the {@code findings} section and commits through the store's section-scoped compare-and-set, carrying every
 * other section verbatim, so the sweep, the gate and an on-demand report writing the same coordinate converge on the
 * union of their rows instead of losing an update - and a licenses or publish writer on the same document never
 * collides beyond the CAS token.
 *
 * <p>Categorize-never-discard is unchanged: {@link #record} only appends or refreshes (keeping an existing row's
 * {@code firstSeen}, labels and supersession mark), {@link #supersede} marks a row rather than deleting it, and nothing
 * here removes a row - the only removals are the artifact lifecycle's own (the inventory's {@code evict}, which now
 * takes the whole document, and the discarded-hold observer, which drops just the findings section). The 
 * row-carry is preserved inside {@link FindingsSection}: a row a newer node wrote that this one cannot parse rides
 * every mutate untouched.
 *
 * <p><strong>Batched commit (§4a).</strong> {@link #commit} folds a whole batch of rows and labels into <em>one</em>
 * section mutate, so a scan pass's advisory rows or an AI sweep's per-finding labels cost one compare-and-set for the
 * coordinate version, not one per row.
 *
 * <p><strong>One layout at a time.</strong> With the consolidated metadata store installed, both the point read and
 * the repository-wide walk are the {@code meta} documents' findings sections and nothing else. With no store installed
 * at all, both reads and writes stay on the {@code findings/} sidecar, so a deployment without {@code metadata.store}
 * degrades gracefully rather than losing its findings. What is deliberately absent is a fall-through between the two:
 * a document with no findings section means <em>nothing recorded</em>, and a walk that unioned the two planes would
 * have to dedup them.
 */
public final class StoreFindings implements Findings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreFindings.class);


    private final ArtifactStore store;

    /** The consolidated metadata store this repository's document lives in, or {@code null} when no
     *  {@link MetadataProvider} is installed (the graceful-absence path stays on the {@code findings/} sidecar). */
    private final MetadataStore metadata;

    public StoreFindings(ArtifactStore store) {
        this(store, MetadataProvider.installed().map(provider -> provider.over(store)).orElse(null));
    }

    /** Bind the ledger to an explicit metadata store (or {@code null} for the graceful-absence sidecar path) rather
     *  than the discovered one - the seam a distribution wires directly, and a test uses to exercise one path
     *  deterministically. */
    public StoreFindings(ArtifactStore store, MetadataStore metadata) {
        this.store = store;
        this.metadata = metadata;
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
        if (metadata != null) {
            // The section is the whole answer: an absent one means nothing has been recorded for this version here,
            // not that its rows live under an older key.
            return metadata.read(ecosystem, coordinate, version)
                    .filter(document -> document.has(FindingsSection.TAG))
                    .map(document -> FindingsSection.rows(document.section(FindingsSection.TAG)))
                    .orElseGet(List::of);
        }
        return sidecarRows(ecosystem, coordinate, version);
    }

    @Override
    public List<Located> all(Filter filter) throws IOException {
        return collect(filter, Integer.MAX_VALUE, Integer.MAX_VALUE).located();
    }

    @Override
    public Page all(Filter filter, int offset, int limit) throws IOException {
        // A selective, non-coordinate filter (severity/kind/category/source) has no key push-down in the live walk, so it
        // would visit the whole findings plane and read every coordinate's metadata document to fill one page. Serve it
        // from the durable filter index when one is built: the index seeks the matching facet bucket and post-filters the
        // rest, so the read is bounded. A coordinate filter (a direct key-prefix lookup) or a bare/ecosystem-only filter
        // (a dense walk that stops at the window) already bounds the walk, and a not-yet-built index falls back to that
        // same walk below - never worse than before the index.
        if (FindingsFilterIndex.indexable(filter)) {
            Page indexed = new FindingsFilterIndex(store).read(filter, offset, limit);
            if (indexed != null) {
                return indexed;
            }
        }
        int from = Math.clamp(offset, 0, MAX_OFFSET);
        int size = Math.max(0, limit);
        // Collect one past the requested window so the walk can report whether more remain without materialising the
        // whole ledger - a bounded slice for a big repository's paged read rather than a full scan every request.
        Collected collected = collect(filter, from + size + 1, EXAMINED_CAP);
        if (from >= collected.located().size()) {
            return new Page(List.of(), collected.cut());
        }
        int end = Math.min(collected.located().size(), from + size);
        return new Page(collected.located().subList(from, end),
                collected.located().size() > from + size || collected.cut());
    }

    /** The most versions a live walk examines to fill one window when the filter index does not stand - past it the
     *  page is answered as-is and marked as having more, so a sparse filter over a very large ledger stays bounded
     *  instead of draining it for one request. */
    static final int EXAMINED_CAP = 20_000;

    private record Collected(List<Located> located, boolean cut) {
    }

    /**
     * Rebuild the durable findings-filter index from the ledger's current findings, gated on the composite freshness
     * stamp so a pass whose findings have not moved is a no-op. The stamp is the findings scan freshness followed by the
     * {@linkplain Findings#evictions eviction epoch}, so an eviction that did not move the scan stamp still rebuilds
     * the index (an evicted version's rows drop on the next index pass, not the next scan). Driven by the scheduled
     * {@code findings-filter-index} maintenance pass off the request path, exactly as the health/vulnerability rank
     * indexes are; the read stays eventually consistent, serving the last built generation.
     */
    @Override
    public Optional<Facets> facets() throws IOException {
        return new FindingsFilterIndex(store).facets();
    }

    @Override
    public void reindex() throws IOException {
        String scanStamp = Findings.scanned(store).read().map(Instant::toString).orElse("");
        new FindingsFilterIndex(store).rebuild(this, scanStamp + ' ' + Findings.evictions(store).current());
    }

    /** Apply a row transform through the {@code findings} section (the consolidated store) or, in graceful absence,
     *  the {@code findings/} sidecar - one compare-and-set either way. */
    private void apply(String ecosystem, String coordinate, String version,
                       UnaryOperator<List<Finding>> rowTransform) throws IOException {
        if (metadata != null) {
            metadata.mutate(ecosystem, coordinate, version, FindingsSection.TAG,
                    FindingsSection.transform(rowTransform, Instant.now()));
        } else {
            sidecarMutate(ecosystem, coordinate, version, rowTransform);
        }
    }

    /** Label-or-throw against a mutable row list, matching {@link #label}'s contract inside a batch: a classifier's
     *  re-run refreshes its own {@code (source, name)} opinion, other sources' labels are untouched. */
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
        // A genuinely new finding (not a refresh of an existing row) is an event an external system may want to react
        // to; best-effort and a no-op when no event sink (the webhook module) is installed.
        EventSink.emit(store, RepositoryEvent.finding(ecosystem, coordinate, version, finding.source(), finding.id(),
                finding.severity() == null ? null : finding.severity().name(), finding.category(), Instant.now()));
    }

    /**
     * Walk for the findings a filter matches, collecting at most {@code cap} of them. With the consolidated store
     * installed the walk is the {@code meta} documents' findings sections; with no store installed it is the plain
     * sidecar walk. A coordinate-scoped filter is a direct key-prefix lookup under each tree (the canonical key codec
     * is shared, so one push-down serves both), an ecosystem-scoped filter narrows to that ecosystem's subtree.
     */
    private Collected collect(Filter filter, int cap, int examinedCap) throws IOException {
        List<Located> located = new ArrayList<>();
        int[] examined = {0};
        boolean[] cut = {false};
        walk(filter, new LocatedSink() {
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

    /**
     * Deliver every finding a filter matches to {@code visitor}, streamed: the same union/dedup {@link #walk} that
     * {@link #collect} runs, but emitting each match instead of accumulating them, so a whole-ledger pass - a console
     * facet fold, a background reclassification - stays bounded in heap on a repository with a very large finding set
     * rather than materialising the whole matched list.
     */
    @Override
    public void all(Filter filter, Visitor visitor) throws IOException {
        walk(filter, candidate -> {
            visitor.accept(candidate);
            return false;                               // a full streaming pass never stops early
        });
    }

    /** The shared walk behind {@link #collect} and {@link #all(Filter, Visitor)}: each match is offered to
     *  {@code sink}, which returns true to stop the walk early (the bounded collect) or false to continue (the
     *  streaming visitor). One layout is walked, never a union of two - the document sections when a consolidated
     *  store is installed, the {@code findings/} sidecars when none is. */
    private void walk(Filter filter, LocatedSink sink) throws IOException {
        // One layout or the other, each naming its own root at every level. The two branches are kept apart rather
        // than folded behind a `root` variable so the roots this ledger reads stay legible to the storage-namespace
        // census, which resolves a key only where it is spelled at the call site (the manifest's ratchet went
        // blind to `meta`/`findings` the moment the listing became a helper call through a parameter).
        try {
            if (metadata != null) {
                eachEcosystem(filter, MetadataKey.PREFIX, ecosystem ->
                        eachCoordinate(filter, ecosystem, MetadataKey.PREFIX, encoded ->
                                LEDGER.scan(store, MetadataKey.PREFIX + "/" + ecosystem + "/" + encoded, version -> {
                                    if (version.equals(MetadataKey.COORDINATE)) {
                                        return;                  // the per-coordinate document, not a version's
                                    }
                                    emit(filter, sink, ecosystem, encoded, version);
                                })));
                return;
            }
            eachEcosystem(filter, Findings.PREFIX, ecosystem ->
                    eachCoordinate(filter, ecosystem, Findings.PREFIX, encoded ->
                            LEDGER.scan(store, Findings.PREFIX + "/" + ecosystem + "/" + encoded, version ->
                                    emit(filter, sink, ecosystem, encoded, version))));
        } catch (Stop _) {
            // the sink asked to stop; the enumeration is abandoned without draining another container
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

    /** The rows recorded for one coordinate version, from whichever layout this ledger reads. */
    private List<Finding> rows(String ecosystem, String coordinate, String version) throws IOException {
        if (metadata == null) {
            return sidecarRows(ecosystem, coordinate, version);
        }
        Optional<Section> section = metadata.section(ecosystem, coordinate, version, FindingsSection.TAG);
        return section.isEmpty() ? List.of() : FindingsSection.rows(section);
    }

    /** The early-stop signal for a sink-driven walk nested three enumerations deep - the cancellation hook
     *  {@link BoundedChildren} documents (a throw from the name consumer abandons the enumeration), so the bounded
     *  {@code collect} still stops at its window instead of draining the ledger to fill it. */
    private static final class Stop extends RuntimeException {

        private Stop() {
            super(null, null, false, false);                      // a control signal: no message, no stack trace
        }
    }

    /** Match one finding against the filter and, when it matches, offer it to the sink; the sink's return value (stop)
     *  is propagated so the walk can terminate early. A non-matching finding is neither offered nor a stop signal. */
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

    /**
     * The enumeration every level of the ledger scan streams through. The sink's contract is every matching finding -
     * the paged and Visitor legs are both implemented over this walk - so neither the entry cap nor the step budget
     * may end it; what it bounds is <b>heap</b>, one page of names at a time.
     *
     * <p>That is the fix, and it is the health ledger's twin one level deeper. An unfiltered walk materialised
     * the whole coordinate list of an ecosystem (through the coordinate-segment step, which listed the ecosystem's
     * subtree when the filter named no coordinate) and then the whole version list of each coordinate - so the sink
     * form the two bounded legs are built on was itself two whole listings deep. A coordinate-filtered call resolves
     * by direct key and never enumerated anything; the unfiltered one is the ledger scan.
     */
    /** The ledger's walk pages at the drain page rather than the primitive's default: a filesystem cannot seek a
     *  directory, so every page rescans its container, and the level under {@code meta/<ecosystem>} holds one child
     *  per coordinate. Paged a thousand at a time, the bounded pre-index read (twenty thousand examined, two kinds,
     *  plus the served-ledger probe) rescanned a million-entry directory some sixty times and answered in 29 s -
     *  measured by the refresh-walk canary; ten times wider it is a handful of rescans. */
    private static final BoundedChildren LEDGER =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).steps(Integer.MAX_VALUE)
                    .page(BoundedChildren.DRAIN_PAGE);

    /** The ecosystem subtrees a filter's walk visits under a tree root: every stored ecosystem, or - when the filter
     *  pins one - only the matching stored ecosystem(s) (case-insensitively, as {@link build.jenesis.repository.findings.Findings.Filter#matches} compares). */
    private void eachEcosystem(Filter filter, String root, BoundedChildren.Names names) throws IOException {
        LEDGER.scan(store, root, ecosystem -> {
            if (filter.ecosystem() == null || ecosystem.equalsIgnoreCase(filter.ecosystem())) {
                names.accept(ecosystem);
            }
        });
    }

    /** The encoded coordinate segments a filter's walk visits under an ecosystem in a tree: every coordinate when the
     *  filter pins none, or - the push-down - just the encoded segment(s) the coordinate filter names, probed directly.
     *  The filter value is either the bare coordinate or the {@code coordinate:version} form, so both the whole value
     *  and its pre-last-colon prefix are candidate segments; a segment that holds nothing simply yields no versions. */
    private void eachCoordinate(Filter filter, String ecosystem, String root, BoundedChildren.Names names)
            throws IOException {
        if (filter.coordinate() == null) {
            LEDGER.scan(store, root + "/" + ecosystem, names);
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

    /** The rows of one coordinate version's {@code findings/} sidecar - the graceful-absence read; empty when the
     *  sidecar was never written. */
    private List<Finding> sidecarRows(String ecosystem, String coordinate, String version) throws IOException {
        return store.readVersioned(Findings.key(ecosystem, coordinate, version))
                .map(versioned -> FindingsSection.parse(readTree(versioned.content())).recognised())
                .orElse(List.of());
    }

    /** Re-read, transform and compare-and-set a coordinate version's {@code findings/} sidecar, retrying a concurrent
     *  writer's conflict a bounded number of times - the graceful-absence path when no consolidated store is present. */
    private void sidecarMutate(String ecosystem, String coordinate, String version,
                               UnaryOperator<List<Finding>> mutation) throws IOException {
        Retries.update(store, Findings.key(ecosystem, coordinate, version), current -> {
            FindingsSection.Document document = current
                    .map(versioned -> FindingsSection.parse(readTree(versioned.content())))
                    .orElseGet(FindingsSection.Document::empty);
            List<Finding> rows = mutation.apply(new ArrayList<>(document.recognised()));
            return JSON.writeValueAsBytes(FindingsSection.serialize(rows, document.carried()));
        });
    }

    private static JsonNode readTree(byte[] content) {
        try {
            return JSON.readTree(content);
        } catch (RuntimeException e) {
            LOGGER.warn("Skipping an unreadable findings document", e);
            return JSON.createObjectNode();
        }
    }
}
